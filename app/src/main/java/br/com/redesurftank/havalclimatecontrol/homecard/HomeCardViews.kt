package br.com.redesurftank.havalclimatecontrol.homecard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Os dois cards da Home da MediaCenter, desenhados no NOSSO processo.
 *
 * Port direto do drawCard/drawEvCard de res/raw/com_beantechs_mediacenter_card.js: mesma
 * paleta (HmiTheme), mesmas coordenadas, mesmos ícones. Diferenças:
 *  - o fundo em degradê do OEM é OPACO aqui. No Frida o card vivia dentro da fileira e a
 *    translucidez (alpha 0,82) deixava o main_bg da home aparecer; no overlay o que está
 *    embaixo é o título "Música online" e o ícone do Deezer, que precisam sumir.
 *  - a altura pode passar de 300 para cobrir o ícone do OEM inteiro; o rodapé desce junto
 *    (`extra`), o miolo fica onde estava.
 */
data class ClimateSnap(
    val on: Boolean,
    val ac: Boolean,
    val auto: Boolean,
    val temp: Float?,
    val fan: Int,
    val turbo: Boolean
)

data class EvSnap(
    val conn: Boolean,
    val auto: Boolean,
    val phase: Int?,
    val soc: Int?,
    val model: Int?
)

internal object CardPalette {
    // Degradê dos cards de mídia local da MediaCenter (ver o script), com alpha FF.
    val BG_COLORS = intArrayOf(0xFF2D2F33.toInt(), 0xFF242627.toInt(), 0xFF212325.toInt(), 0xFF1E2021.toInt())
    val BG_STOPS = floatArrayOf(0f, 0.22f, 0.52f, 1f)
    val EDGE_COLORS = intArrayOf(0xFF535557.toInt(), 0xFF4B4D4F.toInt(), 0xFF3C3E40.toInt(), 0xFF2C2D31.toInt())
    val EDGE_STOPS = floatArrayOf(0f, 0.22f, 0.55f, 1f)
    const val SURFACE2 = 0xFF1C1C1C.toInt()
    const val FG = 0xFFFAFAFA.toInt()
    const val MUTED = 0xFFA3A3A3.toInt()
    const val DIM = 0xFF6B6B6B.toInt()
    const val FAINT = 0xFF404040.toInt()
    const val ACCENT = 0xFF22C55E.toInt()
    const val ACCENT_SOFT = 0x1F22C55E
    const val ACCENT_EDGE = 0x6622C55E
    const val BORDER = 0x1AFFFFFF
    const val AMBER = 0xFFF59E0B.toInt()
    const val AMBER_SOFT = 0x1FF59E0B
    const val AMBER_EDGE = 0x66F59E0B
    const val RED = 0xFFEF4444.toInt()
}

