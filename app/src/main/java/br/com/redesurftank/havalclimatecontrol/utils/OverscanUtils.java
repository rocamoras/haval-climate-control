package br.com.redesurftank.havalclimatecontrol.utils;

import android.content.Context;
import android.content.SharedPreferences;

import br.com.redesurftank.havalclimatecontrol.App;

/**
 * Reserva uma faixa no rodapé do display 0 com `wm overscan`, para a faixa de clima.
 *
 * Mesma técnica do AutoPanel (leonardovin/shizuku-bottom-bar): com o overscan, todo app —
 * Home, Android Auto, CarPlay — passa a ser diagramado acima da faixa, e o nosso overlay
 * ocupa o que sobrou. O Android Auto NÃO se redesenha: a TS dimensiona a SurfaceView pela
 * resolução física, então o rodapé do vídeo é cortado, não encolhido.
 *
 * O overscan é PERSISTIDO pelo WindowManager (/data/system/display_settings.xml) e
 * sobrevive ao reboot. Se o app parar de rodar com a faixa reservada, sobra uma tarja
 * preta no rodapé de todas as telas. Por isso o valor que aplicamos vai para
 * climate_control_prefs (device-protected, legível no boot travado): no boot, quem não
 * quer mais a faixa limpa só o que nós deixamos — um overscan de terceiro (o próprio
 * AutoPanel, por exemplo) não é tocado.
 *
 * `wm overscan` existe até o Android 10; a central roda Android 9. Shell do Shizuku
 * basta (WRITE_SECURE_SETTINGS), não precisa de root.
 */
public final class OverscanUtils {

    private static final String TAG = "OverscanUtils";

    private static final String PREFS_NAME      = "climate_control_prefs";
    /** px que nós deixamos reservados no rodapé; 0/ausente = nada nosso aplicado. */
    private static final String KEY_APPLIED_PX  = "overscan_bottom_applied_px";

    private OverscanUtils() {}

    private static SharedPreferences prefs() {
        return App.getDeviceProtectedContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Reserva {@code bottomPx} no rodapé do display 0. Roda shell: chamar fora da main. */
    public static boolean applyBottom(int bottomPx) {
        // Ja e nosso e do mesmo tamanho: o valor persiste no reboot, e reaplicar a cada
        // restart do servico faria todo app em tela se rediagramar sem motivo.
        if (prefs().getInt(KEY_APPLIED_PX, 0) == bottomPx) return true;
        ShizukuUtils.ShellResult r = ShizukuUtils.run(
                new String[]{"wm", "overscan", "0,0,0," + bottomPx});
        if (r.ok()) {
            prefs().edit().putInt(KEY_APPLIED_PX, bottomPx).apply();
            PersistentLog.w(TAG, "overscan aplicado: rodape " + bottomPx + "px");
        } else {
            PersistentLog.e(TAG, "falha aplicando overscan " + bottomPx + "px: " + r.describeFailure());
        }
        return r.ok();
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
