/*
 * Hook do SystemUI (com.android.systemui) — Haval H6 GT.
 *
 * Duas funcoes, num script so (dois agentes Frida no mesmo processo e risco a toa):
 *
 * 1) GUARDA DO POPUP DO HVAC — fica sempre ligada.
 *    BeanStatusBarManager.handleValue(dataId, dataValue), case
 *    "car.hvac.panel_display_notify": com valor "2" (e sem console auxiliar) chama
 *    AppUtils.startApp(ctx, "com.beantechs.hvac", "com.beantechs.hvac.MainActivity").
 *    O modulo do HVAC publica esse "2" depois de qualquer escrita, inclusive as do app,
 *    e o app do OEM rouba o foco. Antes a defesa era `pm disable-user` no pacote a cada
 *    escrita (e por sessao, com a tela do app aberta) — e cada `pm` regrava o estado de
 *    pacotes e dispara PACKAGE_CHANGED para todo mundo, a Home inclusive.
 *    Aqui so esse evento passa pelo app: o provider
 *    content://br.com.redesurftank.havalclimatecontrol.hvacguard responde, metodo
 *    "suppress", se o popup e consequencia nossa (tela do app na frente, ou escrita
 *    automatica recente). Qualquer falha na consulta deixa o popup do OEM passar.
 *    A linha "GUARDA HVAC ARMADA" no log e o que o servico le (FridaUtils.probe) para
 *    parar de usar o `pm disable` — o texto tem de casar com GUARD_ARMED_MARK.
 *    Com o app de clima do OEM desativado (opcoes de performance), o mesmo provider
 *    tambem abre a faixa do app no lugar dele: no popup que nao veio de nos e no botao
 *    de A/C da barra lateral (AppUtils.Companion.startHVACApp, metodo "open").
 *
 * 2) TEMPERATURA EXTERNA REAL — ligada por /data/local/tmp/haval_real_temp ("on"/"off").
 *    Engenharia reversa (SystemUI decompilado):
 *    - A barra mostra a temp via BeanCarStatusBarView.mWeatherTemp, alimentada por
 *      BeanWeatherManager.onWeatherInfoUpdate (ou seja, pelo APP DE CLIMA). Ao
 *      desativar o app de clima, fica "--" (R.string.bean_info_label_default).
 *    - Existe BeanCarStatusBarView.onOutsideTempUpdate(float) que usaria o sensor
 *      real, MAS só roda em BuildConfig.type==1||3. Neste carro type==2, e o
 *      BeanStatusBarManager sequer atualiza o sensor (case "car.basic.outside_temp"
 *      é guardado por type==1||3). Logo, hookar onOutsideTempUpdate não adianta —
 *      ele nunca é chamado aqui.
 *    Solução: ler o sensor REAL direto do platform (como o próprio código faz p/ o
 *    POWER_MODE) e escrever na mWeatherTemp periodicamente, ignorando type e o app
 *    de clima:
 *      PlatformAdapterClient.getInstance().getData("car.basic.outside_temp")
 */
"use strict";

function log(m) { console.log("[sysui] " + m); }

