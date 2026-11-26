package br.com.redesurftank.havalclimatecontrol.homecard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/**
 * Qual Activity está no topo, pelo UsageStatsManager e SEM Shizuku.
 *
 * Portado do haval-engine-reverse (overlay/UsageTopWatcher.kt, v2.33.0), onde foi medido
 * no carro em 25/11/2026: com a detecção pelo Shizuku o overlay só aparecia quando o
 * binder chegava, no mesmo tempo do Frida; com o UsageStats ele aparece antes.
 *
 * O ActivityManager grava um MOVE_TO_FOREGROUND no instante do resume, e o app lê esses
 * eventos com o appop GET_USAGE_STATS — concedido uma vez pelo Shizuku, persistente.
 * Antes do desbloqueio do usuário o UsageStats não responde; o poll só segue.
 */
class UsageTopWatcher(
    ctx: Context,
    private val onTop: (component: ComponentName?, eventAt: Long) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        const val POLL_MS = 250L
        /** Releitura de uma janela já lida: um evento gravado logo depois da consulta anterior não escapa. */
        private const val OVERLAP_MS = 1000L

        fun allowed(ctx: Context): Boolean = try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }

    private val thread = HandlerThread("homecard-usage").apply { start() }
    private val handler = Handler(thread.looper)
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Volatile private var running = false
    private var since = 0L
    private var current: ComponentName? = null
    private var firstReadDone = false
    private var errorLogged = false

    fun start() {
        if (running) return
        running = true
        // Primeira leitura cobre desde o boot: pega a tela que já está aberta.
        since = System.currentTimeMillis() - SystemClock.elapsedRealtime() - OVERLAP_MS
        handler.post(tick)
    }

    fun quit() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post { thread.quitSafely() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                read()
            } catch (t: Throwable) {
                if (!errorLogged) { log("UsageStats falhou: ${t.message}"); errorLogged = true }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun read() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(since, now) ?: return   // usuário ainda bloqueado
        var latest: ComponentName? = null
        var latestAt = 0L
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            @Suppress("DEPRECATION")
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.timeStamp >= latestAt) {
                val cls = ev.className ?: continue
                latest = ComponentName(ev.packageName, cls)
                latestAt = ev.timeStamp
            }
        }
        since = now - OVERLAP_MS
        if (!firstReadDone) {
            firstReadDone = true
            log("UsageStats respondendo" +
                (latest?.let { "; topo desde o boot: ${it.flattenToShortString()}" } ?: "; nenhum evento ainda"))
        }
        if (latest != null && latest != current) {
            current = latest
            // timeStamp é relógio de parede: converte para elapsedRealtime.
            onTop(latest, SystemClock.elapsedRealtime() - (now - latestAt))
        }
    }
}
