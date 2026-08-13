/*
 * Hook do SystemUI (com.android.systemui) — Haval H6 GT — TEMPERATURA EXTERNA REAL.
 *
 * Engenharia reversa (SystemUI decompilado):
 *   - A barra mostra a temp via BeanCarStatusBarView.mWeatherTemp, alimentada por
 *     BeanWeatherManager.onWeatherInfoUpdate (ou seja, pelo APP DE CLIMA). Ao
 *     desativar o app de clima, fica "--" (R.string.bean_info_label_default).
 *   - Existe BeanCarStatusBarView.onOutsideTempUpdate(float) que usaria o sensor
 *     real, MAS só roda em BuildConfig.type==1||3. Neste carro type==2, e o
 *     BeanStatusBarManager sequer atualiza o sensor (case "car.basic.outside_temp"
 *     é guardado por type==1||3). Logo, hookar onOutsideTempUpdate não adianta —
 *     ele nunca é chamado aqui.
 *
 * Solução: ler o sensor REAL direto do platform (como o próprio código faz p/ o
 *   POWER_MODE) e escrever na mWeatherTemp periodicamente, ignorando type e o app
 *   de clima:
 *     PlatformAdapterClient.getInstance().getData("car.basic.outside_temp")
 */
"use strict";

function log(m) { console.log("[sysui-outtemp] " + m); }

Java.perform(function () {
    var PAC = Java.use("com.beantechs.adapterservice.client.PlatformAdapterClient");
    var Str = Java.use("java.lang.String");
    var OUTSIDE = "car.basic.outside_temp";
    var views = [];

    function findViews() {
        views = [];
        try {
            Java.choose("com.bean.statusbar.BeanCarStatusBarView", {
                onMatch: function (v) { views.push(v); },
                onComplete: function () {}
            });
        } catch (e) { log("choose err: " + e); }
    }

    function readTemp() {
        try {
            var raw = PAC.getInstance().getData(OUTSIDE);
            var t = parseFloat(raw);
            return isNaN(t) ? null : t;
        } catch (e) { log("getData err: " + e); return null; }
    }

    var lastLog = "", tick = 0;
    function tickLog(s) { if (s !== lastLog || tick % 15 === 0) { lastLog = s; log(s); } }

    setInterval(function () {
        tick++;
        Java.perform(function () {
            var t = readTemp();
            if (t === null || t <= -40.0 || t >= 86.5) { tickLog("sensor invalido/ausente: " + t); return; }
            var s = Math.round(t) + "°"; // ex.: "28°"
            if (views.length === 0) findViews();
            if (views.length === 0) { tickLog("BeanCarStatusBarView nao encontrada (barra criada?)"); return; }
            var painted = 0;
            views.forEach(function (v) {
                try {
                    var tv = v.mWeatherTemp.value;
                    if (tv !== null) {
                        Java.scheduleOnMainThread(function () {
                            try { tv.setText.overload("java.lang.CharSequence").call(tv, Str.$new(s)); }
                            catch (e) { log("setText err: " + e); }
                        });
                        painted++;
                    }
                } catch (e) { /* view morta */ }
            });
            if (painted === 0) { views = []; tickLog("nenhuma view pintavel — recapturando"); }
            else tickLog("pintado " + s + " (sensor " + t + ")");
        });
    }, 2000);

    log("hook temp externa REAL ativo (le car.basic.outside_temp -> mWeatherTemp)");
});
