package br.com.redesurftank.havalclimatecontrol.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import br.com.redesurftank.havalclimatecontrol.App;
import br.com.redesurftank.havalclimatecontrol.R;
import rikka.shizuku.Shizuku;

/**
 * Injeção de scripts Frida em processos do sistema, via Shizuku.
 *
 * Dois alvos hoje:
 *   - {@link #TARGET_SYSTEM_UI}: guarda do popup do HVAC do OEM e, se ligada, a
 *     temperatura externa real na barra (res/raw/com_android_systemui.js). Fica
 *     injetado sempre: a guarda é o que substitui o `pm disable` a cada escrita.
 *   - {@link #TARGET_MEDIA_CENTER}: desenha o card de clima na Home da MediaCenter
 *     (res/raw/com_beantechs_mediacenter_card.js).
 *
 * Cada alvo tem o seu processo injetor, identificado na linha de comando pelo nome do
 * script — é por isso que desligar um recurso não derruba o outro. Não há frida-server:
 * com `-D local` o frida-inject injeta sozinho, e cada processo Frida a menos é uma
 * chance a menos de mexer no SELinux no meio do boot (ver o patch no build.yml).
 *
 * Portado (simplificado) do app irmão haval-engine-reverse. Requer o binário arm64
 * `fridainject` em res/raw. No repositório ele é um placeholder minúsculo; o build do
 * CI substitui pelo binário real (16.7.19), com a recarga da política do SELinux
 * desligada.
 */
public class FridaUtils {
    private static final String TAG = "FridaUtils";

    public static final String FRIDA_INJECTOR_PATH = "/data/local/tmp/fridainjector";
    // Deixado pelas versoes ate 1.27.x, que subiam um frida-server: e morto e apagado
    // na primeira injecao, senao ficava rodando ate o proximo boot e ocupando ~50 MB.
    private static final String LEGACY_SERVER_PATH = "/data/local/tmp/fridaserver";
    /**
     * Versao do APK que extraiu os arquivos de /data/local/tmp. Enquanto bater, a injecao
     * nao toca no binario: antes cada injecao descompactava e copiava as dezenas de MB do
     * frida-inject de novo — inclusive a cada 1s no boot, esperando a MediaCenter subir.
     */
    private static final String STAMP_PATH = "/data/local/tmp/haval_frida.stamp";

    /** Arquivo de controle lido em runtime pelo script da MediaCenter (on/off). */
    public static final String HOME_CARD_CTRL_PATH = "/data/local/tmp/haval_home_card";
    /** Turbo ativo, "1"/"0" — escrito pelo serviço, lido pelo card de clima. */
    public static final String HOME_CARD_TURBO_PATH = "/data/local/tmp/haval_home_card_turbo";
    /** Arquivo de controle lido pelo script do SystemUI: pinta a temperatura real (on/off). */
    public static final String REAL_TEMP_CTRL_PATH = "/data/local/tmp/haval_real_temp";

    /** Descrição de um alvo de injeção: processo, script e caminhos derivados. */
    public static final class Target {
        public final String processName;
        public final String scriptName;   // sem .js — identifica o injetor no pkill
        public final int    scriptRes;
        /** Arquivo de controle on/off do script, ou null se ele nao tiver. */
        final String ctrlPath;

        Target(String processName, String scriptName, int scriptRes, String ctrlPath) {
            this.processName = processName;
            this.scriptName  = scriptName;
            this.scriptRes   = scriptRes;
            this.ctrlPath    = ctrlPath;
        }

        public String scriptPath() { return "/data/local/tmp/" + scriptName + ".js"; }
        public String logPath()    { return "/data/local/tmp/" + scriptName + ".log"; }

        /**
         * Padrao de `pgrep -f` que casa o injetor mas NAO o shell que executa o pgrep.
         * Dentro de {@link #probe} o comando inteiro vira a cmdline de um `sh -c`, e um
         * `pgrep -f com_android_systemui` ali acharia o proprio shell e responderia
         * "vivo" para sempre. O truque classico do colchete resolve: a regex
         * `[c]om_...` casa o texto `com_...` do injetor e nao casa o `[c]om_...`
         * literal da nossa propria cmdline.
         */
        public String pgrepPattern() {
            return "[" + scriptName.charAt(0) + "]" + scriptName.substring(1);
        }