Java.perform(function () {
    var Str = Java.use("java.lang.String");

    // ── 1) guarda do popup do HVAC ──────────────────────────────────────────
    var PANEL_KEY = "car.hvac.panel_display_notify";
    var GUARD_URI = "content://br.com.redesurftank.havalclimatecontrol.hvacguard";

    /** Chama o provider do app; null se ele nao respondeu (app fora, provider ausente). */
    function guardCall(method) {
        var Uri = Java.use("android.net.Uri");
        var ctx = Java.use("android.app.ActivityThread").currentApplication();
        var cr  = ctx.getContentResolver();
        return cr.call.overload("android.net.Uri", "java.lang.String", "java.lang.String", "android.os.Bundle")
            .call(cr, Uri.parse(GUARD_URI), method, null, null);
    }

    function suppressReason() {
        try {
            var b = guardCall("suppress");
            if (b === null) return null;
            var r = b.getString("reason");
            return r === null ? null : ("" + r);
        } catch (e) {
            log("guarda: provider indisponivel (" + e + ") — popup liberado");
            return null;
        }
    }

    try {
        var BSM = Java.use("com.bean.statusbar.BeanStatusBarManager");
        var hv  = BSM.handleValue.overload("java.lang.String", "java.lang.String");
        hv.implementation = function (key, value) {
            if (key !== null && value !== null && ("" + key) === PANEL_KEY
                    && ("" + value).trim() === "2") {
                var why = suppressReason();
                if (why !== null) {
                    log("popup do HVAC do OEM barrado (" + why + ")");
                    return;
                }
            }
            return hv.call(this, key, value);
        };
        log("GUARDA HVAC ARMADA (BeanStatusBarManager.handleValue)");
    } catch (e) {
        log("guarda: hook falhou, servico segue no pm disable: " + e);
    }

    // Botao de A/C da barra lateral: BeanCarNavigationBarView.onClick chama
    // AppUtils.Companion.startHVACApp, que abre o com.beantechs.hvac direto (sem passar
    // pelo handleValue acima). Com o app de clima do OEM desativado nas opcoes de
    // performance, o provider responde "handled" e abre a faixa do app no lugar; em
    // qualquer outro caso (ou erro) segue o original.
    try {
        var AUC = Java.use("com.bean.utils.AppUtils$Companion");
        AUC.startHVACApp.overloads.forEach(function (ov) {
            ov.implementation = function () {
                try {
                    var b = guardCall("open");
                    if (b !== null && b.getBoolean("handled")) {
                        log("botao de A/C: app de clima do OEM desativado — abrindo a faixa");
                        return;
                    }
                } catch (e) {
                    log("botao de A/C: provider indisponivel (" + e + ") — segue o OEM");
                }
                return ov.apply(this, arguments);
            };
        });
        log("botao de A/C da barra lateral interceptado (AppUtils.startHVACApp)");
    } catch (e) {
        log("botao de A/C: hook falhou: " + e);
    }

    // ── 2) temperatura externa real ─────────────────────────────────────────
    var PAC = Java.use("com.beantechs.adapterservice.client.PlatformAdapterClient");
    var JFile     = Java.use("java.io.File");
    var FileReader = Java.use("java.io.FileReader");
    var BufReader  = Java.use("java.io.BufferedReader");
    var OUTSIDE = "car.basic.outside_temp";
    var CTRL    = "/data/local/tmp/haval_real_temp";
    var views = [];
    var tempOn = null;          // marcador "ainda nao li"

    function readCtrl() {
        try {
            var f = JFile.$new(CTRL);
            if (!f.exists()) return false;
            var br = BufReader.$new(FileReader.$new(f));
            var l  = br.readLine();
            br.close();
            return ((l === null ? "" : ("" + l)).trim().toLowerCase() === "on");
        } catch (e) {
            log("readCtrl err: " + e);
            return false;
        }
    }

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

    /** Escreve s na mWeatherTemp de todas as barras; devolve quantas pintou. */
    function paint(s) {
        if (views.length === 0) findViews();
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
        if (painted === 0) views = [];
        return painted;
    }

    var lastLog = "", tick = 0;
    function tickLog(s) { if (s !== lastLog || tick % 15 === 0) { lastLog = s; log(s); } }

    setInterval(function () {
        tick++;
        Java.perform(function () {
            var want = readCtrl();
            if (want !== tempOn) {
                var was = tempOn;
                tempOn = want;
                log("temp externa real = " + (want ? "ON" : "OFF"));
                // Sem o servico de clima do OEM ninguem mais escreve ali: desligar
                // deixaria o ultimo valor pintado congelado na barra.
                if (!want && was === true) paint("--");
            }
            if (!tempOn) return;

            var t = readTemp();
            if (t === null || t <= -40.0 || t >= 86.5) { tickLog("sensor invalido/ausente: " + t); return; }
            var s = Math.round(t) + "°"; // ex.: "28°"
            if (paint(s) === 0) tickLog("nenhuma view pintavel (barra criada?) — recapturando");
            else tickLog("pintado " + s + " (sensor " + t + ")");
        });
    }, 2000);

    log("hook do SystemUI ativo (guarda do HVAC + temp externa real via " + CTRL + ")");
});