/** Base: moldura, paints reaproveitados e primitivas com a mesma assinatura do script. */
@SuppressLint("ViewConstructor")
abstract class HomeCardBaseView(ctx: Context) : View(ctx) {
    protected val pad = 32f
    protected val baseH = 300f
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Quanto o card é mais alto que o desenho original de 300px. */
    protected val extra get() = max(0f, height - baseH)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            CardPalette.BG_COLORS, CardPalette.BG_STOPS, Shader.TileMode.CLAMP)
        edgePaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            CardPalette.EDGE_COLORS, CardPalette.EDGE_STOPS, Shader.TileMode.CLAMP)
    }

    protected fun frame(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, 24f, 24f, bgPaint)
        c.drawRoundRect(rect, 24f, 24f, edgePaint)
    }

    protected fun fill(color: Int): Paint = p.apply {
        reset(); isAntiAlias = true; this.color = color; style = Paint.Style.FILL
    }

    protected fun stroke(color: Int, w: Float): Paint = p.apply {
        reset(); isAntiAlias = true; this.color = color; style = Paint.Style.STROKE
        strokeWidth = w; strokeCap = Paint.Cap.ROUND
    }

    protected fun textPaint(color: Int, size: Float, family: String, spacing: Float = 0f): Paint = p.apply {
        reset(); isAntiAlias = true; this.color = color; textSize = size
        typeface = Typeface.create(family, Typeface.NORMAL); letterSpacing = spacing
    }

    protected fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, paint: Paint) {
        rect.set(l, t, r, b); c.drawRoundRect(rect, rad, rad, paint)
    }

    protected fun arc(c: Canvas, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, paint: Paint) {
        rect.set(l, t, r, b); c.drawArc(rect, start, sweep, false, paint)
    }

    /** Selo à direita do cabeçalho: texto + bolinha. */
    protected fun badge(c: Canvas, text: String, color: Int) {
        val tp = textPaint(color, 16f, "monospace", 0.06f)
        val bw = tp.measureText(text)
        val w = width.toFloat()
        c.drawText(text, w - pad - bw, 52f, tp)
        c.drawCircle(w - pad - bw - 16f, 46f, 5f, fill(color))
    }

    /** Tile 88x88 em (PAD, 98) com fundo e borda. */
    protected fun tile(c: Canvas, soft: Int, edge: Int) {
        roundRect(c, pad, 98f, pad + 88f, 186f, 22f, fill(soft))
        roundRect(c, pad + 0.5f, 98.5f, pad + 87.5f, 185.5f, 22f, stroke(edge, 1f))
    }

    /** Número grande + unidade + rótulo, à direita do tile. */
    protected fun bigNumber(c: Canvas, value: String, unit: String?, label: String, live: Boolean) {
        val x = pad + 88f + 24f
        val np = textPaint(if (live) CardPalette.FG else CardPalette.FAINT, 76f, "sans-serif-medium")
        val nw = np.measureText(value)
        c.drawText(value, x, 170f, np)
        if (unit != null) c.drawText(unit, x + nw + 6f, 170f, textPaint(CardPalette.MUTED, 34f, "sans-serif"))
        c.drawText(label, x + 2f, 198f, textPaint(CardPalette.DIM, 15f, "sans-serif-medium", 0.2f))
    }

    protected fun polygon(c: Canvas, pts: List<Pair<Float, Float>>, paint: Paint) {
        path.reset()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        c.drawPath(path, paint)
    }
}

/** Card de clima: A/C, temperatura interna, vento e selo AUTO/MANUAL/TURBO/OFF. */
class ClimateCardView(ctx: Context) : HomeCardBaseView(ctx) {
    private val fanMax = 7
    var snap: ClimateSnap = ClimateSnap(false, false, false, null, 0, false)
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        val s = snap
        val w = width.toFloat()
        frame(c)
        val acOn = s.on && s.ac
        val autoOn = s.on && s.auto
        val modeC = if (s.turbo) CardPalette.AMBER else if (autoOn) CardPalette.ACCENT else CardPalette.FAINT

        c.drawText("CLIMA", pad, 52f, textPaint(CardPalette.DIM, 17f, "sans-serif-medium", 0.18f))
        badge(c, if (!s.on) "OFF" else if (s.turbo) "TURBO" else if (s.auto) "AUTO" else "MANUAL", modeC)

        tile(c, if (acOn) CardPalette.ACCENT_SOFT else CardPalette.SURFACE2,
            if (acOn) CardPalette.ACCENT_EDGE else CardPalette.BORDER)
        snowflake(c, pad + 44f, 142f, 21f,
            if (acOn) CardPalette.ACCENT else if (s.on) CardPalette.MUTED else CardPalette.FAINT)

        val live = s.on && s.temp != null
        bigNumber(c, if (live) s.temp!!.roundToInt().toString() else "--", if (live) "°C" else null,
            "INTERNA", s.on)

