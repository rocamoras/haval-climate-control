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
 * Injeção de um script Frida no processo do SystemUI para destravar a exibição da
 * temperatura externa real na barra de status (ver res/raw/com_android_systemui.js).
 *
 * Portado (simplificado) do app irmão haval-engine-reverse. Requer os binários arm64
 * `fridaserver` e `fridainject` em res/raw. No repositório eles são placeholders
 * minúsculos; o build do CI substitui pelos binários reais (16.7.19).
 */
public class FridaUtils {
    private static final String TAG = "FridaUtils";

    public static final String FRIDA_SERVER_PATH   = "/data/local/tmp/fridaserver";
    public static final String FRIDA_INJECTOR_PATH = "/data/local/tmp/fridainjector";
    public static final String SCRIPT_PATH         = "/data/local/tmp/com_android_systemui.js";
    public static final String LOG_PATH            = "/data/local/tmp/com_android_systemui.log";
    private static final String TARGET_PROCESS     = "com.android.systemui";

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

    /** Extrai binários+script, sobe o fridaserver e injeta o hook no SystemUI. Retorna msg de status. */
    public static String startAndInject() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK do Release/CI.";
        try {
            if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
            if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            if (!extract(R.raw.com_android_systemui, SCRIPT_PATH)) return "Falha ao extrair script";

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

            String pid = systemUiPid();
            if (pid.isEmpty()) return "SystemUI não encontrado (pid vazio)";

            // Idempotente: remove injeções anteriores no SystemUI antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "com_android_systemui"});

            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid + " -s " + SCRIPT_PATH
                    + " > " + LOG_PATH + " 2>&1 < /dev/null &";
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado no SystemUI pid=" + pid);
            return "Injetado no SystemUI (pid " + pid + "). Veja a barra.";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /** Encerra fridaserver/fridainjector. O hook some quando o SystemUI reinicia. */
    public static String stop() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridainjector"});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridaserver"});
            return "Frida encerrado (o hook cai no próximo restart do SystemUI)";
        } catch (Exception e) {
            return "Erro ao encerrar: " + e.getMessage();
        }
    }

    /**
     * true se o processo injetor no SystemUI ainda está vivo. Usado pelo watchdog
     * do serviço para decidir se precisa re-injetar.
     */
    public static boolean isInjectionAlive() {
        if (!Shizuku.pingBinder()) return false;
        try {
            String out = ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pgrep", "-f", "com_android_systemui"}).trim();
            return !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** pid atual do com.android.systemui (vazio se não estiver rodando). */
    public static String systemUiPid() {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                "ps -A | grep ' " + TARGET_PROCESS + "' | awk '{print $2}'"}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.isEmpty())
            pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", TARGET_PROCESS}).trim();
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
