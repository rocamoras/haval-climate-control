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
    }

    public static final Target TARGET_SYSTEM_UI = new Target(
            "com.android.systemui", "com_android_systemui", R.raw.com_android_systemui);

    public static final Target TARGET_MEDIA_CENTER = new Target(
            "com.beantechs.mediacenter", "com_beantechs_mediacenter_card",
            R.raw.com_beantechs_mediacenter_card);

    private static final Target[] ALL_TARGETS = { TARGET_SYSTEM_UI, TARGET_MEDIA_CENTER };

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
            svc.newProcess(new String[]{"setenforce", "0"}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_SERVER_PATH}, null, null).waitFor();
            svc.newProcess(new String[]{"chmod", "755", FRIDA_INJECTOR_PATH}, null, null).waitFor();

            String running = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", "fridaserver"}).trim();
            if (running.isEmpty()) {
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"}, null, null).waitFor();
                Thread.sleep(1500);
            }

            String pid = pidOf(t);
            if (pid.isEmpty()) return t.processName + " não encontrado (pid vazio)";

            // Idempotente: remove injeções anteriores NESTE alvo antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", t.scriptName});

            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid
                    + " -s " + t.scriptPath()
                    + " > " + t.logPath() + " 2>&1 < /dev/null &";
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

    /** pid atual do processo do alvo (vazio se não estiver rodando). */
    public static String pidOf(Target t) {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                "ps -A | grep ' " + t.processName + "' | awk '{print $2}'"}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.isEmpty())
            pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", t.processName}).trim();
        if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
        return pid;
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