        // rodapé: vento (ícone + 7 segmentos + valor)
        val cy = 262f + extra
        wind(c, pad, cy, if (s.on) CardPalette.MUTED else CardPalette.FAINT)
        val fanStr = if (s.on) s.fan.toString() else "--"
        val fp = textPaint(if (s.on) CardPalette.FG else CardPalette.FAINT, 24f, "monospace")
        val fanW = fp.measureText(fanStr)
        c.drawText(fanStr, w - pad - fanW, cy + 9f, fp)
        val barsL = pad + 52f
        val barsR = w - pad - fanW - 16f
        val gap = 5f
        val barW = (barsR - barsL - gap * (fanMax - 1)) / fanMax
        for (i in 0 until fanMax) {
            val bx = barsL + i * (barW + gap)
            val on = s.on && i < s.fan
            roundRect(c, bx, cy - 6f, bx + barW, cy + 6f, 3f,
                fill(if (on) (if (s.turbo) CardPalette.AMBER else CardPalette.FG) else CardPalette.SURFACE2))
            roundRect(c, bx + 0.5f, cy - 5.5f, bx + barW - 0.5f, cy + 5.5f, 3f,
                stroke(if (on) (if (s.turbo) CardPalette.AMBER_EDGE else CardPalette.MUTED) else CardPalette.BORDER, 1f))
        }
    }

    private fun snowflake(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val sp = stroke(color, 2.2f)
        for (k in 0 until 6) {
            val a = Math.toRadians(90.0 + k * 60)
            c.drawLine(cx, cy, cx + r * cos(a).toFloat(), cy - r * sin(a).toFloat(), sp)
            val bx = cx + r * 0.62f * cos(a).toFloat()
            val by = cy - r * 0.62f * sin(a).toFloat()
            for (off in intArrayOf(40, -40)) {
                val ab = a + Math.toRadians(off.toDouble())
                c.drawLine(bx, by, bx + r * 0.30f * cos(ab).toFloat(), by - r * 0.30f * sin(ab).toFloat(), sp)
            }
        }
    }

    private fun wind(c: Canvas, x: Float, cy: Float, color: Int) {
        val sp = stroke(color, 2.4f)
        c.drawLine(x, cy - 9, x + 20, cy - 9, sp)
        arc(c, x + 15, cy - 14, x + 25, cy - 4, 90f, 270f, sp)
        c.drawLine(x, cy, x + 26, cy, sp)
        arc(c, x + 21, cy - 5, x + 31, cy + 5, 270f, 270f, sp)
        c.drawLine(x, cy + 9, x + 15, cy + 9, sp)
    }
}