        /**
         * Atribuicao de shell que monta o nome do script numa variavel sem que o nome
         * apareca inteiro na cmdline do `sh -c`. Mesmo motivo do {@link #pgrepPattern}:
         * o `pkill -f` dentro de {@link #inject} mataria o proprio shell se a linha de
         * comando dele trouxesse o caminho do script por extenso.
         */
        String nameVar(String var) {
            return var + "=" + scriptName.charAt(0) + "; " + var + "=\"${" + var + "}"
                    + scriptName.substring(1) + "\"";
        }
    }

    public static final Target TARGET_SYSTEM_UI = new Target(
            "com.android.systemui", "com_android_systemui", R.raw.com_android_systemui,
            REAL_TEMP_CTRL_PATH);

    public static final Target TARGET_MEDIA_CENTER = new Target(
            "com.beantechs.mediacenter", "com_beantechs_mediacenter_card",
            R.raw.com_beantechs_mediacenter_card, HOME_CARD_CTRL_PATH);

    private static final Target[] ALL_TARGETS = { TARGET_SYSTEM_UI, TARGET_MEDIA_CENTER };

    /**
     * Sentinela que o script grava no proprio log quando as classes do alvo nao
     * existem no processo em que ele foi injetado. Tem de casar com o texto do JS.
     */
    static final String WRONG_TARGET_MARK = "ALVO ERRADO";

    /**
     * Sentinela que o script do SystemUI grava quando o hook da guarda do HVAC entrou.
     * Tem de casar com o texto do JS. Sem ela o servico volta ao `pm disable`.
     */
    static final String GUARD_ARMED_MARK = "GUARDA HVAC ARMADA";

    /** Abaixo disso o arquivo em res/raw é um placeholder, não o binário real. */
    private static final long MIN_REAL_BINARY_BYTES = 100_000L;

    private static volatile Boolean embedded;
    private static volatile String  stamp;

    /**
     * true se o binário real do Frida está embutido neste APK. Memorizado, e a leitura
     * para assim que passa do placeholder: antes lia o binário inteiro a cada injeção, e a
     * tela de Configurações fazia isso na thread de UI.
     */
    public static boolean fridaToolsEmbedded() {
        Boolean e = embedded;
        if (e == null) {
            try (InputStream in = App.getContext().getResources().openRawResource(R.raw.fridainject)) {
                long total = 0;
                byte[] buf = new byte[8192];
                int r;
                while (total <= MIN_REAL_BINARY_BYTES && (r = in.read(buf)) != -1) total += r;
                e = total > MIN_REAL_BINARY_BYTES;
            } catch (Exception ex) {
                e = false;
            }
            embedded = e;
        }
        return e;
    }

    // ─────────────────────────────────────────────────────────────
    // SystemUI — guarda do HVAC + temperatura externa real
    // ─────────────────────────────────────────────────────────────

    /** Injeta o script do SystemUI, ja com o arquivo da temperatura real no estado pedido. */
    public static InjectResult injectSystemUi(boolean realTemp) {
        return inject(TARGET_SYSTEM_UI, realTemp);
    }

    /** Liga/desliga a pintura da temperatura real; o script relê a cada 2 s. */
    public static void setRealTemp(boolean enabled) {
        writeCtrl(REAL_TEMP_CTRL_PATH, enabled);
    }

    public static boolean isInjectionAlive() {
        return isInjectionAlive(TARGET_SYSTEM_UI);
    }

    /** pid atual do com.android.systemui (vazio se não estiver rodando). */
    public static String systemUiPid() {
        return pidOf(TARGET_SYSTEM_UI);
    }

    // ─────────────────────────────────────────────────────────────
    // Card na Home — MediaCenter
    // ─────────────────────────────────────────────────────────────

    /**
     * Liga o card: grava o arquivo de controle e injeta na MediaCenter, no mesmo shell.
     * O script relê o arquivo a cada 1,5 s, então o estado vale mesmo se a injeção já
     * estiver de pé.
     */
    public static InjectResult startHomeCard() {
        return inject(TARGET_MEDIA_CENTER, true);
    }

    /**
     * Desliga o card. Só grava "off" e deixa a injeção viva por enquanto: matar o
     * injetor na hora congelaria os hooks com a fileira de mídia vazia até o
     * processo reiniciar. Quem encerra de fato é {@link #stopHomeCardInjection()},
     * chamado pelo serviço depois que o script teve tempo de restaurar a tela.
     */
    public static String stopHomeCard() {
        writeCtrl(HOME_CARD_CTRL_PATH, false);
        return "Card na home desligado (restaurando a fileira original)";
    }

