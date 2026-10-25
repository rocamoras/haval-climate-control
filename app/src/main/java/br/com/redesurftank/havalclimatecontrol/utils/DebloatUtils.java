package br.com.redesurftank.havalclimatecontrol.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

import br.com.redesurftank.havalclimatecontrol.App;

/**
 * Opções de performance: desativa apps do OEM que ficam rodando e consomem RAM/CPU da
 * central. Mesmas quatro do Impulse (navegação Neusoft, voz iFlyTek, previsão do tempo e
 * telemetria DataTrack).
 *
 * Diferenças de propósito em relação ao Impulse:
 * - Sempre `pm disable-user` + `am force-stop`, nunca `pm uninstall --user 0`. Reverte com
 *   `pm enable` e não briga com o Impulse: com o toggle dele desligado ele roda
 *   `pm install-existing` a cada boot, que reinstala um pacote removido mas não reabilita um
 *   desabilitado.
 * - Só reabilitamos no boot o que NÓS desabilitamos (flag "_owned"). Com o toggle desligado
 *   o app fica inerte e não desfaz o que outro app tenha feito; quem reabilita na marra é
 *   só o toque explícito do usuário em "Desl.".
 *
 * Prefs em climate_control_prefs (device-protected): o serviço aplica no boot, possivelmente
 * antes do unlock. Tudo aqui roda shell — chamar fora da main.
 */
public final class DebloatUtils {

    private static final String TAG = "DebloatUtils";

    private static final String PREFS_NAME = "climate_control_prefs";

    public enum Target {
        NAVIGATION("debloat_native_navigation", "com.neusoft.na.navigation"),
        VOICE("debloat_native_voice",
                "com.iflytek.cutefly.speechclient.hmi", "com.beantechs.voiceclient"),
        // A previsão só alimenta a temperatura da barra; versões anteriores a deixavam sempre
        // desativada, e o seed pelo estado real mantém isso para quem atualiza.
        WEATHER("debloat_native_weather", "com.beantechs.weatherservice"),
        DATATRACK("debloat_datatrack", "com.beantechs.datatrackservice");

        final String   key;
        /** O primeiro é o representante: é por ele que a verificação decide. */
        final String[] packages;

        Target(String key, String... packages) {
            this.key      = key;
            this.packages = packages;
        }
    }

    private DebloatUtils() {}

    private static SharedPreferences prefs() {
        return App.getDeviceProtectedContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** O usuário quer o pacote desativado. Ausente = ainda não semeado (vira false na UI). */
    public static boolean isWanted(Target t) {
        return prefs().getBoolean(t.key, false);
    }

    /** commit, não apply: a central costuma ser desligada logo depois de mexer na tela. */
    public static void setWanted(Target t, boolean wanted) {
        prefs().edit().putBoolean(t.key, wanted).commit();
    }

    /**
     * Boot do serviço: semeia as prefs que ainda não existem pelo estado real (pacote já
     * desativado por fora → toggle nasce ligado) e aplica o que estiver divergente. Um shell
     * só no caso comum, em que nada mudou.
     *
     * {@code weatherForcedOff}: temperatura externa real ligada. O app de previsão escreve na
     * mesma TextView que o hook do SystemUI, então ele fica desativado de qualquer jeito.
     */
    public static void reconcileAll(boolean weatherForcedOff) {
        Set<String> enabled = listEnabled();
        if (enabled == null) return;
        SharedPreferences p = prefs();
        for (Target t : Target.values()) {
            if (!p.contains(t.key)) {
                boolean disabledNow = !enabled.contains(t.packages[0]);
                p.edit().putBoolean(t.key, disabledNow).commit();
                PersistentLog.w(TAG, t.name() + ": pref semeada pelo estado real = " + disabledNow);
            }
            apply(t, weatherForcedOff && t == Target.WEATHER, false, enabled);
        }
    }

    /** Aplica um alvo. {@code explicit}: veio do toque do usuário, então reabilita mesmo o
     *  que não fomos nós que desativamos. */
    public static void reconcile(Target t, boolean forcedOff, boolean explicit) {
        Set<String> enabled = listEnabled();
        if (enabled == null) return;
        apply(t, forcedOff, explicit, enabled);
    }

    private static void apply(Target t, boolean forcedOff, boolean explicit, Set<String> enabled) {
        boolean wanted = isWanted(t) || forcedOff;
        String ownedKey = t.key + "_owned";
        boolean owned = prefs().getBoolean(ownedKey, false);

        if (wanted) {
            boolean anyRunning = false;
            for (String pkg : t.packages) anyRunning |= enabled.contains(pkg);
            // Já desativado (por nós ou por fora): sem shell. Não toma posse do que outro
            // app desativou — senão desligar a temp real reabilitaria a previsão dele.
            if (!anyRunning) return;
            StringBuilder sh = new StringBuilder();
            for (String pkg : t.packages) {
                // Pacote ausente nesta variante da central: o pm falha e segue para o próximo.
                sh.append("pm disable-user --user 0 ").append(pkg).append(" >/dev/null 2>&1 && ")
                  .append("am force-stop ").append(pkg).append("; ");
            }
            runAndVerify(t, sh, false, ownedKey, true);
        } else {
            if (!owned && !explicit) return;
            if (enabled.contains(t.packages[0])) {
                if (owned) prefs().edit().remove(ownedKey).commit();
                return;
            }
            StringBuilder sh = new StringBuilder();
            for (String pkg : t.packages) {
                // install-existing só se outro app (Impulse) tiver feito uninstall --user 0.
                sh.append("pm list packages | grep -qx 'package:").append(pkg).append("' || ")
                  .append("pm install-existing ").append(pkg).append(" >/dev/null 2>&1; ")
                  .append("pm enable ").append(pkg).append(" >/dev/null 2>&1; ");
            }
            runAndVerify(t, sh, true, ownedKey, false);
        }
    }

    /** Roda o script e confere em `pm list packages -e` no mesmo shell. */
    private static void runAndVerify(Target t, StringBuilder sh, boolean expectEnabled,
                                     String ownedKey, boolean ownedAfter) {
        sh.append("pm list packages -e");
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"sh", "-c", sh.toString()});
        if (!r.ran()) {
            PersistentLog.e(TAG, t.name() + ": " + r.describeFailure());
            return;
        }
        boolean isEnabled = parse(r.stdout).contains(t.packages[0]);
        if (isEnabled == expectEnabled) {
            SharedPreferences.Editor e = prefs().edit();
            if (ownedAfter) e.putBoolean(ownedKey, true); else e.remove(ownedKey);
            e.commit();
            PersistentLog.w(TAG, t.name() + " -> " + (expectEnabled ? "reativado" : "desativado"));
        } else {
            // Flag fica como estava: o próximo boot tenta de novo.
            PersistentLog.e(TAG, t.name() + ": estado nao confirmado (" + r.describeFailure() + ")");
        }
    }

    /** Pacotes habilitados agora, ou null se o Shizuku não respondeu. */
    private static Set<String> listEnabled() {
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"pm", "list", "packages", "-e"});
        if (!r.ok() || r.stdout.isEmpty()) {
            PersistentLog.e(TAG, "pm list packages -e falhou: " + r.describeFailure());
            return null;
        }
        return parse(r.stdout);
    }

    private static Set<String> parse(String out) {
        Set<String> s = new HashSet<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) s.add(line.substring("package:".length()));
        }
        return s;
    }
}