/** Card do ciclo automático do haval-ev-manager (contrato em /data/local/tmp/haval_ev_home_card). */
class EvCardView(ctx: Context) : HomeCardBaseView(ctx) {
    var snap: EvSnap = EvSnap(false, false, null, null, null)
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onDraw(c: Canvas) {
        val e = snap
        val w = width.toFloat()
        frame(c)
        val live = e.conn && e.auto
        val phase = if (live) e.phase else null
        val charge = phase == 80
        val use = phase == 20
        val hue = if (charge) CardPalette.ACCENT else if (use) CardPalette.AMBER else CardPalette.FAINT
        val hueSoft = if (charge) CardPalette.ACCENT_SOFT else if (use) CardPalette.AMBER_SOFT else CardPalette.SURFACE2
        val hueEdge = if (charge) CardPalette.ACCENT_EDGE else if (use) CardPalette.AMBER_EDGE else CardPalette.BORDER
        val soc = if (e.conn) e.soc else null

        c.drawText("CICLO AUTO", pad, 52f, textPaint(CardPalette.DIM, 17f, "sans-serif-medium", 0.18f))
        badge(c, if (!e.conn) "OFFLINE" else if (e.auto) "ATIVO" else "OFF",
            if (live) CardPalette.ACCENT else CardPalette.FAINT)

        tile(c, hueSoft, hueEdge)
        val tcx = pad + 44f; val tcy = 142f
        when {
            charge -> bolt(c, tcx, tcy, 44f, hue)
            use -> battery(c, tcx, tcy, 46f, soc ?: 0, hue)
            else -> pause(c, tcx, tcy, 44f, CardPalette.FAINT)
        }

        bigNumber(c, soc?.toString() ?: "--", if (soc != null) "%" else null, "BATERIA", soc != null)

        // pastilha da fase + modo imposto
        val pillTxt = if (charge) "CARGA" else if (use) "USO" else "--"
        val pp = textPaint(if (live) hue else CardPalette.FAINT, 19f, "monospace", 0.1f)
        val pillTW = pp.measureText(pillTxt)
        val pillR = w - pad; val pillL = pillR - pillTW - 32f
        roundRect(c, pillL, 100f, pillR, 134f, 14f, fill(hueSoft))
        roundRect(c, pillL + 0.5f, 100.5f, pillR - 0.5f, 133.5f, 14f, stroke(hueEdge, 1f))
        c.drawText(pillTxt, pillL + 16f, 124f, textPaint(if (live) hue else CardPalette.FAINT, 19f, "monospace", 0.1f))

        val modeTxt = if (live) (if (charge) "HEV" else if (use) "EV" else "--")
        else (if (e.model == 0) "HEV" else if (e.model == 1) "EV" else "--")
        val mp = textPaint(if (live) CardPalette.MUTED else CardPalette.FAINT, 17f, "monospace", 0.06f)
        val modeW = mp.measureText(modeTxt)
        c.drawText(modeTxt, w - pad - modeW, 166f, mp)
        val lp = textPaint(CardPalette.DIM, 13f, "sans-serif-medium", 0.1f)
        c.drawText("MODO", w - pad - modeW - 8f - lp.measureText("MODO"), 166f, lp)

        // barra: nível + traço no gatilho da próxima virada
        val trL = pad; val trR = w - pad; val trT = 232f + extra; val trB = 246f + extra
        roundRect(c, trL, trT, trR, trB, 7f, fill(CardPalette.SURFACE2))
        roundRect(c, trL + 0.5f, trT + 0.5f, trR - 0.5f, trB - 0.5f, 7f, stroke(CardPalette.BORDER, 1f))
        if (soc != null && soc > 0) {
            val fw = (trR - trL) * max(0, min(100, soc)) / 100f
            val fillC = if (!live) CardPalette.MUTED else if (charge) CardPalette.ACCENT
            else if (soc <= 20) CardPalette.RED else CardPalette.AMBER
            roundRect(c, trL, trT, trL + max(14f, fw), trB, 7f, fill(fillC))
        }
        val gate = if (charge) 75 else if (use) 20 else null
        if (gate != null) {
            val gx = trL + (trR - trL) * gate / 100f
            roundRect(c, gx - 1.5f, trT - 5f, gx + 1.5f, trB + 5f, 2f, fill(CardPalette.FG))
        }

        // escala
        val scaleY = 272f + extra
        val sp = textPaint(CardPalette.DIM, 13f, "monospace", 0.06f)
        c.drawText("0", trL, scaleY, sp)
        c.drawText("100", trR - sp.measureText("100"), scaleY, sp)
        val center = when {
            !e.conn -> "SEM CONEXAO"
            !e.auto -> "CICLO DESLIGADO"
            gate == null -> "AGUARDANDO SOC TARGET"
            soc == null -> "META $phase% · aguardando bateria"
            else -> {
                val falta = if (charge) max(0, gate - soc) else max(0, soc - gate)
                if (falta == 0) "VIRANDO DE FASE…"
                else "META $phase% · vira em ${if (charge) ">=" else "<="}$gate% (faltam $falta)"
            }
        }
        val cp = textPaint(CardPalette.MUTED, 13f, "monospace", 0.06f)
        c.drawText(center, (w - cp.measureText(center)) / 2f, scaleY, cp)
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val k = r / 24f
        val pts = listOf(13f to 2f, 4.5f to 13.5f, 11f to 13.5f, 10f to 22f, 18.5f to 10.5f, 12f to 10.5f)
            .map { (x, y) -> cx + (x - 12) * k to cy + (y - 12) * k }
        polygon(c, pts, fill(color))
    }

    private fun battery(c: Canvas, cx: Float, cy: Float, r: Float, pct: Int, color: Int) {
        val w = r * 1.15f; val h = r * 0.80f
        val l = cx - w / 2; val t = cy - h / 2
        roundRect(c, l + 1, t + 1, l + w * 0.82f - 1, t + h - 1, 5f, stroke(color, 2.2f))
        roundRect(c, l + w * 0.86f, cy - h * 0.16f, l + w, cy + h * 0.16f, 2f, fill(color))
        val inner = (w * 0.82f - 10) * max(0, min(100, pct)) / 100f
        if (inner > 0) roundRect(c, l + 5, t + 5, l + 5 + inner, t + h - 5, 2.5f, fill(color))
    }

    private fun pause(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val sp = stroke(color, 2.4f)
        c.drawLine(cx - r * 0.22f, cy - r * 0.42f, cx - r * 0.22f, cy + r * 0.42f, sp)
        c.drawLine(cx + r * 0.22f, cy - r * 0.42f, cx + r * 0.22f, cy + r * 0.42f, sp)
    }
}
