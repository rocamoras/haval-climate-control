package br.com.redesurftank.havalclimatecontrol.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import br.com.redesurftank.havalclimatecontrol.CarProps
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder
import br.com.redesurftank.havalclimatecontrol.MainActivity
import br.com.redesurftank.havalclimatecontrol.R
import br.com.redesurftank.havalclimatecontrol.ui.OemAccent
import br.com.redesurftank.havalclimatecontrol.ui.OemIcon
import br.com.redesurftank.havalclimatecontrol.ui.OemInk
import br.com.redesurftank.havalclimatecontrol.ui.OemInk2
import br.com.redesurftank.havalclimatecontrol.ui.intOf
import br.com.redesurftank.havalclimatecontrol.ui.oemTemp
import br.com.redesurftank.havalclimatecontrol.ui.tempCmd
import br.com.redesurftank.havalclimatecontrol.ui.tempOf
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog

/**
 * Faixa de clima flutuante no rodapé da tela, por cima de qualquer app (inclusive Android
 * Auto e CarPlay). Fica escondida; um deslize de baixo para cima a partir da borda inferior
 * a traz, e ela some sozinha depois de alguns segundos sem toque.
 *
 * Duas janelas TYPE_APPLICATION_OVERLAY:
 *  - a ALÇA, uma tira fina e invisível colada na borda de baixo, que só existe para pegar o
 *    gesto. Ela fica sempre por cima do app da frente, então os [EDGE_HEIGHT_PX] de baixo
 *    deixam de receber toque — é o preço do gesto.
 *  - a FAIXA, que fica adicionada o tempo todo e entra/sai por translationY. Escondida, ela
 *    ganha FLAG_NOT_TOUCHABLE e não desenha nada. Trocar uma flag sai mais barato que
 *    recriar a composição a cada gesto.
 *
 * A 1.27.0 reservava o rodapé com `wm overscan` e a faixa ficava ao lado do conteúdo. Com a
 * faixa escondida quase sempre, a reserva viraria uma tarja vazia, então ela passou a
 * flutuar por cima — o OverscanUtils agora só limpa o que aquela versão deixou.
 *
 * Toda chamada pública é na main thread.
 */
class ClimateStripOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ClimateStrip"

        /** Altura da faixa, em px (dp == px nesta central). */
        const val STRIP_HEIGHT_PX = 80

        /** Altura da alça do gesto. Fina para roubar o mínimo de toque do app da frente. */
        private const val EDGE_HEIGHT_PX = 16
        /** Quanto o dedo precisa subir, a partir da alça, para a faixa abrir. */
        private const val SWIPE_TRIGGER_PX = 40f

        private const val AUTO_HIDE_MS = 5_000L
        private const val ANIM_MS = 180L
        /** Logo depois de abrir, um toque "fora" ainda e o fim do proprio gesto de abrir. */
        private const val OUTSIDE_GRACE_MS = 600L

        // Tentativas de encostar as janelas no rodapé físico; ver pinToBottom().
        private const val PIN_ATTEMPTS = 3
        private const val PIN_DELAY_MS = 400L

        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { conceal("sem toque por ${AUTO_HIDE_MS / 1000}s") }

    private var edge: View? = null
    private var edgeParams: WindowManager.LayoutParams? = null
    private var bar: FrameLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null

    private var revealed = false
    private var revealedAtMs = 0L

    val isInstalled: Boolean get() = bar != null

    /** Liga o recurso: adiciona a alça e a faixa, e mostra a faixa uma vez como confirmação. */
    fun install() {
        if (bar != null) return

        val e = View(context).apply { setOnTouchListener(EdgeSwipe()) }
        val eLp = overlayParams(EDGE_HEIGHT_PX, BASE_FLAGS).apply { title = "ClimateStripEdge" }

        val o = OverlayOwner().also { it.start() }
        val compose = ComposeView(context).apply {
            setContent { ClimateStrip(onHome = ::goHome, onOpenApp = ::openApp) }
        }
        // Os owners vão na RAIZ da janela, não na ComposeView: o recomposer da janela é
        // criado a partir do rootView e procura o lifecycle dali para cima. Na ComposeView
        // filha ele não acha, e o processo inteiro cai no primeiro desenho da faixa.
        val b = BarFrame(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(compose, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            translationY = STRIP_HEIGHT_PX.toFloat()
            alpha = 0f
        }
        val bLp = overlayParams(
            STRIP_HEIGHT_PX,
            BASE_FLAGS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply { title = "ClimateStrip" }

        try {
            // Alça primeiro: a faixa, adicionada depois, fica por cima dela.
            wm.addView(e, eLp)
            wm.addView(b, bLp)
        } catch (ex: Exception) {
            // Sem SYSTEM_ALERT_WINDOW cai aqui (BadTokenException / SecurityException).
            PersistentLog.e(TAG, "falha adicionando a faixa: $ex")
            runCatching { wm.removeViewImmediate(e) }
            o.stop()
            return
        }
        edge = e; edgeParams = eLp
        bar = b; barParams = bLp
        owner = o
        PersistentLog.w(TAG, "faixa instalada")
        handler.postDelayed({ pinToBottom(e, eLp, "alca", 1) }, PIN_DELAY_MS)
        handler.postDelayed({ pinToBottom(b, bLp, "faixa", 1) }, PIN_DELAY_MS)
        reveal()
    }

    /** Desliga o recurso: remove as duas janelas. */
    fun uninstall() {
        handler.removeCallbacksAndMessages(null)
        for (v in listOfNotNull(bar, edge)) {
            try {
                wm.removeViewImmediate(v)
            } catch (ex: Exception) {
                PersistentLog.e(TAG, "falha removendo janela da faixa: $ex")
            }
        }
        owner?.stop()
        edge = null; edgeParams = null
        bar = null; barParams = null
        owner = null
        revealed = false
        PersistentLog.w(TAG, "faixa desinstalada")
    }

    private fun overlayParams(height: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 0
    }

    private fun reveal() {
        val b = bar ?: return
        val lp = barParams ?: return
        scheduleAutoHide()
        if (revealed) return
        revealed = true
        revealedAtMs = SystemClock.uptimeMillis()
        setTouchable(b, lp, true)
        b.animate().cancel()
        b.animate().translationY(0f).alpha(1f).setDuration(ANIM_MS).start()
    }

    private fun conceal(reason: String) {
        handler.removeCallbacks(autoHide)
        val b = bar ?: return
        val lp = barParams ?: return
        if (!revealed) return
        revealed = false
        // Intocável já, antes da animação: o toque seguinte tem que ir para o app de baixo.
        setTouchable(b, lp, false)
        b.animate().cancel()
        b.animate().translationY(STRIP_HEIGHT_PX.toFloat()).alpha(0f).setDuration(ANIM_MS).start()
        // Logcat e nao PersistentLog: some a cada uso, e encheria o log de diagnostico.
        Log.d(TAG, "faixa escondida: $reason")
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun setTouchable(v: View, lp: WindowManager.LayoutParams, touchable: Boolean) {
        val want = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (want == lp.flags) return
        lp.flags = want
        try {
            wm.updateViewLayout(v, lp)
        } catch (ex: Exception) {
            PersistentLog.e(TAG, "falha trocando o toque da faixa: $ex")
        }
    }

    /**
     * Mede onde a janela foi parar e a encosta no rodapé físico.
     *
     * O frame-pai de um overlay depende de flags do PhoneWindowManager que variam por
     * build; em vez de apostar, medimos: se a borda de baixo não coincide com a altura
     * real da tela, desloca y pela diferença. O log é o que diz, em campo, qual foi o caso.
     * A faixa escondida está transladada, mas getLocationOnScreen inclui a translação —
     * por isso a medida usa a posição da janela, tirando o translationY da view.
     */
    private fun pinToBottom(v: View, lp: WindowManager.LayoutParams, name: String, attempt: Int) {
        if (edge == null) return
        if (!v.isAttachedToWindow || v.height == 0) {
            if (attempt < PIN_ATTEMPTS) handler.postDelayed({ pinToBottom(v, lp, name, attempt + 1) }, PIN_DELAY_MS)
            return
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val top = loc[1] - v.translationY.toInt()
        val realH = DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }.heightPixels
        val diff = realH - (top + v.height)
        PersistentLog.w(TAG, "posicao $name: top=$top h=${v.height} tela=$realH y=${lp.y} " +
                "diff=$diff (tentativa $attempt)")
        if (diff == 0 || attempt >= PIN_ATTEMPTS) return
        // Gravidade BOTTOM: y positivo sobe. Borda acima do rodapé (diff > 0) => desce.
        lp.y -= diff
        try {
            wm.updateViewLayout(v, lp)
        } catch (ex: Exception) {
            PersistentLog.e(TAG, "falha reposicionando $name: $ex")
            return
        }
        handler.postDelayed({ pinToBottom(v, lp, name, attempt + 1) }, PIN_DELAY_MS)
    }

    /** Volta para a tela inicial da central — tira o Android Auto/CarPlay da frente. */
    private fun goHome() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { PersistentLog.e(TAG, "falha indo para a Home pela faixa: $it") }
        conceal("botao home")
    }

    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { PersistentLog.e(TAG, "falha abrindo o app pela faixa: $it") }
        conceal("abriu o app")
    }

    /** Deslize de baixo para cima a partir da alça. O gesto inteiro fica com a janela que
     *  recebeu o DOWN, então o dedo pode subir bem além dos 16 px dela. */
    private inner class EdgeSwipe : View.OnTouchListener {
        private var downY = 0f
        private var fired = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && downY - e.rawY > SWIPE_TRIGGER_PX) {
                    fired = true
                    reveal()
                }
            }
            return true
        }
    }

    /** Raiz da faixa: qualquer toque nela adia o auto-ocultar; toque fora a esconde. */
    private inner class BarFrame(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (SystemClock.uptimeMillis() - revealedAtMs > OUTSIDE_GRACE_MS) conceal("toque fora")
                return true
            }
            scheduleAutoHide()
            return super.dispatchTouchEvent(ev)
        }
    }

    /** Lifecycle mínimo para a ComposeView viver fora de uma Activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Conteúdo da faixa
// ─────────────────────────────────────────────────────────────

// Sem fundo chapado: um degradê que só escurece o rodapé, e cada grupo de controles numa
// pílula translúcida — é o que mantém o texto legível por cima de um mapa claro.
private val StripScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))
private val ChipBg = Color(0x8C101318)
private val ChipShape = RoundedCornerShape(40.dp)

private fun Modifier.chip() = clip(ChipShape).background(ChipBg)

@Composable
private fun ClimateStrip(onHome: () -> Unit, onOpenApp: () -> Unit) {
    val s = ClimateStateHolder
    val fanMax   = intOf(s.fanSpeedRange, 7).coerceAtLeast(1)
    val fanValue = intOf(s.fanSpeed, 0).coerceIn(0, fanMax)

    Row(
        modifier = Modifier.fillMaxSize().background(StripScrim).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).chip().clickable(onClick = onHome),
        ) {
            Icon(Icons.Rounded.Home, contentDescription = "Tela inicial", tint = OemInk, modifier = Modifier.size(38.dp))
        }

        // Com SYNC ligado o carro espelha o outro lado sozinho, como no OEM.
        TempStepper("MOTORISTA", s.driverTemp) { v ->
            s.sendCommand(CarProps.DRIVER_TEMP, tempCmd(v))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.chip().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OemIcon(R.drawable.hvac_power_off, s.powerMode == "1", size = 56.dp, contentDescription = "Ligar/desligar") {
                s.sendCommand(CarProps.POWER_MODE, if (s.powerMode == "1") "0" else "1")
            }
            OemIcon(R.drawable.hvac_auto_off, s.autoEnable == "1", size = 56.dp, contentDescription = "AUTO") {
                s.sendCommand(CarProps.AUTO_ENABLE, if (s.autoEnable == "1") "0" else "1")
            }
            OemIcon(R.drawable.hvac_ac_off, s.acEnable == "1", size = 56.dp, contentDescription = "A/C") {
                s.sendCommand(CarProps.AC_ENABLE, if (s.acEnable == "1") "0" else "1")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", enabled = fanValue > 1) {
                s.sendCommand(CarProps.FAN_SPEED, (fanValue - 1).coerceIn(1, fanMax).toString())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                Text("$fanValue/$fanMax", fontSize = 30.sp, fontWeight = FontWeight.Light, color = OemInk)
                Caption(if (s.turboActive) "TURBO" else "VENTILADOR", if (s.turboActive) OemAccent else OemInk2)
            }
            StepButton("+", enabled = fanValue < fanMax) {
                s.sendCommand(CarProps.FAN_SPEED, (fanValue + 1).coerceIn(1, fanMax).toString())
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.chip().clickable(onClick = onOpenApp).padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Text(
                "Cabine ${deg(s.insideTemp)}   Ext ${deg(s.outsideTemp)}",
                fontSize = 22.sp, fontWeight = FontWeight.Light, color = OemInk,
            )
            Caption("TOQUE PARA ABRIR", OemInk2)
        }

        TempStepper("PASSAGEIRO", s.passengerTemp) { v ->
            s.sendCommand(CarProps.PASS_TEMP, tempCmd(v))
        }
    }
}

/** Mesmos limites e passo do OemTempScroll da tela principal. */
@Composable
private fun TempStepper(caption: String, raw: String, onChange: (Float) -> Unit) {
    val value = tempOf(raw, 22f)
    val known = raw.replace(',', '.').toFloatOrNull() != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("−", enabled = known && value > 16f) { onChange((value - 0.5f).coerceIn(16f, 32f)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
            Text(
                if (known) oemTemp(value) + "°" else "--",
                fontSize = 34.sp, fontWeight = FontWeight.Light, color = OemInk, textAlign = TextAlign.Center,
            )
            Caption(caption, OemInk2)
        }
        StepButton("+", enabled = known && value < 32f) { onChange((value + 0.5f).coerceIn(16f, 32f)) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .chip()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(label, fontSize = 34.sp, color = if (enabled) OemInk else OemInk2)
    }
}

@Composable
private fun Caption(text: String, color: Color) =
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp, color = color)

private fun deg(v: String) = v.replace(',', '.').toFloatOrNull()?.let { oemTemp(it) + "°" } ?: "--"
