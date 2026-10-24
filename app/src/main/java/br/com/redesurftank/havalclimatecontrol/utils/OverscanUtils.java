package br.com.redesurftank.havalclimatecontrol.utils;

import android.content.Context;
import android.content.SharedPreferences;

import br.com.redesurftank.havalclimatecontrol.App;

/**
 * Desfaz a reserva de rodapé (`wm overscan`) que a 1.27.0 deixou para a faixa de clima.
 *
 * Naquela versão a faixa ficava fixa e o overscan empurrava todo app para cima dela. A
 * faixa passou a flutuar por cima, escondida até o gesto, e a reserva virou só uma tarja
 * vazia — mas o WindowManager a PERSISTE (/data/system/display_settings.xml) e ela
 * sobrevive ao reboot. Por isso a limpeza continua aqui.
 *
 * Só desfaz o que está marcado como nosso em climate_control_prefs (device-protected,
 * legível no boot travado): um overscan de terceiro, como o do AutoPanel, não é tocado.
 */
public final class OverscanUtils {

    private static final String TAG = "OverscanUtils";

    private static final String PREFS_NAME      = "climate_control_prefs";
    /** px que a 1.27.0 deixou reservados no rodapé; 0/ausente = nada nosso aplicado. */
    private static final String KEY_APPLIED_PX  = "overscan_bottom_applied_px";

    private OverscanUtils() {}

    private static SharedPreferences prefs() {
        return App.getDeviceProtectedContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Desfaz a reserva, se fomos nós que a deixamos. Roda shell: chamar fora da main. */
    public static void clearIfOurs(String motivo) {
        int applied = prefs().getInt(KEY_APPLIED_PX, 0);
        if (applied <= 0) return;
        ShizukuUtils.ShellResult r = ShizukuUtils.run(new String[]{"wm", "overscan", "reset"});
        if (r.ok()) {
            prefs().edit().remove(KEY_APPLIED_PX).apply();
            PersistentLog.w(TAG, "overscan limpo (" + motivo + "), era " + applied + "px");
        } else {
            // Marca fica: o próximo boot tenta de novo.
            PersistentLog.e(TAG, "falha limpando overscan (" + motivo + "): " + r.describeFailure());
        }
    }
}
