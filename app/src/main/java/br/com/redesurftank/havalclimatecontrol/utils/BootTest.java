package br.com.redesurftank.havalclimatecontrol.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Teste A/B do crash do system_server no boot: pula o servico nos proximos N boots.
 *
 * Pergunta que responde: o reinicio do framework ~30s apos o boot (NetworkPolicy ->
 * currentNetworkTimeMillis -> DeadSystemException, ver dropbox SYSTEM_RESTART) acontece
 * sem a gente? Num boot pulado o processo ate sobe (pelo BOOT_COMPLETED), mas nao
 * inicia o ClimateControlService — sem Shizuku, telnet, iptables nem Frida.
 *
 * Evidencia que fica para o upload:
 *   - uma linha "[teste-boot] boot pulado" por boot real, com o uptime;
 *   - se o framework cair num boot pulado, o nosso processo morre junto e o
 *     BOOT_COMPLETED chega de novo no MESMO boot_id: sai uma linha "mesmo boot" com
 *     uptime maior. Essa linha sozinha ja prova que o crash nao e nosso;
 *   - os SYSTEM_RESTART do dropbox, cruzando pelo horario.
 *
 * Os boots sao contados por boot_id do kernel, e nao por BOOT_COMPLETED: um reinicio do
 * framework reentrega o BOOT_COMPLETED e consumiria dois boots do contador num so.
 *
 * Prefs no storage device-protected (como o resto do servico): o LOCKED_BOOT_COMPLETED
 * chega antes do unlock.
 */
public final class BootTest {

    private static final String TAG = "BootTest";

    private static final String PREFS_NAME        = "climate_control_prefs";
    private static final String KEY_REMAINING     = "boot_test_skip_remaining";
    private static final String KEY_TOTAL         = "boot_test_skip_total";
    // Boot em que o teste foi armado: esse nao conta, o servico ja esta de pe nele.
    private static final String KEY_ARMED_BOOT_ID = "boot_test_armed_boot_id";
    // Boot que esta sendo pulado agora: toda chamada nele continua pulando, mesmo que o
    // contador ja tenha chegado a zero.
    private static final String KEY_SKIP_BOOT_ID  = "boot_test_skip_boot_id";

    private static boolean loggedInThisProcess = false;

    private BootTest() {}

    /**
     * Decide se quem esta subindo o servico ({@code origem}) deve deixar de subir.
     * Chamar ANTES do startForegroundService. Nunca lanca: na duvida, nao pula.
     */
    public static synchronized boolean shouldSkipServiceStart(Context ctx, String origem) {
        try {
            SharedPreferences p = prefs(ctx);
            String bootId = currentBootId();
            long uptimeS = SystemClock.elapsedRealtime() / 1000;

            if (bootId.equals(p.getString(KEY_SKIP_BOOT_ID, null))) {
                // Uma linha por processo: App.onCreate e os dois BOOT_COMPLETED caem aqui
                // no mesmo processo, e o que interessa e o processo NOVO (pid novo).
                if (!loggedInThisProcess) {
                    loggedInThisProcess = true;
                    PersistentLog.w(TAG, "[teste-boot] mesmo boot pulado, servico continua parado ("
                            + origem + ", uptime " + uptimeS + "s, boot_id " + shortId(bootId) + ")");
                }
                return true;
            }

            int remaining = p.getInt(KEY_REMAINING, 0);
            if (remaining <= 0) return false;
            if (bootId.equals(p.getString(KEY_ARMED_BOOT_ID, null))) return false;

            int total = p.getInt(KEY_TOTAL, remaining);
            int numero = total - remaining + 1;
            p.edit()
                    .putInt(KEY_REMAINING, remaining - 1)
                    .putString(KEY_SKIP_BOOT_ID, bootId)
                    .commit();
            loggedInThisProcess = true;
            PersistentLog.w(TAG, "[teste-boot] boot pulado " + numero + "/" + total
                    + " — servico NAO iniciado (" + origem + ", uptime " + uptimeS
                    + "s, boot_id " + shortId(bootId) + ")");
            return true;
        } catch (Throwable t) {
            PersistentLog.e(TAG, "[teste-boot] falha decidindo, servico sobe normal: " + t);
            return false;
        }
    }

    /** Arma o teste para os proximos {@code boots} boots (0 cancela). */
    public static synchronized void arm(Context ctx, int boots) {
        String bootId = currentBootId();
        prefs(ctx).edit()
                .putInt(KEY_REMAINING, Math.max(0, boots))
                .putInt(KEY_TOTAL, Math.max(0, boots))
                .putString(KEY_ARMED_BOOT_ID, bootId)
                .remove(KEY_SKIP_BOOT_ID)
                .commit();
        PersistentLog.w(TAG, boots > 0
                ? "[teste-boot] armado: pular o servico nos proximos " + boots
                        + " boots (armado no boot_id " + shortId(bootId) + ")"
                : "[teste-boot] cancelado");
    }

    /** Boots que ainda vao ser pulados. */
    public static int remaining(Context ctx) {
        return prefs(ctx).getInt(KEY_REMAINING, 0);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * boot_id do kernel. Se a ROM nao deixar ler, cai no instante do boot em relogio de
     * parede arredondado a 10 min — sobrevive a um reinicio do framework, que nao zera o
     * elapsedRealtime; so erra se o relogio pular mais que isso entre os dois.
     */
    private static String currentBootId() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String line = r.readLine();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Exception ignored) {
        }
        long bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return "wall-" + (bootWallMs / (10 * 60 * 1000L));
    }

    private static String shortId(String bootId) {
        return bootId.length() > 8 ? bootId.substring(0, 8) : bootId;
    }
}
