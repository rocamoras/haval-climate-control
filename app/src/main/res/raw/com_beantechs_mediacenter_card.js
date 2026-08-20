/*
 * Card de clima na Home da MediaCenter (com.beantechs.mediacenter).
 *
 * Substitui a fileira de mídia online da tela principal por um card próprio com
 * o estado do ar-condicionado: A/C ligado, temperatura interna e velocidade do
 * vento. Visual copiado do HMI do app (HmiSurface #141414, borda 7% branco,
 * accent #22C55E), desenhado num Bitmap por Canvas — o APK do OEM não aceita
 * recursos novos, e desenhar tudo num bitmap dá controle exato do layout sem
 * aninhar Views via Frida.
 *
 * Base de engenharia reversa: haval-engine-reverse/docs/HANDOFF-cards-midia-online.md
 *   - MediaCenterActivity.loadOnlineMusicCard() desenha um ImageView por CP de
 *     MediaCenterManager.getCPList() -> ... -> OnlineOsModelImpl.getMCpList().
 *     Roda SÓ no onCreate e começa com removeAllViews().
 *   - O id do CP vai na `elevation` da view; MediaCenterActivity.onClick lê de
 *     volta, valida 501..600 e chama skipApp4OnlineOs(cp). Reusamos esse
 *     dispatch com um CP virtual (CP_CARD) para abrir o nosso app.
 *   - Valores pelo mesmo caminho da barra de status:
 *     PlatformAdapterClient.getInstance().getData(chave).
 *
 * Chaves confirmadas no dex de com.beantechs.hvac.apk:
 *   car.hvac.ac_enable, car.hvac.auto_enable, car.hvac.fan_speed,
 *   car.hvac.power_mode; e car.basic.inside_temp (confirmada em campo).
 *
 * Geometria (config DEFAULT do APK — esta central é 1792x720, ela NÃO usa o
 * layout-1792x1080 que o handoff mediu; daí os números divergirem dele):
 *   título online_music   marginTop 100  (32sp, ~37px de altura)
 *   HorizontalScrollView  marginTop 168, width 1158
 *   título local_media    marginTop 430  <- teto rígido: passar disso sobrepõe
 *   cards de conteúdo     marginTop 498
 * Com o título visível sobram só 262px. Como tudo é ancorado no topo do pai com
 * margem fixa e sem encadeamento (handoff §2), esconder o título NÃO sobe a
 * fileira sozinho — por isso reescrevemos o topMargin dela para 100, o que dá
 * 330px de altura útil para um card de 300.
 *
 * A fileira do OEM é desenhada por loadOnlineMusicCard() no onCreate. Não existe
 * preferencia persistida atrás dela: sem o hook instalado, os ícones aparecem. Por
 * isso hookamos também loadOnlineMusicCard e pintamos o card DENTRO da mesma
 * chamada — assim o card entra no mesmo frame do onCreate, sem passar pelo
 * intervalo de 1,5s do poll.
 *
 * Controle em runtime por /data/local/tmp/haval_home_card:
 *   "on"  -> fileira esvaziada e içada, título escondido, card desenhado
 *   "off" (ou arquivo ausente) -> restaura fileira, margem e título originais
 */
"use strict";

function log(m) { console.log("[hcc-card] " + m); }

