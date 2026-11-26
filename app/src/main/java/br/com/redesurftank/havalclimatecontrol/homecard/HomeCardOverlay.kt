package br.com.redesurftank.havalclimatecontrol.homecard

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import br.com.redesurftank.havalclimatecontrol.App
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog
import br.com.redesurftank.havalclimatecontrol.utils.ShizukuUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Card na Home da MediaCenter por OVERLAY, sem Frida (opção "Desenho: overlay").
 *
 * Em vez de injetar o com_beantechs_mediacenter_card.js no processo da MediaCenter, uma
 * janela TYPE_APPLICATION_OVERLAY nossa fica por cima do título "Música online" e da
 * fileira de mídia online, e só aparece enquanto a MediaCenterActivity está no topo. O
 * layout dela é todo ancorado no topo com margem fixa, então a posição não muda: os cards
 * (opacos) cobrem o título e o ícone do Deezer sem mexer no app do OEM.
 *
 * Motivo (medido no haval-engine-reverse, 25/11/2026): com a detecção pelo UsageStats o
 * overlay aparece antes do card do Frida, porque nada no caminho do boot espera o Shizuku.
 *  - janela: appop SYSTEM_ALERT_WINDOW; topo: appop GET_USAGE_STATS. Os dois são
 *    concedidos uma vez pelo Shizuku (onShizukuReady) e persistem.
 *  - dados do clima: ClimateStateHolder, que só enche depois da conexão ao veículo (via
 *    Shizuku). Até lá o card mostra o último retrato salvo em prefs device-protected.
 *  - card do ciclo: o mesmo arquivo /data/local/tmp/haval_ev_home_card do script. Leitura
 *    direta primeiro; se o SELinux negar, `cat` pelo Shizuku.
 *  - posição: calibrada por `uiautomator dump` (Shizuku) na primeira vez que a mídia
 *    aparece no topo, sem abrir nada. Antes disso o card não aparece.
 */
object HomeCardOverlay {
    private const val TAG = "HomeCardOverlay"

    const val MEDIA_PKG = "com.beantechs.mediacenter"
    const val MEDIA_ACTIVITY = "com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity"
    private const val ID_ROW = "$MEDIA_PKG:id/horizontalScrollView"
    private const val ID_TITLE = "$MEDIA_PKG:id/online_music"
    private const val ID_LOCAL = "$MEDIA_PKG:id/local_media"
    private const val DUMP_PATH = "/data/local/tmp/hcc_home_card_ui.xml"

    private const val EV_STATE = "/data/local/tmp/haval_ev_home_card"
    private const val EV_PKG = "br.com.redesurftank.havalevmanager"
    private const val EV_MAX_AGE_S = 90L
    private const val EV_POLL_MS = 3_000L
    private const val HVAC_POLL_MS = 1_000L

    private const val CARD_W = 536
    private const val CARD_H = 300
    private const val GAP = 24

    // Mesmo arquivo de prefs do serviço (device-protected): o boot lê antes do desbloqueio.
    private const val SERVICE_PREFS = "climate_control_prefs"
    const val KEY_OVERLAY_MODE = "home_card_overlay"

    private const val PREFS = "home_card_overlay"
    private const val K_ROW = "row"
    private const val K_TITLE = "title"
    private const val K_LOCAL = "local"
    private const val K_SNAP = "climate_snap"

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val bottom get() = y + h
        override fun toString() = "$x,$y,$w,$h"

