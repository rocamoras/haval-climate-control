package br.com.redesurftank.havalclimatecontrol.services

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import br.com.redesurftank.havalclimatecontrol.ui.OemInk3
import br.com.redesurftank.havalclimatecontrol.ui.OemTrack
import br.com.redesurftank.havalclimatecontrol.ui.intOf
import br.com.redesurftank.havalclimatecontrol.ui.oemTemp
import br.com.redesurftank.havalclimatecontrol.ui.tempCmd
import br.com.redesurftank.havalclimatecontrol.ui.tempOf
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog

/**
 * Faixa de clima fixa no rodapé da tela, visível por cima de qualquer app (inclusive
 * Android Auto e CarPlay).
 *
 * A janela é um TYPE_APPLICATION_OVERLAY com LAYOUT_NO_LIMITS, para poder cair dentro da
 * faixa que o OverscanUtils reserva — sem o overscan ela ficaria por cima do conteúdo
 * dos apps em vez de ao lado dele. Flags copiadas do AutoPanel, que roda nesta central.
 *
 * Toda chamada pública é na main thread.
 */
class ClimateStripOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ClimateStrip"

        /** Altura da faixa, em px (dp == px nesta central). É também o overscan reservado. */
        const val STRIP_HEIGHT_PX = 80

        // Tentativas de encostar a janela no rodapé físico; ver pinToBottom().
        private const val PIN_ATTEMPTS = 3
        private const val PIN_DELAY_MS = 400L
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            STRIP_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Palpite inicial: a gravidade é aplicada dentro do frame que o overscan já
            // encolheu, então a janela nasceria logo ACIMA da faixa. y negativo empurra para
            // baixo; pinToBottom() corrige se o WindowManager desta central medir diferente.
            y = -STRIP_HEIGHT_PX
            title = "ClimateStrip"
        }

        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { ClimateStrip(onOpenApp = ::openApp) }
        }

        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            // Sem SYSTEM_ALERT_WINDOW cai aqui (BadTokenException / SecurityException).
            PersistentLog.e(TAG, "falha adicionando a faixa: $e")
            o.stop()
            return
        }
        view = v
        owner = o
        params = lp
        PersistentLog.w(TAG, "faixa adicionada")
        handler.postDelayed({ pinToBottom(1) }, PIN_DELAY_MS)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {
            PersistentLog.e(TAG, "falha removendo a faixa: $e")
        }
        owner?.stop()
        view = null
        owner = null
        params = null
        PersistentLog.w(TAG, "faixa removida")
    }

    /**
     * Mede onde a janela foi parar e a encosta no rodapé físico.
     *
     * O frame-pai de um overlay depende de flags do PhoneWindowManager que variam por
     * build; em vez de apostar, medimos: se a borda de baixo não coincide com a altura
     * real da tela, desloca y pela diferença. O log é o que diz, em campo, qual foi o caso.
     */
    private fun pinToBottom(attempt: Int) {
        val v = view ?: return
        val lp = params ?: return
        if (!v.isAttachedToWindow || v.height == 0) {
            if (attempt < PIN_ATTEMPTS) handler.postDelayed({ pinToBottom(attempt + 1) }, PIN_DELAY_MS)
            return
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val realH = DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }.heightPixels
        val diff = realH - (loc[1] + v.height)
        PersistentLog.w(TAG, "posicao: top=${loc[1]} h=${v.height} tela=$realH y=${lp.y} " +
                "diff=$diff (tentativa $attempt)")
        if (diff == 0 || attempt >= PIN_ATTEMPTS) return
        // Gravidade BOTTOM: y positivo sobe. Borda acima do rodapé (diff > 0) => desce.
        lp.y -= diff
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
            PersistentLog.e(TAG, "falha reposicionando a faixa: $e")
            return
        }
        handler.postDelayed({ pinToBottom(attempt + 1) }, PIN_DELAY_MS)
    }

    private fun openApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { PersistentLog.e(TAG, "falha abrindo o app pela faixa: $it") }
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

private val StripBg = Color(0xF20B0D12)

@Composable
private fun ClimateStrip(onOpenApp: () -> Unit) {
    val s = ClimateStateHolder
    val fanMax   = intOf(s.fanSpeedRange, 7).coerceAtLeast(1)
    val fanValue = intOf(s.fanSpeed, 0).coerceIn(0, fanMax)
    val syncOn   = s.syncEnable == "1"

    Row(
        modifier = Modifier.fillMaxSize().background(StripBg).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TempStepper("MOTORISTA", s.driverTemp) { v ->
            s.sendCommand(CarProps.DRIVER_TEMP, tempCmd(v))
            if (syncOn) s.sendCommand(CarProps.PASS_TEMP, tempCmd(v))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepButton("−", enabled = fanValue > 1) {
                s.sendCommand(CarProps.FAN_SPEED, (fanValue - 1).coerceIn(1, fanMax).toString())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                Text("$fanValue/$fanMax", fontSize = 30.sp, fontWeight = FontWeight.Light, color = OemInk)
                Caption(if (s.turboActive) "TURBO" else "VENTILADOR", if (s.turboActive) OemAccent else OemInk3)
            }
            StepButton("+", enabled = fanValue < fanMax) {
                s.sendCommand(CarProps.FAN_SPEED, (fanValue + 1).coerceIn(1, fanMax).toString())
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenApp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                "Cabine ${deg(s.insideTemp)}   Ext ${deg(s.outsideTemp)}",
                fontSize = 22.sp, fontWeight = FontWeight.Light, color = OemInk,
            )
            Caption("TOQUE PARA ABRIR", OemInk3)
        }

        TempStepper("PASSAGEIRO", s.passengerTemp) { v ->
            s.sendCommand(CarProps.PASS_TEMP, tempCmd(v))
            if (syncOn) s.sendCommand(CarProps.DRIVER_TEMP, tempCmd(v))
        }
    }
}

/** Mesmos limites e passo do OemTempScroll da tela principal. */
@Composable
private fun TempStepper(caption: String, raw: String, onChange: (Float) -> Unit) {
    val value = tempOf(raw, 22f)
    val known = raw.replace(',', '.').toFloatOrNull() != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepButton("−", enabled = known && value > 16f) { onChange((value - 0.5f).coerceIn(16f, 32f)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
            Text(
                if (known) oemTemp(value) + "°" else "--",
                fontSize = 34.sp, fontWeight = FontWeight.Light, color = OemInk, textAlign = TextAlign.Center,
            )
            Caption(caption, OemInk3)
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
            .clip(RoundedCornerShape(30.dp))
            .background(OemTrack)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(label, fontSize = 34.sp, color = if (enabled) OemInk else OemInk3)
    }
}

@Composable
private fun Caption(text: String, color: Color) =
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp, color = color)

private fun deg(v: String) = v.replace(',', '.').toFloatOrNull()?.let { oemTemp(it) + "°" } ?: "--"