Java.perform(function () {

    var CTRL = "/data/local/tmp/haval_home_card";
    var ACT  = "com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity";
    var IMPL_CLS = "com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl";
    var OUR_PKG  = "br.com.redesurftank.havalclimatecontrol";
    var CARD_TAG = "hcc-home-card";
    var CP_CARD  = 590;          // CP virtual: precisa cair em 501..600 p/ o onClick do OEM
    var ROW_TOP  = 100;          // topMargin da fileira com o titulo escondido

    // ── wrappers ───────────────────────────────────────────────────────────
    // Regra do handoff §3: instância de Java.choose expõe só os métodos da
    // própria classe. Todo método herdado (findViewById, setVisibility, ...)
    // vai por Java.cast para a classe que o DECLARA.
    var JFile      = Java.use("java.io.File");
    var FileReader = Java.use("java.io.FileReader");
    var BufReader  = Java.use("java.io.BufferedReader");
    var Integer    = Java.use("java.lang.Integer");
    var Str        = Java.use("java.lang.String");

    var ActivityCls  = Java.use("android.app.Activity");
    var ViewCls      = Java.use("android.view.View");
    var ViewGroupCls = Java.use("android.view.ViewGroup");
    var VGLayoutParams = Java.use("android.view.ViewGroup$LayoutParams");
    var MarginLP       = Java.use("android.view.ViewGroup$MarginLayoutParams");
    var TextViewCls  = Java.use("android.widget.TextView");
    var ImageView    = Java.use("android.widget.ImageView");
    var LLParams     = Java.use("android.widget.LinearLayout$LayoutParams");

    var Bitmap    = Java.use("android.graphics.Bitmap");
    var BmpConfig = Java.use("android.graphics.Bitmap$Config");
    var Canvas    = Java.use("android.graphics.Canvas");
    var Paint     = Java.use("android.graphics.Paint");
    var PStyle    = Java.use("android.graphics.Paint$Style");
    var PCap      = Java.use("android.graphics.Paint$Cap");
    var PAlign    = Java.use("android.graphics.Paint$Align");
    var RectF     = Java.use("android.graphics.RectF");
    var Typeface  = Java.use("android.graphics.Typeface");

    var PAC = null;
    try {
        PAC = Java.use("com.beantechs.adapterservice.client.PlatformAdapterClient");
    } catch (e) {
        log("PlatformAdapterClient indisponível neste processo: " + e);
    }

    var loggedOnce = {};
    function logOnce(key, msg) {
        if (loggedOnce[key]) return;
        loggedOnce[key] = true;
        log(msg);
    }

    // ── paleta (idêntica ao HmiTheme do app) ────────────────────────────────
    // `|0` converte para int com sinal: 0xFF141414 estoura o range de int Java
    // e o marshaller do Frida rejeita o número sem a conversão.
    var C_SURFACE     = 0xFF141414 | 0;
    var C_SURFACE2    = 0xFF1C1C1C | 0;
    var C_FG          = 0xFFFAFAFA | 0;
    var C_MUTED       = 0xFFA3A3A3 | 0;
    var C_DIM         = 0xFF6B6B6B | 0;
    var C_FAINT       = 0xFF404040 | 0;
    var C_ACCENT      = 0xFF22C55E | 0;
    var C_ACCENT_SOFT = 0x1F22C55E | 0;
    var C_ACCENT_EDGE = 0x6622C55E | 0;
    var C_BORDER      = 0x1AFFFFFF | 0;

    // ── geometria (densidade 1.0 nesta tela: 1dp = 1px) ─────────────────────
    // 330px úteis com a fileira içada; 300 deixa 30 de folga antes de "Mídia local".
    // Largura: a fileira tem 1158 nesta config, então 536 cabe com sobra.
    var W = 536, H = 300, PAD = 32;

    // ── leitura do veículo ──────────────────────────────────────────────────
    var K_INSIDE = "car.basic.inside_temp";
    var K_AC     = "car.hvac.ac_enable";
    var K_AUTO   = "car.hvac.auto_enable";
    var K_FAN    = "car.hvac.fan_speed";
    var K_POWER  = "car.hvac.power_mode";
    var FAN_MAX  = 7;

    function readRaw(k) {
        if (PAC === null) return null;
        try {
            var v = PAC.getInstance().getData(k);
            return (v === null) ? null : ("" + v).trim();
        } catch (e) {
            logOnce("getData:" + k, "getData(" + k + ") err: " + e);
            return null;
        }
    }

    function readNum(k) {
        var raw = readRaw(k);
        if (raw === null || raw === "") return null;
        var v = parseFloat(raw);
        return isNaN(v) ? null : v;
    }

    /** Snapshot do clima. `on` = HVAC energizado (carro ligado). */
    function readState() {
        var power  = readNum(K_POWER);
        var inside = readNum(K_INSIDE);
        var tempOk = (inside !== null && inside > -50.0 && inside < 90.0);
        // power_mode ausente nesta versão: cai para "o sensor interno responde?"
        var on = (power === null) ? tempOk : (power !== 0);
        return {
            on:     on,
            ac:     readNum(K_AC) === 1,
            auto:   readNum(K_AUTO) === 1,
            temp:   tempOk ? inside : null,
            fan:    Math.max(0, Math.min(FAN_MAX, Math.round(readNum(K_FAN) || 0)))
        };
    }

    function stateSig(s) {
        return [s.on, s.ac, s.auto, (s.temp === null ? "x" : Math.round(s.temp)), s.fan].join("|");
    }

    // ── desenho ─────────────────────────────────────────────────────────────
    function newPaint(color, style, strokeW) {
        var p = Paint.$new.overload().call(Paint);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(style ? PStyle.STROKE.value : PStyle.FILL.value);
        if (strokeW) {
            p.setStrokeWidth(strokeW);
            p.setStrokeCap(PCap.ROUND.value);
        }
        return p;
    }

    function textPaint(color, size, weightName, spacing) {
        var p = newPaint(color, false, 0);
        p.setTextSize(size);
        p.setTypeface(Typeface.create.overload("java.lang.String", "int")
            .call(Typeface, weightName, 0));
        if (spacing) p.setLetterSpacing(spacing);
        return p;
    }

    function roundRect(cv, l, t, r, b, rad, paint) {
        cv.drawRoundRect.overload("android.graphics.RectF", "float", "float",
            "android.graphics.Paint")
            .call(cv, RectF.$new.overload("float", "float", "float", "float")
                .call(RectF, l, t, r, b), rad, rad, paint);
    }

    function line(cv, x1, y1, x2, y2, paint) {
        cv.drawLine.overload("float", "float", "float", "float", "android.graphics.Paint")
            .call(cv, x1, y1, x2, y2, paint);
    }

    function circle(cv, cx, cy, r, paint) {
        cv.drawCircle.overload("float", "float", "float", "android.graphics.Paint")
            .call(cv, cx, cy, r, paint);
    }

    function text(cv, s, x, y, paint) {
        cv.drawText.overload("java.lang.String", "float", "float", "android.graphics.Paint")
            .call(cv, s, x, y, paint);
    }

    function arc(cv, l, t, r, b, start, sweep, paint) {
        cv.drawArc.overload("android.graphics.RectF", "float", "float", "boolean",
            "android.graphics.Paint")
            .call(cv, RectF.$new.overload("float", "float", "float", "float")
                .call(RectF, l, t, r, b), start, sweep, false, paint);
    }

    var DEG = Math.PI / 180.0;

    /** Floco de neve: 3 eixos completos + farpas nas 6 pontas. */
    function drawSnowflake(cv, cx, cy, r, color) {
        var p = newPaint(color, true, 2.2);
        for (var k = 0; k < 6; k++) {
            var a  = (90 + k * 60) * DEG;
            var ex = cx + r * Math.cos(a);
            var ey = cy - r * Math.sin(a);
            line(cv, cx, cy, ex, ey, p);
            // farpas a 62% do braço, abrindo 40 graus para fora
            var bx = cx + r * 0.62 * Math.cos(a);
            var by = cy - r * 0.62 * Math.sin(a);
            [40, -40].forEach(function (off) {
                var ab = a + off * DEG;
                line(cv, bx, by,
                    bx + r * 0.30 * Math.cos(ab),
                    by - r * 0.30 * Math.sin(ab), p);
            });
        }
    }

    /** Ícone de vento: 3 linhas horizontais, as duas maiores com um laço. */
    function drawWind(cv, x, cy, color) {
        var p = newPaint(color, true, 2.4);
        line(cv, x, cy - 9, x + 20, cy - 9, p);
        arc(cv, x + 15, cy - 14, x + 25, cy - 4, 90, 270, p);
        line(cv, x, cy, x + 26, cy, p);
        arc(cv, x + 21, cy - 5, x + 31, cy + 5, 270, 270, p);
        line(cv, x, cy + 9, x + 15, cy + 9, p);
    }

    /**
     * Pinta o card inteiro num Bitmap ARGB_8888 de 536x324.
     * Só é chamado quando o snapshot muda (ver tick) — redesenhar a 1,5s
     * geraria ~700 KB de lixo por tick sem necessidade.
     */
    function drawCard(s) {
        var bmp = Bitmap.createBitmap.overload("int", "int", "android.graphics.Bitmap$Config")
            .call(Bitmap, W, H, BmpConfig.ARGB_8888.value);
        var cv = Canvas.$new.overload("android.graphics.Bitmap").call(Canvas, bmp);

        // fundo + borda
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, newPaint(C_SURFACE, false, 0));
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, newPaint(C_BORDER, true, 1));

        var acOn   = s.on && s.ac;
        var autoOn = s.on && s.auto;

        // ── cabeçalho: rótulo + selo de modo ──
        text(cv, "CLIMA", PAD, 52,
            textPaint(C_DIM, 17, "sans-serif-medium", 0.18));

        var badge   = s.on ? (s.auto ? "AUTO" : "MANUAL") : "OFF";
        var badgeP  = textPaint(autoOn ? C_ACCENT : C_FAINT, 16, "monospace", 0.06);
        var badgeW  = badgeP.measureText.overload("java.lang.String").call(badgeP, badge);
        text(cv, badge, W - PAD - badgeW, 52, badgeP);
        circle(cv, W - PAD - badgeW - 16, 46, 5,
            newPaint(autoOn ? C_ACCENT : C_FAINT, false, 0));

        // ── meio: tile do A/C + temperatura interna ──
        var tileX = PAD, tileY = 98, tileS = 88;
        roundRect(cv, tileX, tileY, tileX + tileS, tileY + tileS, 22,
            newPaint(acOn ? C_ACCENT_SOFT : C_SURFACE2, false, 0));
        roundRect(cv, tileX + 0.5, tileY + 0.5, tileX + tileS - 0.5, tileY + tileS - 0.5, 22,
            newPaint(acOn ? C_ACCENT_EDGE : C_BORDER, true, 1));
        drawSnowflake(cv, tileX + tileS / 2, tileY + tileS / 2, 21,
            acOn ? C_ACCENT : (s.on ? C_MUTED : C_FAINT));

        var tx = tileX + tileS + 24;
        var tempStr = (s.on && s.temp !== null) ? ("" + Math.round(s.temp)) : "--";
        var tempP   = textPaint(s.on ? C_FG : C_FAINT, 76, "sans-serif-medium", 0);
        text(cv, tempStr, tx, 170, tempP);
        if (s.on && s.temp !== null) {
            var tw = tempP.measureText.overload("java.lang.String").call(tempP, tempStr);
            text(cv, "°C", tx + tw + 6, 170,
                textPaint(C_MUTED, 34, "sans-serif", 0));
        }
        text(cv, "INTERNA", tx + 2, 198,
            textPaint(C_DIM, 15, "sans-serif-medium", 0.2));

        // ── rodapé: vento (ícone + 7 segmentos + valor) ──
        var cy = 262;                              // barras terminam em 268, PAD embaixo
        drawWind(cv, PAD, cy, s.on ? C_MUTED : C_FAINT);

        var fanStr = s.on ? ("" + s.fan) : "--";
        var fanP   = textPaint(s.on ? C_FG : C_FAINT, 24, "monospace", 0);
        var fanW   = fanP.measureText.overload("java.lang.String").call(fanP, fanStr);
        text(cv, fanStr, W - PAD - fanW, cy + 9, fanP);

        var barsL = PAD + 52, barsR = W - PAD - fanW - 16;
        var gap   = 5;
        var barW  = (barsR - barsL - gap * (FAN_MAX - 1)) / FAN_MAX;
        for (var i = 0; i < FAN_MAX; i++) {
            var bx   = barsL + i * (barW + gap);
            var fill = s.on && i < s.fan;
            roundRect(cv, bx, cy - 6, bx + barW, cy + 6, 3,
                newPaint(fill ? C_FG : C_SURFACE2, false, 0));
            roundRect(cv, bx + 0.5, cy - 5.5, bx + barW - 0.5, cy + 5.5, 3,
                newPaint(fill ? C_MUTED : C_BORDER, true, 1));
        }
        return bmp;
    }

    // ── ids de recurso (§3: campo estático de R$id, com fallback do arsc) ────
    var RID = null;
    try {
        RID = Java.use("com.beantechs.mediacenter.mainmodel1xos.R$id");
    } catch (e) {
        log("R$id indisponível, usando ids fixos: " + e);
    }
    var RID_FIXO = {
        online_music: 0x7f0a01ba,
        horizontalScrollView: 0x7f0a0107,
        online_music_container: 0x7f0a01bb
    };
    function resId(name) {
        try {
            if (RID !== null && RID[name] !== undefined) return RID[name].value;
        } catch (e) { /* cai no fixo */ }
        return RID_FIXO[name];
    }

    function viewOf(act, name) {
        var id = resId(name);
        if (!id) { log("id " + name + " desconhecido"); return null; }
        try {
            return Java.cast(act, ActivityCls).findViewById(id);
        } catch (e) {
            log("findViewById(" + name + ") err: " + e);
            return null;
        }
    }

    // ── estado desejado ─────────────────────────────────────────────────────
    var enabled  = false;
    var lastCtrl = null;        // marcador "ainda não li"
    var lastSig  = null;        // snapshot já pintado
    var origList  = null;       // CPs de fábrica, para restaurar no "off"
    var origRowTop = null;      // topMargin original da fileira, para restaurar no "off"

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

    // Integer[] no Frida é array JS de wrappers Integer (§7.2): montar via
    // java.lang.reflect.Array devolve wrapper de Object e o marshaller rejeita.
    function buildArr(list) {
        var out = [];
        for (var i = 0; i < list.length; i++) out.push(Integer.valueOf(list[i]));
        return out;
    }

    /** Lista de CPs que a tela deve enxergar: vazia com o card ligado. */
    function wantedList() {
        return enabled ? [] : (origList === null ? [] : origList);
    }

    /**
     * Captura a lista de fábrica uma única vez, antes de qualquer sobrescrita.
     *
     * Se o injetor for reiniciado dentro de um processo que JÁ tinha a fileira
     * zerada por uma injeção anterior, a captura vem vazia e o "off" não teria o
     * que restaurar. Nesse caso caímos na lista conhecida do Brasil
     * (persist.bean.country.code=17 -> [551 Deezer], handoff §9).
     */
    var DEFAULT_CPS = [551];
    function captureOriginal() {
        if (origList !== null) return;
        try {
            var inst = Java.use("com.beantechs.mediacenter.mediacentermodel.MediaCenterManager")
                .getInstance();
            if (inst === null) return;               // serviço ainda não subiu; tenta no próximo tick
            var cur = inst.getCPList();
            var acc = [];
            if (cur !== null) for (var i = 0; i < cur.length; i++) acc.push(parseInt("" + cur[i], 10));
            if (acc.length === 0) {
                origList = DEFAULT_CPS.slice();
                log("CPs de fábrica vieram vazios (reinjeção?) — usando [" + origList.join(",") + "]");
            } else {
                origList = acc;
                log("CPs de fábrica = [" + acc.join(",") + "]");
            }
        } catch (e) {
            log("captureOriginal err: " + e);
        }
    }

    // ── hooks da cadeia da lista de CPs ─────────────────────────────────────
    var CHAIN = [
        ["com.beantechs.mediacenter.mediacentermodel.MediaCenterManager", "getCPList"],
        ["com.beantechs.mediacenter.mediacentermodel.core.OnlineOsImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getMCpList"]
    ];
    CHAIN.forEach(function (pair) {
        var cls = pair[0], name = pair[1];
        try {
            var w = Java.use(cls);
            if (w[name] === undefined) { log("SEM método " + cls + "." + name); return; }
            w[name].implementation = function () {
                if (!enabled) return this[name]();
                try { return buildArr([]); }
                catch (e) { log("buildArr err: " + e); return this[name](); }
            };
            log("hook OK " + cls.split(".").pop() + "." + name);
        } catch (e) {
            log("hook FALHOU " + cls + "." + name + ": " + e);
        }
    });

    /** Escreve a lista direto nas instâncias vivas (argumento, não retorno — §7.2). */
    function applyToInstances() {
        var n = 0;
        try {
            var arr = buildArr(wantedList());
            Java.choose(IMPL_CLS, {
                onMatch: function (inst) {
                    try { inst.setMCpList(arr); n++; }
                    catch (e) { log("setMCpList err: " + e); }
                },
                onComplete: function () {}
            });
        } catch (e) {
            log("applyToInstances err: " + e);
        }
        log("setMCpList aplicado em " + n + " instância(s)");
        return n;
    }

    // ── clique: reusa o dispatch por elevation do OEM (handoff §5.3) ─────────
    function openOurApp() {
        try {
            var ctx = Java.use("android.app.ActivityThread")
                .currentApplication().getApplicationContext();
            var i = ctx.getPackageManager().getLaunchIntentForPackage(OUR_PKG);
            if (i === null) { log("app " + OUR_PKG + " não instalado"); return false; }
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i);
            log("card clicado -> abrindo " + OUR_PKG);
            return true;
        } catch (e) {
            log("openOurApp err: " + e);
            return false;
        }
    }

    try {
        Java.use(IMPL_CLS).skipApp4OnlineOs.implementation = function (cp) {
            if (parseInt("" + cp, 10) === CP_CARD && openOurApp()) return;
            return this.skipApp4OnlineOs(cp);
        };
        log("hook OK skipApp4OnlineOs (clique do card)");
    } catch (e) {
        log("hook FALHOU skipApp4OnlineOs: " + e);
    }

    /**
     * Hook no proprio loadOnlineMusicCard: ele roda no onCreate e monta a fileira
     * do OEM. Trocando o conteudo AQUI, dentro da mesma chamada na main thread, o
     * card entra no mesmo frame -- sem a janela de ate 1,5s em que o container
     * ficava vazio (ou com os icones do OEM) esperando o proximo tick.
     *
     * `this` de hook e referencia JNI LOCAL (handoff 7.4): usar no mesmo tick e
     * correto, guardar entre ticks aborta o processo. Aqui nao guardamos nada.
     */
    try {
        Java.use(ACT).loadOnlineMusicCard.implementation = function () {
            this.loadOnlineMusicCard();
            if (!enabled) return;
            try {
                paintCard(this, readState());
            } catch (e) {
                logOnce("onload:" + e, "paint no loadOnlineMusicCard err: " + e);
            }
        };
        log("hook OK loadOnlineMusicCard (card no mesmo frame do onCreate)");
    } catch (e) {
        log("hook FALHOU loadOnlineMusicCard: " + e);
    }

    // ── montagem da view ────────────────────────────────────────────────────
    /**
     * Cria (ou reaproveita) o ImageView do card no online_music_container e
     * pinta o bitmap. Roda SEMPRE na main thread. A view é achada por tag —
     * guardar o wrapper entre ticks derruba o processo (§7.4).
     */
    function paintCard(act, s) {
        try {
            var boxRaw = viewOf(act, "online_music_container");
            if (boxRaw === null) { logOnce("nobox", "container não está inflado"); return; }

            var tagStr = Str.$new(CARD_TAG);
            var boxV   = Java.cast(boxRaw, ViewCls);
            var found  = boxV.findViewWithTag.overload("java.lang.Object").call(boxV, tagStr);

            var iv;
            if (found === null) {
                iv = ImageView.$new.overload("android.content.Context").call(ImageView, act);
                var ivV = Java.cast(iv, ViewCls);
                ivV.setTag.overload("java.lang.Object").call(ivV, tagStr);
                // elevation = CP virtual: é assim que o onClick do OEM identifica a view
                ivV.setElevation(CP_CARD);
                ivV.setOnClickListener(Java.cast(act, Java.use("android.view.View$OnClickListener")));
                var lp = LLParams.$new.overload("int", "int").call(LLParams, W, H);
                ivV.setLayoutParams(Java.cast(lp, VGLayoutParams));

                var box = Java.cast(boxRaw, ViewGroupCls);
                box.removeAllViews();
                box.addView.overload("android.view.View").call(box, ivV);
                log("card criado no container");
                lastSig = null;                    // força a primeira pintura
            } else {
                iv = Java.cast(found, ImageView);
            }

            var sig = stateSig(s);
            if (sig !== lastSig) {
                iv.setImageBitmap(drawCard(s));
                lastSig = sig;
                log("card pintado: " + sig);
            }

            setTitleVisible(act, false);
            setRowTop(act, ROW_TOP);
        } catch (e) {
            logOnce("paint:" + e, "paintCard err: " + e + (e.stack ? " | " + e.stack : ""));
        }
    }

    /** Mostra/esconde o título "Mídia online" do bloco. */
    function setTitleVisible(act, visible) {
        try {
            var raw = viewOf(act, "online_music");
            if (raw === null) return;
            var v = Java.cast(raw, ViewCls);
            var want = visible ? 0 : 8;            // VISIBLE : GONE
            if (v.getVisibility() !== want) v.setVisibility(want);
        } catch (e) {
            logOnce("title:" + e, "setTitleVisible err: " + e);
        }
    }

    /**
     * Reescreve o topMargin da fileira. Esconder o título não sobe nada sozinho:
     * a fileira é ancorada no topo do PAI com margem fixa, sem encadeamento com
     * o título (handoff §2). Sem isto o card de 300 bateria em "Mídia local"
     * (168 + 300 = 468 > 430).
     */
    function setRowTop(act, top) {
        try {
            var raw = viewOf(act, "horizontalScrollView");
            if (raw === null) return;
            var v   = Java.cast(raw, ViewCls);
            var lp  = v.getLayoutParams();
            if (lp === null) return;
            var mlp = Java.cast(lp, MarginLP);
            if (origRowTop === null) {
                origRowTop = mlp.topMargin.value;
                log("topMargin original da fileira = " + origRowTop);
            }
            var prev = mlp.topMargin.value;
            if (prev === top) return;
            mlp.topMargin.value = top;
            v.setLayoutParams(Java.cast(mlp, VGLayoutParams));
            log("topMargin da fileira: " + prev + " -> " + top);
        } catch (e) {
            logOnce("rowtop:" + e, "setRowTop err: " + e);
        }
    }

    /** Remove o card e devolve título e fileira ao estado de fábrica. */
    function restore(act) {
        try {
            var boxRaw = viewOf(act, "online_music_container");
            if (boxRaw !== null) {
                var boxV  = Java.cast(boxRaw, ViewCls);
                var found = boxV.findViewWithTag.overload("java.lang.Object")
                    .call(boxV, Str.$new(CARD_TAG));
                if (found !== null) {
                    Java.cast(boxRaw, ViewGroupCls)
                        .removeView.overload("android.view.View")
                        .call(Java.cast(boxRaw, ViewGroupCls), Java.cast(found, ViewCls));
                }
            }
            if (origRowTop !== null) setRowTop(act, origRowTop);
            setTitleVisible(act, true);
            act.loadOnlineMusicCard();       // redesenha os ícones de fábrica
            log("fileira original restaurada");
        } catch (e) {
            log("restore err: " + e);
        }
    }

    /** Acha a Activity viva e roda `fn` na main thread. Sem cache entre ticks (§7.4). */
    function withActivity(fn) {
        var act = null;
        try {
            Java.choose(ACT, {
                onMatch: function (inst) { act = inst; return "stop"; },
                onComplete: function () {}
            });
        } catch (e) {
            logOnce("choose:" + e, "choose err: " + e);
            return;
        }
        if (act === null) return;            // tela fechada: vale no próximo onCreate
        Java.scheduleOnMainThread(function () {
            try { fn(act); }
            catch (e) { logOnce("main:" + e, "main thread err (activity morta?): " + e); }
        });
    }

    // ── loop ────────────────────────────────────────────────────────────────
    function tick() {
        Java.perform(function () {
            captureOriginal();
            var want = readCtrl();
            var changed = (want !== lastCtrl);
            if (changed) {
                lastCtrl = want;
                enabled  = want;
                lastSig  = null;
                log("card na home = " + (want ? "ON" : "OFF"));
                applyToInstances();
            }
            if (enabled) {
                var s = readState();
                withActivity(function (act) {
                    // loadOnlineMusicCard roda no onCreate e faz removeAllViews:
                    // se a Activity foi recriada, paintCard recria o card sozinho.
                    paintCard(act, s);
                });
            } else if (changed) {
                withActivity(restore);
            }
        });
    }

    // Primeiro ciclo IMEDIATO: esperar o primeiro setInterval deixava a fileira do
    // OEM na tela por 1,5s a mais depois de a injecao ja estar de pe.
    tick();
    setInterval(tick, 1500);

    // ── autoteste (§7.5): falha de helper aparece na injeção, não no carro ──
    (function selfTest() {
        var falhas = [];
        [["buildArr",   function () { return buildArr([551]).length === 1; }],
         ["wantedList", function () { return wantedList().length >= 0; }],
         ["resId",      function () { return resId("online_music") > 0; }],
         ["readState",  function () { return readState() !== null; }],
         ["stateSig",   function () { return stateSig(readState()).length > 0; }],
         ["drawCard",   function () { return drawCard(readState()) !== null; }],
         ["readCtrl",   function () { return typeof readCtrl() === "boolean"; }],
         // teto rígido: 430 (topo do título "Mídia local") - ROW_TOP
         ["altura",     function () { return ROW_TOP + H <= 430; }]
        ].forEach(function (par) {
            try {
                if (par[1]() !== true) falhas.push(par[0] + " (resultado inesperado)");
            } catch (e) {
                falhas.push(par[0] + ": " + e);
            }
        });
        if (falhas.length === 0) log("autoteste dos helpers: OK");
        else log("autoteste dos helpers FALHOU -> " + falhas.join(" | "));
    })();

    log("card de clima na home ativo (alvo com.beantechs.mediacenter)");
});