        companion object {
            fun parse(s: String?): Rect? {
                val p = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
                if (p.size != 4 || p[2] <= 0 || p[3] <= 0) return null
                return Rect(p[0], p[1], p[2], p[3])
            }
        }
    }

    // ── estado para a tela de configurações ───────────────────────────────

    private val _status = MutableStateFlow("desligado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated.asStateFlow()

    // ── ciclo de vida ─────────────────────────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var climateView: ClimateCardView? = null
    private var evView: EvCardView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var watcher: UsageTopWatcher? = null
    private var mediaOnTop = false
    private var visible = false
    @Volatile private var calibrating = false
    private var row: Rect? = null
    private var title: Rect? = null
    private var local: Rect? = null
    private var ev: EvSnap? = null
    private var evReadMode = "?"
    private var evThread: Thread? = null
    private var startedAt = 0L
    private val logged = HashSet<String>()

    private fun prefs() = App.getDeviceProtectedContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun servicePrefs() =
        App.getDeviceProtectedContext().getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)

    /** Desenho por overlay (true, padrão desta versão) ou pelo Frida (false). */
    @JvmStatic
    fun isOverlayMode(): Boolean = servicePrefs().getBoolean(KEY_OVERLAY_MODE, true)

    /** Grava a escolha da tela. commit: a central costuma ser desligada logo depois. */
    @JvmStatic
    fun saveOverlayMode(on: Boolean) {
        servicePrefs().edit().putBoolean(KEY_OVERLAY_MODE, on).commit()
    }

    private fun log(msg: String) = PersistentLog.w(TAG, "$msg (uptime ${uptimeS()})")

    private fun logOnce(key: String, msg: String) {
        if (logged.add(key)) log(msg)
    }

    private fun uptimeS() = String.format(Locale.US, "%.2fs", SystemClock.elapsedRealtime() / 1000.0)

    @JvmStatic
    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        running = true
        appCtx = ctx.applicationContext
        startedAt = SystemClock.elapsedRealtime()
        logged.clear()
        val p = prefs()
        row = Rect.parse(p.getString(K_ROW, null))
        title = Rect.parse(p.getString(K_TITLE, null))
        local = Rect.parse(p.getString(K_LOCAL, null))
        _calibrated.value = row != null
        log("overlay ligado; calibrado=${row != null} fileira=$row título=$title")

        if (Settings.canDrawOverlays(appCtx)) main.post { addWindow() }
        else log("sem SYSTEM_ALERT_WINDOW ainda; concedo quando o Shizuku chegar")
        if (UsageTopWatcher.allowed(appCtx!!)) startWatcher()
        else log("sem GET_USAGE_STATS ainda; concedo quando o Shizuku chegar")
        main.post(hvacTick)
        evThread = Thread(evLoop, "homecard-ev").also { it.start() }
        _status.value = if (row == null) "ligado · calibra na 1ª vez que a mídia abrir" else "ligado"
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        watcher?.quit(); watcher = null
        evThread?.interrupt(); evThread = null
        main.removeCallbacks(hvacTick)
        main.post { removeWindow() }
        _status.value = "desligado"
        log("overlay desligado")
    }

    @Synchronized
    private fun startWatcher() {
        if (watcher != null) return
        val ctx = appCtx ?: return
        watcher = UsageTopWatcher(ctx, ::onTopChanged, ::log).also { it.start() }
    }

    /**
     * Shizuku de pé: concede o que falta e, se a mídia já estiver no topo sem calibração,
     * calibra agora. Chamar fora da main thread.
     */
    @JvmStatic
    fun onShizukuReady() {
        if (!running) return
        val ctx = appCtx ?: return
        if (!Settings.canDrawOverlays(ctx)) {
            ShizukuUtils.runCommandAndGetOutput(arrayOf("appops", "set", ctx.packageName, "SYSTEM_ALERT_WINDOW", "allow"))
            log("appop SYSTEM_ALERT_WINDOW concedido: ${Settings.canDrawOverlays(ctx)}")
            main.post { addWindow() }
        }
        if (!UsageTopWatcher.allowed(ctx)) {
            ShizukuUtils.runCommandAndGetOutput(arrayOf("appops", "set", ctx.packageName, "GET_USAGE_STATS", "allow"))
            log("appop GET_USAGE_STATS concedido: ${UsageTopWatcher.allowed(ctx)}")
        }
        if (UsageTopWatcher.allowed(ctx)) startWatcher()
        if (mediaOnTop && row == null) calibrateAsync()
    }

    // ── topo ──────────────────────────────────────────────────────────────

    private fun onTopChanged(top: ComponentName?, eventAt: Long) {
        val isMedia = top?.packageName == MEDIA_PKG && top.className == MEDIA_ACTIVITY
        mediaOnTop = isMedia
        if (isMedia) {
            logOnce("media-top", "mídia no topo pela 1ª vez (+${SystemClock.elapsedRealtime() - startedAt}ms do start)")
            if (row == null) calibrateAsync()
        }
        main.post { applyVisibility(eventAt) }
    }

    // ── janela ────────────────────────────────────────────────────────────

    /** Quantos cards entram agora: clima sempre, ciclo só com dado fresco do ev-manager. */
    private fun cardCount() = if (ev != null) 2 else 1

    /**
     * Retângulo da janela em px de tela. Começa no topo do título (que precisa sumir) e
     * desce o bastante para cobrir o ícone do OEM, sem passar do título "Mídia local".
     */
    private fun windowRect(): Rect? {
        val r = row ?: return null
        val top = title?.y ?: r.y
        var h = max(CARD_H, r.bottom - top)
        local?.let { h = min(h, it.y - top - 4) }
        val left = min(r.x, title?.x ?: r.x)
        val w = cardCount() * CARD_W + (cardCount() - 1) * GAP
        return Rect(left, top, min(w, max(r.w, CARD_W)), h)
    }

    @SuppressLint("RtlHardcoded")
    private fun addWindow() {
        val ctx = appCtx ?: return
        if (root != null || !running) return
        try {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val r = windowRect() ?: Rect(0, 0, CARD_W, CARD_H)
            val params = WindowManager.LayoutParams(
                r.w, r.h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = r.x; y = r.y
                alpha = 0f
                title = "hcc-home-card"
            }
            val frame = FrameLayout(ctx)
            val cv = ClimateCardView(ctx).apply {
                setOnClickListener { openApp(ctx.packageName) }
                snap = loadSnap() ?: snap
            }
            val evv = EvCardView(ctx).apply {
                setOnClickListener { openApp(EV_PKG) }
                visibility = View.GONE
            }
            frame.addView(cv, FrameLayout.LayoutParams(CARD_W, FrameLayout.LayoutParams.MATCH_PARENT))
            frame.addView(evv, FrameLayout.LayoutParams(CARD_W, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                leftMargin = CARD_W + GAP
            })
            val firstDraw = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    logOnce("first-frame", "1º frame do card (invisível, +${SystemClock.elapsedRealtime() - startedAt}ms)")
                    main.post { frame.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
            frame.viewTreeObserver.addOnDrawListener(firstDraw)
            wm!!.addView(frame, params)
            root = frame; climateView = cv; evView = evv; lp = params; visible = false
            log("janela adicionada em $r (+${SystemClock.elapsedRealtime() - startedAt}ms do start)")
            applyEv()
            applyVisibility(SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            log("addView falhou: ${t.javaClass.simpleName}: ${t.message}")
            _status.value = "erro ao adicionar a janela: ${t.message}"
        }
    }

    private fun removeWindow() {
        val v = root ?: return
        try { wm?.removeView(v) } catch (_: Throwable) {}
        root = null; climateView = null; evView = null; lp = null; visible = false
    }

    private fun relayout() {
        val v = root ?: return
        val p = lp ?: return
        val r = windowRect() ?: return
        if (p.x == r.x && p.y == r.y && p.width == r.w && p.height == r.h) return
        p.x = r.x; p.y = r.y; p.width = r.w; p.height = r.h
        try { wm?.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun applyVisibility(eventAt: Long) {
        val v = root ?: return
        val p = lp ?: return
        // Sem calibração não há onde desenhar: melhor a fileira do OEM que um card torto.
        val want = running && mediaOnTop && row != null && !calibrating
        if (want == visible) return
        visible = want
        relayout()
        p.alpha = if (want) 1f else 0f
        p.flags = if (want) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            log("updateViewLayout falhou: ${t.message}")
            return
        }
        _status.value = if (want) "card visível" else if (row == null) "ligado · calibra na 1ª vez que a mídia abrir"
        else "ligado · mídia fora do topo"
        if (want) logOnce("first-visible",
            "card VISÍVEL pela 1ª vez: ${SystemClock.elapsedRealtime() - eventAt}ms depois da mídia abrir")
    }

    // ── dados do clima ────────────────────────────────────────────────────

    private val hvacTick = object : Runnable {
        override fun run() {
            if (!running) return
            val live = snapFromHolder()
            if (live != null) {
                climateView?.snap = live
                saveSnap(live)
            }
            main.postDelayed(this, HVAC_POLL_MS)
        }
    }

    /** Retrato do ClimateStateHolder, ou null se o veículo ainda não respondeu nada. */
    private fun snapFromHolder(): ClimateSnap? {
        val s = ClimateStateHolder
        val power = s.powerMode.toFloatOrNull()
        val inside = s.insideTemp.toFloatOrNull()
        if (power == null && inside == null) return null
        val tempOk = inside != null && inside > -50f && inside < 90f
        val on = if (power == null) tempOk else power != 0f
        return ClimateSnap(
            on = on,
            ac = s.acEnable.toFloatOrNull() == 1f,
            auto = s.autoEnable.toFloatOrNull() == 1f,
            temp = if (tempOk) inside else null,
            fan = max(0, min(7, (s.fanSpeed.toFloatOrNull() ?: 0f).roundToInt())),
            turbo = on && s.turboActive
        )
    }

    private var lastSaved: ClimateSnap? = null

    private fun saveSnap(s: ClimateSnap) {
        if (s == lastSaved) return
        lastSaved = s
        val txt = listOf(s.on, s.ac, s.auto, s.temp ?: "", s.fan, s.turbo).joinToString(";")
        try { prefs().edit().putString(K_SNAP, txt).apply() } catch (_: Throwable) {}
    }

    private fun loadSnap(): ClimateSnap? = try {
        val p = prefs().getString(K_SNAP, null)?.split(";")
        if (p == null || p.size != 6) null
        else ClimateSnap(p[0] == "true", p[1] == "true", p[2] == "true", p[3].toFloatOrNull(),
            p[4].toIntOrNull() ?: 0, p[5] == "true")
    } catch (_: Throwable) { null }

    // ── dados do ciclo (haval-ev-manager) ─────────────────────────────────

    private val evLoop = Runnable {
        while (running && !Thread.currentThread().isInterrupted) {
            val e = readEv()
            if (e != ev) {
                ev = e
                main.post { applyEv() }
            }
            try { Thread.sleep(EV_POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun applyEv() {
        val e = ev
        evView?.let {
            it.visibility = if (e != null) View.VISIBLE else View.GONE
            if (e != null) it.snap = e
        }
        relayout()
    }

    private fun readEvLine(): String? {
        try {
            val f = File(EV_STATE)
            if (f.exists()) {
                val l = f.bufferedReader().use { it.readLine() }
                if (evReadMode != "direto") { evReadMode = "direto"; log("ciclo: arquivo lido direto") }
                return l
            }
        } catch (_: Throwable) { /* SELinux/DAC: tenta pelo Shizuku */ }
        if (!ShizukuUtils.isAvailable()) return null
        val out = ShizukuUtils.runCommandAndGetOutput(arrayOf("cat", EV_STATE)).trim()
        if (out.isEmpty()) return null
        if (evReadMode != "shizuku") { evReadMode = "shizuku"; log("ciclo: arquivo lido pelo Shizuku (leitura direta negada)") }
        return out.lineSequence().firstOrNull()
    }

    /** Mesmas regras do script: linha ausente, ilegível ou vencida => sem card do ciclo. */
    private fun readEv(): EvSnap? {
        val line = readEvLine()?.trim()
        if (line.isNullOrEmpty()) return null
        val kv = line.split(";").mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.toMap()
        val ts = kv["ts"]?.toLongOrNull() ?: return null
        val age = System.currentTimeMillis() / 1000 - ts
        if (age > EV_MAX_AGE_S) return null
        val phase = kv["phase"]?.toIntOrNull()
        return EvSnap(
            conn = kv["conn"] == "1",
            auto = kv["auto"] == "1",
            phase = if (phase == 80 || phase == 20) phase else null,
            soc = kv["soc"]?.toIntOrNull(),
            model = kv["model"]?.toIntOrNull()
        )
    }

    // ── calibração (uiautomator pelo Shizuku) ─────────────────────────────

    private fun calibrateAsync() {
        if (calibrating || !ShizukuUtils.isAvailable()) return
        Thread({ calibrate(false) }, "homecard-calib").start()
    }

    /**
     * Lê do dump da hierarquia os bounds do título, da fileira e do "Mídia local".
     * `openMedia` abre a MediaCenter antes (botão da tela); automático, ela já está no
     * topo. Bloqueante.
     */
    @JvmStatic
    fun calibrate(openMedia: Boolean): String {
        if (!ShizukuUtils.isAvailable()) return "Shizuku indisponível"
        if (calibrating) return "calibração já em andamento"
        calibrating = true
        main.post { applyVisibility(SystemClock.elapsedRealtime()) }
        try {
            if (openMedia) {
                ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c",
                    "am start -n $MEDIA_PKG/$MEDIA_ACTIVITY >/dev/null 2>&1"))
                Thread.sleep(2500)
            } else {
                Thread.sleep(1000)   // deixa a tela assentar depois do resume
            }
            var xml = ""
            var last = ""
            for (i in 1..3) {
                last = ShizukuUtils.runCommandAndGetOutput(arrayOf("sh", "-c",
                    "uiautomator dump $DUMP_PATH 2>&1; cat $DUMP_PATH 2>/dev/null; rm -f $DUMP_PATH"))
                val at = last.indexOf("<?xml")
                if (at >= 0) { xml = last.substring(at); break }
                Thread.sleep(1200)
            }
            if (xml.isEmpty()) return "uiautomator não gerou o dump: ${last.take(160)}".also { log(it) }
            val r = boundsOf(xml, ID_ROW)
                ?: return "fileira não achada no dump (a mídia estava no topo?)".also { log(it) }
            val t = boundsOf(xml, ID_TITLE)
            val l = boundsOf(xml, ID_LOCAL)
            val e = prefs().edit().putString(K_ROW, r.toString())
            if (t != null) e.putString(K_TITLE, t.toString()) else e.remove(K_TITLE)
            if (l != null) e.putString(K_LOCAL, l.toString()) else e.remove(K_LOCAL)
            e.apply()
            row = r; title = t; local = l
            _calibrated.value = true
            // Os ícones do OEM ficam no container (wrap_content). Se ele for mais largo que um
            // card, sobra ícone aparecendo à direita quando só o card de clima entra.
            val icons = boundsOf(xml, "$MEDIA_PKG:id/online_music_container")
            if (icons != null && icons.x + icons.w > r.x + CARD_W) {
                log("atenção: ícones do OEM vão até x=${icons.x + icons.w}, além do card de clima (x=${r.x + CARD_W})")
            }
            val msg = "calibrado: fileira $r · título ${t ?: "?"} · mídia local ${l ?: "?"} · ícones ${icons ?: "?"}"
            log(msg)
            return msg
        } catch (t: Throwable) {
            return "calibração falhou: ${t.message}".also { log(it) }
        } finally {
            calibrating = false
            main.post { applyVisibility(SystemClock.elapsedRealtime()) }
        }
    }

    private fun boundsOf(xml: String, resId: String): Rect? {
        val node = Regex("<node [^>]*resource-id=\"" + Regex.escape(resId) + "\"[^>]*>").find(xml) ?: return null
        val b = Regex("""bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""").find(node.value) ?: return null
        val (l, t, r, bt) = b.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt() - l.toInt(), bt.toInt() - t.toInt())
    }

    // ── clique ────────────────────────────────────────────────────────────

    private fun openApp(pkg: String) {
        val ctx = appCtx ?: return
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            log("abrir $pkg falhou: ${t.message}")
        }
    }
}