    /** Encerra o injetor da MediaCenter. Chamar só depois do restore do script. */
    public static String stopHomeCardInjection() {
        return stopTarget(TARGET_MEDIA_CENTER);
    }

    /**
     * true se o script do card anunciou {@link #WRONG_TARGET_MARK} — injetado num
     * processo que nao tem as classes do alvo. Le o log do injetor, que e truncado
     * a cada injecao, entao nunca responde por uma tentativa antiga.
     */
    public static boolean homeCardWrongTarget() {
        TargetStatus[] st = probe(TARGET_MEDIA_CENTER);
        return st != null && st[0].wrongTarget;
    }

    public static boolean isHomeCardInjectionAlive() {
        return isInjectionAlive(TARGET_MEDIA_CENTER);
    }

    public static String mediaCenterPid() {
        return pidOf(TARGET_MEDIA_CENTER);
    }

    /**
     * Selo TURBO do card de clima: o script lê {@link #HOME_CARD_TURBO_PATH} a cada
     * tick. Só nas transições do turbo — cada escrita é um fork no shizuku_server.
     */
    public static void writeHomeCardTurbo(boolean active) {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c",
                "echo " + (active ? "1" : "0") + " > " + HOME_CARD_TURBO_PATH
                        + " && chmod 644 " + HOME_CARD_TURBO_PATH});
        if (!r.ok()) Log.e(TAG, "[frida] falha ao gravar " + HOME_CARD_TURBO_PATH + ": " + r.describeFailure());
    }

    private static void writeCtrl(String path, boolean enabled) {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c",
                "echo " + (enabled ? "on" : "off") + " > " + path + " && chmod 644 " + path});
        if (!r.ok()) Log.e(TAG, "[frida] falha ao gravar " + path + ": " + r.describeFailure());
    }

    // ─────────────────────────────────────────────────────────────
    // Mecânica comum
    // ─────────────────────────────────────────────────────────────

    /** Resultado de uma injeção. */
    public static final class InjectResult {
        /** pid do alvo em que o injetor foi lançado; "" se não houve injeção. */
        public final String  pid;
        /** O processo injetor estava de pé logo depois de lançado. */
        public final boolean injectorAlive;
        public final String  message;

        InjectResult(String pid, boolean injectorAlive, String message) {
            this.pid           = pid;
            this.injectorAlive = injectorAlive;
            this.message       = message;
        }

        static InjectResult fail(String message) {
            return new InjectResult("", false, message);
        }
    }

    /**
     * Injeta no alvo num UNICO shell: arquivo de controle, setenforce, pid, limpeza do
     * injetor anterior, lançamento e conferência. Antes eram 8 a 10 newProcess por
     * injeção — cada um um fork no shizuku_server, que já morreu de OOM pelo acúmulo
     * deles (29/08/2026) — e no boot esses milissegundos são a janela da fileira do OEM.
     *
     * Os arquivos em /data/local/tmp só são (re)extraídos quando o shell avisa que o
     * carimbo de versão não bate: na prática, uma vez por instalação.
     */
    public static InjectResult inject(Target t, boolean ctrlOn) {
        if (!Shizuku.pingBinder()) return InjectResult.fail("Shizuku indisponível");
        if (!fridaToolsEmbedded())
            return InjectResult.fail("Binário do Frida ausente neste APK (placeholder). Use o APK do Release/CI.");

        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c", injectScript(t, ctrlOn)});
        if (r.exitCode == 3) {
            if (!extractAll()) return InjectResult.fail("Falha ao extrair o Frida para /data/local/tmp");
            r = ShizukuUtils.run(new String[]{"sh", "-c", injectScript(t, ctrlOn)});
        }

        String pid = "";
        boolean alive = false;
        for (String line : r.stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("PID=")) pid = line.substring(4).trim();
            else if (line.equals("ALIVE=1")) alive = true;
        }
        if (r.exitCode == 4) return InjectResult.fail(t.processName + " não encontrado (pid vazio)");
        if (!r.ok() || pid.isEmpty()) {
            Log.e(TAG, "[frida] injeção em " + t.processName + " falhou: " + r.describeFailure());
            return InjectResult.fail("Erro: " + r.describeFailure());
        }
        Log.w(TAG, "[frida] injetado em " + t.processName + " pid=" + pid);
        return new InjectResult(pid, alive, "Injetado em " + t.processName + " (pid " + pid + ")");
    }

    /**
     * O script de shell de {@link #inject}. Códigos de saída: 3 = arquivos ausentes ou de
     * outra versão (extrair e repetir), 4 = processo alvo não está rodando.
     *
     * `kill $(pidof ...)` para o server legado e não `pkill -f`: o -f casaria com este
     * próprio shell. Pelo mesmo motivo o nome do script entra por {@link Target#nameVar}.
     */
    private static String injectScript(Target t, boolean ctrlOn) {
        StringBuilder s = new StringBuilder();
        s.append("[ -x ").append(FRIDA_INJECTOR_PATH).append(" ] && [ \"$(cat ")
         .append(STAMP_PATH).append(" 2>/dev/null)\" = \"").append(stamp())
         .append("\" ] || exit 3; ");
        if (t.ctrlPath != null) {
            s.append("echo ").append(ctrlOn ? "on" : "off").append(" > ").append(t.ctrlPath)
             .append("; chmod 644 ").append(t.ctrlPath).append("; ");
        }
        // Sem o patch do SELinux no binario, o permissive e o que deixa o alvo mapear o agente.
        s.append("setenforce 0; ");
        s.append("old=$(pidof fridaserver); [ -n \"$old\" ] && kill $old; rm -f ")
         .append(LEGACY_SERVER_PATH).append("; ");
        s.append("p=$(").append(pidSnippet(t)).append("); ")
         .append("[ -z \"$p\" ] && p=$(pidof ").append(t.processName).append(" | awk '{print $1}'); ")
         .append("[ -z \"$p\" ] && exit 4; ");
        // Idempotente: remove injeções anteriores NESTE alvo antes de subir uma nova.
        s.append("pkill -f '").append(t.pgrepPattern()).append("'; ");
        s.append(t.nameVar("n")).append("; ");
        s.append("setsid ").append(FRIDA_INJECTOR_PATH).append(" -D local -p $p")
         .append(" -s /data/local/tmp/$n.js > /data/local/tmp/$n.log 2>&1 < /dev/null & ");
        s.append("echo PID=$p; ");
        // O filho do `&` so ganha a cmdline do injetor depois do exec: confere por ate ~0,5s.
        s.append("i=0; while [ $i -lt 10 ]; do pgrep -f '").append(t.pgrepPattern())
         .append("' >/dev/null && { echo ALIVE=1; break; }; sleep 0.05; i=$((i+1)); done; ");
        s.append("exit 0");
        return s.toString();
    }

    /** versionCode + instante da instalação: muda a cada APK novo, mesmo com o mesmo versionCode. */
    private static String stamp() {
        String s = stamp;
        if (s == null) {
            try {
                Context ctx = App.getContext();
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                s = pi.getLongVersionCode() + "-" + pi.lastUpdateTime;
            } catch (Exception e) {
                s = "desconhecido";
            }
            stamp = s;
        }
        return s;
    }

    /**
     * Extrai injetor e scripts para /data/local/tmp e grava o carimbo, num shell só.
     * `cp` para um .new e depois `mv`: sobrescrever o binário enquanto o injetor do outro
     * alvo roda falha com "Text file busy", e o rename troca o arquivo mesmo assim.
     */
    private static synchronized boolean extractAll() {
        File cache = App.getContext().getCacheDir();
        int[]    res   = { R.raw.fridainject, TARGET_SYSTEM_UI.scriptRes, TARGET_MEDIA_CENTER.scriptRes };
        String[] dests = { FRIDA_INJECTOR_PATH, TARGET_SYSTEM_UI.scriptPath(), TARGET_MEDIA_CENTER.scriptPath() };
        File[]   tmps  = new File[res.length];
        StringBuilder cmd = new StringBuilder("set -e; ");
        try {
            for (int i = 0; i < res.length; i++) {
                tmps[i] = new File(cache, new File(dests[i]).getName());
                copyRaw(res[i], tmps[i]);
                cmd.append("cp -f ").append(tmps[i].getAbsolutePath()).append(' ').append(dests[i]).append(".new; ")
                   .append("mv -f ").append(dests[i]).append(".new ").append(dests[i]).append("; ");
            }
            cmd.append("chmod 755 ").append(FRIDA_INJECTOR_PATH).append("; ")
               .append("echo '").append(stamp()).append("' > ").append(STAMP_PATH);
            ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c", cmd.toString()});
            if (!r.ok()) {
                PersistentLog.e(TAG, "[frida] extracao falhou: " + r.describeFailure());
                return false;
            }
            PersistentLog.w(TAG, "[frida] arquivos extraidos para /data/local/tmp (carimbo " + stamp() + ")");
            return true;
        } catch (Exception e) {
            PersistentLog.e(TAG, "[frida] extracao falhou: " + e);
            return false;
        } finally {
            // A copia em /data/local/tmp e a que vale; a do cache so ocupava dezenas de MB.
            for (File f : tmps) {
                //noinspection ResultOfMethodCallIgnored
                if (f != null) f.delete();
            }
        }
    }

    /** Encerra só o injetor deste alvo; o do outro alvo continua de pé. */
    public static String stopTarget(Target t) {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", t.scriptName});
            if (!anyInjectionAlive()) {
                return "Frida encerrado (o hook cai no próximo restart de " + t.processName + ")";
            }
            return "Injeção em " + t.processName + " encerrada";
        } catch (Exception e) {
            return "Erro ao encerrar: " + e.getMessage();
        }
    }

    /**
     * true se o processo injetor deste alvo ainda está vivo. Usado pelos watchdogs
     * do serviço para decidir se precisa re-injetar.
     */
    public static boolean isInjectionAlive(Target t) {
        if (!Shizuku.pingBinder()) return false;
        try {
            String out = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pgrep", "-f", t.scriptName}).trim();
            return !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean anyInjectionAlive() {
        for (Target t : ALL_TARGETS) if (isInjectionAlive(t)) return true;
        return false;
    }

    /**
     * Snippet de shell que resolve o pid do alvo por nome EXATO de processo.
     *
     * Nao usar `grep ' <nome>'`: e prefixo, e o OEM tem tres pacotes que comecam
     * igual — com.beantechs.mediacenter, .mediacenter.h5.core e .mediacenter.h5.ui.
     * O `head -1` entao entregava o de menor pid, e em 2026-09-02 o injetor do card
     * foi para o h5.core: todos os hooks morreram com ClassNotFoundException (as
     * classes do card estao so no APK do .mediacenter), o card nunca apareceu, e o
     * watchdog seguiu achando tudo bem porque o injetor ESTAVA vivo. `$NF` e o nome
     * do processo no `ps -A` do toybox, e o `pidof` da reserva ja casa exato.
     */
    private static String pidSnippet(Target t) {
        return "ps -A | awk '$NF == \"" + t.processName + "\" {print $2}' | head -1";
    }

    /** pid atual do processo do alvo (vazio se não estiver rodando). */
    public static String pidOf(Target t) {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                pidSnippet(t)}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.isEmpty())
            pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", t.processName}).trim();
        if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
        return pid;
    }

    /**
     * Idade do processo em ms, ou -1 se desconhecida (pid vazio, morto, ou shell ilegivel).
     *
     * Usa o mtime de /proc/<pid> (segundos desde a epoch), que o toybox `stat` do device
     * expoe e o shell le sem root — mais simples e robusto que decodificar o campo 22 de
     * /proc/<pid>/stat em jiffies. -1 significa "nao sei" e o chamador NAO deve bloquear
     * por isso: adiar por uma leitura falha seria pior que injetar.
     */
    public static long processAgeMs(String pid) {
        if (pid == null || pid.isEmpty()) return -1;
        try {
            String out = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                    "now=$(date +%s); st=$(stat -c %Y /proc/" + pid + " 2>/dev/null); "
                  + "[ -n \"$st\" ] && echo $((now - st))"}).trim();
            if (out.isEmpty()) return -1;
            if (out.contains("\n")) out = out.split("\n")[0].trim();
            long secs = Long.parseLong(out);
            return secs < 0 ? -1 : secs * 1000L;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Estado de um alvo colhido por {@link #probe}. */
    public static final class TargetStatus {
        /** pid do processo alvo, ou "" se ele nao esta rodando. */
        public final String  pid;
        /** true se o processo injetor daquele alvo continua vivo. */
        public final boolean injectorAlive;
        /**
         * true se o script anunciou que caiu num processo sem as classes do alvo.
         * Injetor vivo e injecao util sao coisas diferentes; so este campo separa.
         */
        public final boolean wrongTarget;
        /** true se o script anunciou {@link #GUARD_ARMED_MARK} (so o do SystemUI anuncia). */
        public final boolean guardArmed;

        TargetStatus(String pid, boolean injectorAlive, boolean wrongTarget, boolean guardArmed) {
            this.pid           = pid;
            this.injectorAlive = injectorAlive;
            this.wrongTarget   = wrongTarget;
            this.guardArmed    = guardArmed;
        }
    }

    /**
     * pid + injetor vivo de todos os alvos pedidos, num UNICO newProcess.
     *
     * Motivo de existir: cada newProcess() forka no shizuku_server e deixa la um
     * RemoteProcessHolder que so e liberado quando o proxy binder deste lado e
     * coletado. Os dois watchdogs de 10s faziam 4 shells por ciclo — ~9.000 numa
     * sessao de 5h43, e em 29/08/2026 o heap de 96MB do server estourou
     * (OutOfMemoryError em rikka.shizuku.Jj.run), matando o binder e forcando o
     * REINICIO "binder do Shizuku morreu". Um shell por ciclo, para todos os alvos.
     *
     * @return array paralelo a {@code targets}, ou null se o shell nao rodou / veio
     *         ilegivel. null significa "nao sei" e NUNCA "caiu": tratar como caiu
     *         faria uma falha do shell disparar re-injecao a toa.
     */
    public static TargetStatus[] probe(Target... targets) {
        if (targets.length == 0) return new TargetStatus[0];
        if (!Shizuku.pingBinder()) return null;

        // A chave de cada linha e o INDICE, nao o scriptName: o nome do script na
        // cmdline do shell faria o pgrep abaixo casar com ele mesmo.
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < targets.length; i++) {
            Target t = targets[i];
            // Mesma heuristica do pidOf(): `ps -A` primeiro, `pidof` como reserva.
            cmd.append("p=$(").append(pidSnippet(t)).append("); ")
               .append("[ -z \"$p\" ] && p=$(pidof ").append(t.processName)
               .append(" | awk '{print $1}'); ")
               .append("pgrep -f '").append(t.pgrepPattern())
               .append("' >/dev/null 2>&1 && a=1 || a=0; ")
               // Injetor vivo nao significa injecao util: se o script caiu num processo
               // sem as classes do alvo ele grava esta sentinela e desiste. Sem ler isso
               // o watchdog declara saude para sempre (ver pidSnippet).
               // `tail -c` antes do grep: sem match o grep leria o arquivo INTEIRO, e o
               // log do card cresce sem limite (o script escreve a cada 1,5s). As
               // sentinelas, quando existem, sao escritas no comeco da injecao e o arquivo
               // e truncado a cada injecao nova — mas o tail cobre o caso do arquivo ja
               // ter crescido.
               .append("c=$({ head -c 4000 ").append(t.logPath())
               .append("; tail -c 4000 ").append(t.logPath()).append("; } 2>/dev/null); ")
               .append("echo \"$c\" | grep -q '").append(WRONG_TARGET_MARK)
               .append("' && w=1 || w=0; ")
               .append("echo \"$c\" | grep -q '").append(GUARD_ARMED_MARK)
               .append("' && g=1 || g=0; ")
               .append("echo \"").append(i).append(" ${p:--} $a $w $g\"; ");
        }

        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c", cmd.toString()});
        if (!r.ok()) {
            Log.w(TAG, "[probe] falhou: " + r.describeFailure());
            return null;
        }

        TargetStatus[] out = new TargetStatus[targets.length];
        int filled = 0;
        for (String line : r.stdout.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 5) continue;
            int idx;
            try { idx = Integer.parseInt(f[0]); } catch (NumberFormatException e) { continue; }
            if (idx < 0 || idx >= out.length || out[idx] != null) continue;
            out[idx] = new TargetStatus("-".equals(f[1]) ? "" : f[1],
                    "1".equals(f[2]), "1".equals(f[3]), "1".equals(f[4]));
            filled++;
        }
        // Resposta parcial e tao perigosa quanto nenhuma: melhor nao decidir nada.
        if (filled != out.length) {
            Log.w(TAG, "[probe] saida incompleta (" + filled + "/" + out.length + "): " + r.stdout);
            return null;
        }
        return out;
    }

    private static void copyRaw(int resId, File dest) throws Exception {
        try (InputStream in = App.getContext().getResources().openRawResource(resId);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
    }
}
