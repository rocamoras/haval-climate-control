package br.com.redesurftank.havalclimatecontrol.utils;

import android.util.Log;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Leitura/escrita de system properties (`getprop` / `setprop`).
 *
 * As `persist.vendor.gwm.cfg.*` são flags de configuração de variante gravadas pela
 * fábrica. Os apps OEM as leem UMA VEZ na inicialização (ex.:
 * HVACSystemPropertiesUtil.hasAutoDemist() é chamado no init do ViewModel), então
 * alterar uma delas só surte efeito depois de reiniciar o app que a consome.
 *
 * Leitura tenta primeiro o `android.os.SystemProperties` por reflection (não precisa
 * de Shizuku); se a hidden-API policy bloquear, cai para `getprop` via Shizuku.
 * Escrita exige Shizuku — `setprop` de `persist.*` precisa de contexto privilegiado.
 */
public final class SystemPropsUtils {

    private static final String TAG = "SystemPropsUtils";

    private static Method sysPropGet;

    static {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sysPropGet = sp.getMethod("get", String.class, String.class);
        } catch (Throwable t) {
            Log.w(TAG, "android.os.SystemProperties.get indisponível: " + t.getMessage());
        }
    }

    private SystemPropsUtils() {}

    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Valor da property, ou "" se vazia/ilegível. */
    public static String get(String key) {
        if (sysPropGet != null) {
            try {
                String v = (String) sysPropGet.invoke(null, key, "");
                if (v != null && !v.isEmpty()) return v.trim();
            } catch (Throwable t) {
                Log.w(TAG, "reflection get falhou para " + key + ": " + t.getMessage());
            }
        }
        if (!isShizukuReady()) return "";
        try {
            return ShizukuUtils.runCommandAndGetOutput(new String[]{"getprop", key}).trim();
        } catch (Throwable t) {
            Log.e(TAG, "getprop falhou para " + key, t);
            return "";
        }
    }

    /**
     * Grava a property e confere lendo de volta.
     *
     * O `setprop` é silencioso quando o SELinux nega — daí o read-back ser a única
     * forma confiável de saber se pegou. Como a leitura por reflection pode vir de
     * um cache do processo, o read-back usa `getprop` direto.
     *
     * @return null em caso de sucesso, ou a mensagem de erro.
     */
    public static String set(String key, String value) {
        if (!isShizukuReady()) return "Shizuku indisponível — não é possível gravar system property";
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"setprop", key, value});
            String readBack = ShizukuUtils.runCommandAndGetOutput(new String[]{"getprop", key}).trim();
            if (value.equals(readBack)) {
                Log.w(TAG, "setprop " + key + " = " + value + " OK");
                return null;
            }
            return "setprop não pegou (leu de volta \"" + readBack + "\", esperado \"" + value + "\")";
        } catch (Throwable t) {
            Log.e(TAG, "setprop falhou para " + key, t);
            return "Erro: " + t.getMessage();
        }
    }

    /**
     * Reinicia um pacote para que ele releia as properties de configuração.
     * force-stop basta: os apps OEM são religados pelo launcher/AMS na próxima abertura.
     */
    public static String restartPackage(String pkg) {
        if (!isShizukuReady()) return "Shizuku indisponível";
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"am", "force-stop", pkg});
            Log.w(TAG, "force-stop " + pkg);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "force-stop falhou para " + pkg, t);
            return "Erro: " + t.getMessage();
        }
    }
}
