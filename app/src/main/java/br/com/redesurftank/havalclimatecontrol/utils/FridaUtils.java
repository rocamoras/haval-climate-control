package br.com.redesurftank.havalclimatecontrol.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import br.com.redesurftank.havalclimatecontrol.App;
import br.com.redesurftank.havalclimatecontrol.R;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Injeção de scripts Frida em processos do sistema, via Shizuku.
 *
 * Dois alvos hoje:
 *   - {@link #TARGET_SYSTEM_UI}: destrava a temperatura externa real na barra de
 *     status (res/raw/com_android_systemui.js).
 *   - {@link #TARGET_MEDIA_CENTER}: desenha o card de clima na Home da MediaCenter
 *     (res/raw/com_beantechs_mediacenter_card.js).
 *
 * O fridaserver é único e compartilhado pelos dois; cada alvo tem o seu processo
 * injetor, identificado na linha de comando pelo nome do script — é por isso que
 * desligar um recurso não derruba o outro.
 *
 * Portado (simplificado) do app irmão haval-engine-reverse. Requer os binários arm64
 * `fridaserver` e `fridainject` em res/raw. No repositório eles são placeholders
 * minúsculos; o build do CI substitui pelos binários reais (16.7.19).
 */
public class FridaUtils {
    private static final String TAG = "FridaUtils";

    public static final String FRIDA_SERVER_PATH   = "/data/local/tmp/fridaserver";
    public static final String FRIDA_INJECTOR_PATH = "/data/local/tmp/fridainjector";

    /** Arquivo de controle lido em runtime pelo script da MediaCenter (on/off). */
    public static final String HOME_CARD_CTRL_PATH = "/data/local/tmp/haval_home_card";

    /** Descrição de um alvo de injeção: processo, script e caminhos derivados. */
    public static final class Target {
        public final String processName;
        public final String scriptName;   // sem .js — identifica o injetor no pkill
        public final int    scriptRes;

        Target(String processName, String scriptName, int scriptRes) {
            this.processName = processName;
            this.scriptName  = scriptName;
            this.scriptRes   = scriptRes;
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
    }

    public static final Target TARGET_SYSTEM_UI = new Target(
            "com.android.systemui", "com_android_systemui", R.raw.com_android_systemui);

    public static final Target TARGET_MEDIA_CENTER = new Target(
            "com.beantechs.mediacenter", "com_beantechs_mediacenter_card",
            R.raw.com_beantechs_mediacenter_card);

    private static final Target[] ALL_TARGETS = { TARGET_SYSTEM_UI, TARGET_MEDIA_CENTER };

    /**
     * Sentinela que o script grava no proprio log quando as classes do alvo nao
     * existem no processo em que ele foi injetado. Tem de casar com o texto do JS.
     */
    static final String WRONG_TARGET_MARK = "ALVO ERRADO";

    /** Teto de espera pelo fridaserver recem-iniciado. */
    private static final long SERVER_READY_TIMEOUT_MS = 2_500;
    private static final long SERVER_POLL_MS          = 100;

    /** Abaixo disso o arquivo em res/raw é um placeholder, não o binário real. */
    private static final long MIN_REAL_BINARY_BYTES = 100_000L;

    /** true se os binários reais do Frida estão embutidos neste APK. */
    public static boolean fridaToolsEmbedded() {
        try {
            return rawResourceSize(R.raw.fridaserver) > MIN_REAL_BINARY_BYTES
                    && rawResourceSize(R.raw.fridainject) > MIN_REAL_BINARY_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Temperatura Externa Real (UI) — SystemUI
    // ─────────────────────────────────────────────────────────────

    /** Extrai binários+script, sobe o fridaserver e injeta o hook no SystemUI. */
    public static String startAndInject() {
        return inject(TARGET_SYSTEM_UI);
    }

    /** Encerra a injeção no SystemUI. O hook some quando o SystemUI reinicia. */
    public static String stop() {
        return stopTarget(TARGET_SYSTEM_UI);
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
     * Liga o card: grava o arquivo de controle e injeta na MediaCenter. O script
     * relê o arquivo a cada 1,5 s, então o estado vale mesmo se a injeção já
     * estiver de pé.
     */
    public static String startHomeCard() {
        writeHomeCardCtrl(true);
        return inject(TARGET_MEDIA_CENTER);
    }

    /**
     * Desliga o card. Só grava "off" e deixa a injeção viva por enquanto: matar o
     * injetor na hora congelaria os hooks com a fileira de mídia vazia até o
     * processo reiniciar. Quem encerra de fato é {@link #stopHomeCardInjection()},
     * chamado pelo serviço depois que o script teve tempo de restaurar a tela.
     */
    public static String stopHomeCard() {
        writeHomeCardCtrl(false);
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

    private static void writeHomeCardCtrl(boolean enabled) {
        try {
            File tmp = new File(App.getContext().getCacheDir(), "haval_home_card");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write((enabled ? "on" : "off").getBytes("UTF-8"));
            }
            ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"cp", "-f", tmp.getAbsolutePath(), HOME_CARD_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"chmod", "644", HOME_CARD_CTRL_PATH});
        } catch (Exception e) {
            Log.e(TAG, "[frida] falha ao gravar " + HOME_CARD_CTRL_PATH + ": " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Mecânica comum
    // ─────────────────────────────────────────────────────────────

    /** Extrai binários+script, garante o fridaserver de pé e injeta no alvo. */
    public static String inject(Target t) {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK do Release/CI.";
        try {
            if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
            if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            if (!extract(t.scriptRes, t.scriptPath())) return "Falha ao extrair script";

            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            // Num unico shell: cada newProcess() custa um fork+binder, e no boot esses
            // milissegundos sao exatamente a janela em que a fileira do OEM aparece.
            ShizukuUtils.countNewProcess();
            svc.newProcess(new String[]{"/bin/sh", "-c",
                    "setenforce 0; chmod 755 " + FRIDA_SERVER_PATH + " " + FRIDA_INJECTOR_PATH},
                    null, null).waitFor();

            String running = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", "fridaserver"}).trim();
            if (running.isEmpty()) {
                ShizukuUtils.countNewProcess();
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"}, null, null).waitFor();
                // Poll em vez de sleep fixo: o server normalmente responde em ~200ms,
                // e esperar 1,5s sempre custava mais do que o necessario.
                if (!waitForFridaServer(SERVER_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "[frida] fridaserver nao respondeu em "
                            + SERVER_READY_TIMEOUT_MS + "ms, injetando de todo jeito");
                }
            }

            String pid = pidOf(t);
            if (pid.isEmpty()) return t.processName + " não encontrado (pid vazio)";

            // Idempotente: remove injeções anteriores NESTE alvo antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", t.scriptName});

            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid
                    + " -s " + t.scriptPath()
                    + " > " + t.logPath() + " 2>&1 < /dev/null &";
            ShizukuUtils.countNewProcess();
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado em " + t.processName + " pid=" + pid);
            return "Injetado em " + t.processName + " (pid " + pid + ")";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /**
     * Encerra só o injetor deste alvo. O fridaserver é derrubado apenas quando
     * nenhum outro alvo continua injetado — senão desligar um recurso mataria o
     * outro junto.
     */
    public static String stopTarget(Target t) {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", t.scriptName});
            if (!anyInjectionAlive()) {
                ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridaserver"});
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

        TargetStatus(String pid, boolean injectorAlive, boolean wrongTarget) {
            this.pid           = pid;
            this.injectorAlive = injectorAlive;
            this.wrongTarget   = wrongTarget;
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
               // log do card cresce sem limite (o script escreve a cada 1,5s). A sentinela,
               // quando existe, e escrita no comeco da injecao e o arquivo e truncado a
               // cada injecao nova — mas o tail cobre o caso do arquivo ja ter crescido.
               .append("{ head -c 4000 ").append(t.logPath())
               .append("; tail -c 4000 ").append(t.logPath())
               .append("; } 2>/dev/null | grep -q '").append(WRONG_TARGET_MARK)
               .append("' && w=1 || w=0; ")
               .append("echo \"").append(i).append(" ${p:--} $a $w\"; ");
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
            if (f.length != 4) continue;
            int idx;
            try { idx = Integer.parseInt(f[0]); } catch (NumberFormatException e) { continue; }
            if (idx < 0 || idx >= out.length || out[idx] != null) continue;
            out[idx] = new TargetStatus("-".equals(f[1]) ? "" : f[1],
                    "1".equals(f[2]), "1".equals(f[3]));
            filled++;
        }
        // Resposta parcial e tao perigosa quanto nenhuma: melhor nao decidir nada.
        if (filled != out.length) {
            Log.w(TAG, "[probe] saida incompleta (" + filled + "/" + out.length + "): " + r.stdout);
            return null;
        }
        return out;
    }

    /** true assim que `pidof fridaserver` responde, ou false no timeout. */
    private static boolean waitForFridaServer(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String pid = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pidof", "fridaserver"}).trim();
            if (!pid.isEmpty()) return true;
            Thread.sleep(SERVER_POLL_MS);
        }
        return false;
    }

    private static long rawResourceSize(int resId) throws Exception {
        try (InputStream in = App.getContext().getResources().openRawResource(resId)) {
            long total = 0;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) total += r;
            return total;
        }
    }

    private static boolean extract(int resId, String destPath) {
        try {
            File tmp = new File(App.getContext().getCacheDir(), new File(destPath).getName());
            try (InputStream in = App.getContext().getResources().openRawResource(resId);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"cp", "-f", tmp.getAbsolutePath(), destPath});
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[frida] extract falhou (" + destPath + "): " + e.getMessage(), e);
            return false;
        }
    }
}
