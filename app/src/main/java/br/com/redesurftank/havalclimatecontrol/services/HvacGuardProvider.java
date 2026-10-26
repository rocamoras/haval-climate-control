package br.com.redesurftank.havalclimatecontrol.services;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder;
import br.com.redesurftank.havalclimatecontrol.utils.DebloatUtils;
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog;

/**
 * Responde ao hook do SystemUI (res/raw/com_android_systemui.js) se o popup do app de
 * clima do OEM que ele esta prestes a abrir e consequencia nossa.
 *
 * O SystemUI abre o com.beantechs.hvac quando o modulo do HVAC publica
 * `car.hvac.panel_display_notify = 2`, o que acontece depois de qualquer escrita —
 * inclusive as do app. A defesa antiga era `pm disable-user` no pacote; agora o hook
 * pergunta aqui, uma consulta por evento, e o `pm` so volta se o hook nao estiver de pe
 * (ver ClimateControlService.setHvacGuardArmed).
 *
 * Com o app de clima do OEM desativado (opcoes de performance), o provider tambem e a
 * porta para o SystemUI abrir a NOSSA faixa no lugar dele: metodo "open" para o botao de
 * A/C da barra lateral, e o proprio "suppress" para o popup que nao veio de nos.
 *
 * Sem permissao no manifest de proposito: `call()` nao passa pela readPermission no
 * Android 9, e o que sai daqui e so um "sim, barre" — nao ha dado a proteger.
 */
public class HvacGuardProvider extends ContentProvider {

    private static final String TAG = "HvacGuard";

    /**
     * Janela depois de uma escrita em que o popup ainda e nosso. Mesmo valor que a
     * supressao por `pm` usava para reabilitar o pacote (ClimateControlService).
     */
    public static final long WRITE_WINDOW_MS = 2_500;

    private static volatile long    lastWriteMs = 0;     // elapsedRealtime
    private static volatile boolean armed       = false;
    private static final AtomicLong suppressed  = new AtomicLong();

    /** Chamado antes de cada escrita no veiculo. */
    public static void markWrite() {
        lastWriteMs = SystemClock.elapsedRealtime();
    }

    /** Espelho do estado do servico, so para o upload de diagnostico. */
    public static void setArmed(boolean value) {
        armed = value;
    }

    public static String describe() {
        return (armed ? "armada" : "desarmada (pm disable)") + ", popups barrados: " + suppressed.get();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("open".equals(method)) return open();
        if (!"suppress".equals(method)) return null;
        String reason = ourReason();
        // App de clima do OEM desativado nas opcoes de performance: barra TODOS os popups,
        // inclusive os de botao fisico. Deixar passar so faz o startApp do SystemUI falhar
        // num pacote desativado — uma exception no thread principal dele por evento. O que
        // nao for consequencia nossa (botao fisico, +/- da barra lateral) abre a faixa no
        // lugar, como retorno visual do que mudou.
        // Sem PersistentLog aqui: seria uma linha a cada mudanca de clima.
        if (oemHvacDisabled()) {
            suppressed.incrementAndGet();
            if (reason == null) requestOurClimate(false);
            Bundle b = new Bundle();
            b.putString("reason", "app de clima do OEM desativado");
            return b;
        }
        Bundle b = new Bundle();
        if (reason != null) {
            suppressed.incrementAndGet();
            PersistentLog.w(TAG, "popup do HVAC do OEM barrado — " + reason);
            b.putString("reason", reason);
        }
        return b;
    }

    /** Popup consequencia nossa: tela do app na frente ou escrita recente. null se nao. */
    private static String ourReason() {
        if (ClimateStateHolder.INSTANCE.getUiVisible()) return "tela do app na frente";
        long last = lastWriteMs;
        long dt = SystemClock.elapsedRealtime() - last;
        if (last > 0 && dt < WRITE_WINDOW_MS) return "escrita do app ha " + dt + "ms";
        return null;
    }

    /**
     * Botao de A/C da barra lateral (AppUtils.startHVACApp no SystemUI). Com o app de clima
     * do OEM desativado, a resposta "handled" faz o hook pular o original e abrimos o nosso.
     * Fora disso (ou sem o servico de pe) o SystemUI segue o caminho dele.
     */
    private Bundle open() {
        Bundle b = new Bundle();
        if (oemHvacDisabled() && requestOurClimate(true)) b.putBoolean("handled", true);
        return b;
    }

    private boolean oemHvacDisabled() {
        Context ctx = getContext();
        return ctx != null && DebloatUtils.isWanted(ctx, DebloatUtils.Target.HVAC);
    }

    private static boolean requestOurClimate(boolean explicit) {
        ClimateStateHolder.OemClimateRequest cb = ClimateStateHolder.INSTANCE.getOnOemClimateRequest();
        if (cb == null) return false;
        cb.onRequest(explicit);
        return true;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
