/*
 * Card de clima na Home da MediaCenter (com.beantechs.mediacenter).
 *
 * Substitui a fileira de mídia online da tela principal por um card próprio com
 * o estado do ar-condicionado: A/C ligado, temperatura interna e velocidade do
 * vento. Fundo igual aos cards de mídia local do OEM (degradê a 135 graus,
 * translúcido, medido no APK — ver preview/home-card-fundo-oem.html), tipografia
 * e accent #22C55E do HMI do app, desenhado num Bitmap por Canvas — o APK do OEM não aceita
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
 *
 * ── segundo card: ciclo automático do haval-ev-manager ──────────────────────
 * Este agente é o ÚNICO no processo da MediaCenter. O haval-ev-manager já tentou
 * ter o seu (v1.7.0 dele) e derrubou o ART em campo: SIGSEGV em MterpInvokeVirtual
 * com dois agentes hookando os mesmos ArtMethod e dois watchdogs fazendo pkill +
 * reinjetar a cada 10s. Por isso o card dele é desenhado AQUI, e a única coisa que
 * vem de lá é uma linha de texto em /data/local/tmp/haval_ev_home_card:
 *
 *   conn=1;auto=1;phase=80;soc=62;model=0;ts=1761300000
 *
 * O arquivo é o contrato entre os dois apps — mexer no formato exige mexer em
 * HomeCardState.java do haval-ev-manager. Linha ausente, ilegível ou com `ts` mais
 * velho que EV_MAX_AGE segundos => o card do ciclo some e fica só o de clima.
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

    // card do ciclo automático (dados do haval-ev-manager)
    var EV_STATE    = "/data/local/tmp/haval_ev_home_card";
    var EV_PKG      = "br.com.redesurftank.havalevmanager";
    var EV_CARD_TAG = "hcc-ev-card";
    var CP_EV       = 591;       // segundo CP virtual, também dentro de 501..600
    var EV_MAX_AGE  = 90;        // segundos: linha mais velha que isto é descartada
    var GAP         = 24;        // espaço entre os dois cards

    // ── o processo e o certo? ───────────────────────────────────────────────
    // O OEM tem tres pacotes com prefixo com.beantechs.mediacenter (.h5.core e
    // .h5.ui sao os outros dois) e as classes do card vivem SO no primeiro. Cair
    // num dos outros deixava todos os hooks em ClassNotFoundException e o tick
    // repetindo o erro para sempre (860 linhas em 45min no log de 2026-09-02),
    // enquanto o app via o injetor vivo e dava a injecao por boa. Agora avisa e
    // desiste: o watchdog le esta linha e reinjeta no processo certo.
    try {
        Java.use(ACT);
    } catch (e) {
        log("ALVO ERRADO: " + ACT + " nao existe neste processo (h5.core/h5.ui?)"
            + " — nada a fazer aqui: " + e);
        return;
    }

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
    var PathCls   = Java.use("android.graphics.Path");
    var Typeface  = Java.use("android.graphics.Typeface");
    var LinGrad   = Java.use("android.graphics.LinearGradient");
    var TileMode  = Java.use("android.graphics.Shader$TileMode");

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
    var C_SURFACE     = 0xFF141414 | 0;   // legado — o fundo agora e o degrade do OEM
    // Fundo dos cards de midia local da MediaCenter, medido pixel a pixel em
    // drawable-night/main_local_media_card_common_bg_nor.webp (210x197 esticado
    // para 214x192dp): degrade a 135 graus, translucido, com borda de 1px que
    // segue o mesmo eixo. Alpha 0xD1 (0,82) no preenchimento e 0xDC (0,86) na
    // borda — a translucidez deixa o main_bg.webp da home aparecer, igual aos
    // cards do OEM. Ver preview/home-card-fundo-oem.html.
    var G_BG_COLORS   = [0xD12D2F33 | 0, 0xD1242627 | 0, 0xD1212325 | 0, 0xD11E2021 | 0];
    var G_BG_STOPS    = [0.0, 0.22, 0.52, 1.0];
    var G_EDGE_COLORS = [0xDC535557 | 0, 0xDC4B4D4F | 0, 0xDC3C3E40 | 0, 0xDC2C2D31 | 0];
    var G_EDGE_STOPS  = [0.0, 0.22, 0.55, 1.0];
    var C_SURFACE2    = 0xFF1C1C1C | 0;
    var C_FG          = 0xFFFAFAFA | 0;
    var C_MUTED       = 0xFFA3A3A3 | 0;
    var C_DIM         = 0xFF6B6B6B | 0;
    var C_FAINT       = 0xFF404040 | 0;
    var C_ACCENT      = 0xFF22C55E | 0;
    var C_ACCENT_SOFT = 0x1F22C55E | 0;
    var C_ACCENT_EDGE = 0x6622C55E | 0;
    var C_BORDER      = 0x1AFFFFFF | 0;
    // âmbar da fase USO e vermelho da bateria no fundo — mesmos tokens do HMI do
    // haval-ev-manager, para os dois cards lerem como um sistema só.
    var C_AMBER       = 0xFFF59E0B | 0;
    var C_AMBER_SOFT  = 0x1FF59E0B | 0;
    var C_AMBER_EDGE  = 0x66F59E0B | 0;
    var C_RED         = 0xFFEF4444 | 0;

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

    // ── estado do ciclo automático (vem do haval-ev-manager) ────────────────

    /**
     * Lê a primeira linha de um arquivo de texto, ou null.
     *
     * Pelo File nativo do Frida (16.2+), sem objetos Java: o tick roda na thread
     * do agente, e cada File/FileReader/BufferedReader dali era lixo de heap a mais
     * para o GC varrer junto com os handles dessa thread (ver withActivity).
     */
    function readLine(path) {
        if (typeof File !== "undefined" && typeof File.readAllText === "function") {
            try {
                var txt = File.readAllText(path);
                var nl = txt.indexOf("\n");
                return (nl < 0 ? txt : txt.substring(0, nl)).trim();
            } catch (e) {
                // arquivo ausente é normal (ev-manager não instalado, card desligado)
                logOnce("readLine:" + path, "readLine(" + path + ") sem leitura: " + e);
                return null;
            }
        }
        try {
            var f = JFile.$new(path);
            if (!f.exists()) return null;
            var br = BufReader.$new(FileReader.$new(f));
            var l  = br.readLine();
            br.close();
            return (l === null) ? null : ("" + l).trim();
        } catch (e) {
            logOnce("readLine:" + path, "readLine(" + path + ") err: " + e);
            return null;
        }
    }

    /** "a=1;b=2" -> {a:"1", b:"2"} */
    function parseKv(line) {
        var out = {};
        line.split(";").forEach(function (part) {
            var i = part.indexOf("=");
            if (i > 0) out[part.substring(0, i).trim()] = part.substring(i + 1).trim();
        });
        return out;
    }

    function intOrNull(v) {
        if (v === undefined || v === null || v === "" || v === "--") return null;
        var n = parseInt(v, 10);
        return isNaN(n) ? null : n;
    }

    /**
     * Snapshot do ciclo, ou null quando o card não deve aparecer: arquivo ausente,
     * linha ilegível ou `ts` vencido (serviço do outro app morto/parado — melhor
     * sumir o card do que deixar um número velho na Home).
     */
    function readEvState() {
        var line = readLine(EV_STATE);
        if (line === null || line === "") return null;
        var kv = parseKv(line);
        var ts = intOrNull(kv.ts);
        if (ts === null) return null;
        var age = Math.floor(Date.now() / 1000) - ts;
        // age negativo = relógio do sistema andou para trás no boot; não descarta.
        if (age > EV_MAX_AGE) {
            logOnce("evstale", "estado do ciclo vencido (" + age + "s) — card do ciclo oculto");
            return null;
        }
        var phase = intOrNull(kv.phase);
        return {
            conn:  kv.conn === "1",
            auto:  kv.auto === "1",
            phase: (phase === 80 || phase === 20) ? phase : null,
            soc:   intOrNull(kv.soc),
            model: intOrNull(kv.model)
        };
    }

    function evSig(e) {
        if (e === null) return "none";
        return [e.conn, e.auto, e.phase, e.soc, e.model].join("|");
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

    /** Paint com LinearGradient no eixo (0,0)->(W,H) — o mesmo do fundo do OEM. */
    function gradPaint(colors, stops, strokeW) {
        var p = Paint.$new.overload().call(Paint);
        p.setAntiAlias(true);
        p.setStyle(strokeW ? PStyle.STROKE.value : PStyle.FILL.value);
        if (strokeW) p.setStrokeWidth(strokeW);
        p.setShader(LinGrad.$new.overload("float", "float", "float", "float",
            "[I", "[F", "android.graphics.Shader$TileMode")
            .call(LinGrad, 0.0, 0.0, W, H,
                Java.array("int", colors), Java.array("float", stops),
                TileMode.CLAMP.value));
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

    /** Raio: polígono fechado preenchido, no box r x r centrado em (cx,cy). */
    function drawBolt(cv, cx, cy, r, color) {
        // mesma silhueta do ícone do preview, em coordenadas 0..24 reescaladas
        var pts = [[13,2],[4.5,13.5],[11,13.5],[10,22],[18.5,10.5],[12,10.5]];
        var p   = PathCls.$new();
        var k   = r / 24.0;
        pts.forEach(function (pt, i) {
            var x = cx + (pt[0] - 12) * k;
            var y = cy + (pt[1] - 12) * k;
            if (i === 0) p.moveTo(x, y); else p.lineTo(x, y);
        });
        p.close();
        cv.drawPath.overload("android.graphics.Path", "android.graphics.Paint")
            .call(cv, p, newPaint(color, false, 0));
    }

    /** Pilha com o nível preenchido (0..100), centrada em (cx,cy). */
    function drawBattery(cv, cx, cy, r, pct, color) {
        var w = r * 1.15, h = r * 0.80;
        var l = cx - w / 2, t = cy - h / 2;
        roundRect(cv, l + 1, t + 1, l + w * 0.82 - 1, t + h - 1, 5, newPaint(color, true, 2.2));
        roundRect(cv, l + w * 0.86, cy - h * 0.16, l + w, cy + h * 0.16, 2,
            newPaint(color, false, 0));
        var inner = (w * 0.82 - 10) * Math.max(0, Math.min(100, pct)) / 100.0;
        if (inner > 0)
            roundRect(cv, l + 5, t + 5, l + 5 + inner, t + h - 5, 2.5, newPaint(color, false, 0));
    }

    /** Pausa: duas barras. Fase indefinida (ciclo desligado / sem conexão). */
    function drawPause(cv, cx, cy, r, color) {
        var p = newPaint(color, true, 2.4);
        line(cv, cx - r * 0.22, cy - r * 0.42, cx - r * 0.22, cy + r * 0.42, p);
        line(cv, cx + r * 0.22, cy - r * 0.42, cx + r * 0.22, cy + r * 0.42, p);
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

        // fundo + borda — degrade do OEM (mesmo eixo nos dois), raio 24 nosso
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_BG_COLORS, G_BG_STOPS, 0));
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_EDGE_COLORS, G_EDGE_STOPS, 1));

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

    /**
     * Card do ciclo automático, 536x300, com a mesma moldura do card de clima.
     * Só dados do ciclo — bateria é a variável que o algoritmo observa, não um
     * medidor genérico; autonomia e odômetro ficam de fora de propósito.
     *
     * Layout (preview aprovado: haval-ev-manager/preview/auto-cycle-card-preview.html):
     *   cabeçalho  baseline 52      "CICLO AUTO" + selo ATIVO/OFF/OFFLINE
     *   tile       y 98, 88x88      raio na fase CARGA, pilha na USO, pausa parado
     *   bateria    baseline 170     número grande + "%", rótulo BATERIA em 198
     *   pastilha   y 100..134       CARGA (verde) / USO (âmbar), alinhada à direita
     *   modo       baseline 166     "MODO HEV" / "MODO EV" — o power_model da fase
     *   barra      y 232..246       nível + traço branco no gatilho da virada
     *   escala     baseline 272     0 · meta e quanto falta · 100
     */
    function drawEvCard(e) {
        var bmp = Bitmap.createBitmap.overload("int", "int", "android.graphics.Bitmap$Config")
            .call(Bitmap, W, H, BmpConfig.ARGB_8888.value);
        var cv = Canvas.$new.overload("android.graphics.Bitmap").call(Canvas, bmp);

        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_BG_COLORS, G_BG_STOPS, 0));
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_EDGE_COLORS, G_EDGE_STOPS, 1));

        // "vivo" = conectado E ciclo ligado: só aí a fase significa alguma coisa
        var live    = e.conn && e.auto;
        var phase   = live ? e.phase : null;            // 80 = CARGA, 20 = USO
        var charge  = phase === 80;
        var use     = phase === 20;
        var hue     = charge ? C_ACCENT : use ? C_AMBER : C_FAINT;
        var hueSoft = charge ? C_ACCENT_SOFT : use ? C_AMBER_SOFT : C_SURFACE2;
        var hueEdge = charge ? C_ACCENT_EDGE : use ? C_AMBER_EDGE : C_BORDER;
        var soc     = e.conn ? e.soc : null;

        // ── cabeçalho ──
        text(cv, "CICLO AUTO", PAD, 52, textPaint(C_DIM, 17, "sans-serif-medium", 0.18));

        var badge  = !e.conn ? "OFFLINE" : e.auto ? "ATIVO" : "OFF";
        var badgeP = textPaint(live ? C_ACCENT : C_FAINT, 16, "monospace", 0.06);
        var badgeW = badgeP.measureText.overload("java.lang.String").call(badgeP, badge);
        text(cv, badge, W - PAD - badgeW, 52, badgeP);
        circle(cv, W - PAD - badgeW - 16, 46, 5, newPaint(live ? C_ACCENT : C_FAINT, false, 0));

        // ── tile com o ícone da fase ──
        var tileX = PAD, tileY = 98, tileS = 88;
        var tcx = tileX + tileS / 2, tcy = tileY + tileS / 2;
        roundRect(cv, tileX, tileY, tileX + tileS, tileY + tileS, 22, newPaint(hueSoft, false, 0));
        roundRect(cv, tileX + 0.5, tileY + 0.5, tileX + tileS - 0.5, tileY + tileS - 0.5, 22,
            newPaint(hueEdge, true, 1));
        if (charge)      drawBolt(cv, tcx, tcy, 44, hue);
        else if (use)    drawBattery(cv, tcx, tcy, 46, soc === null ? 0 : soc, hue);
        else             drawPause(cv, tcx, tcy, 44, C_FAINT);

        // ── bateria ──
        var bx     = tileX + tileS + 24;
        var socStr = (soc === null) ? "--" : ("" + soc);
        var socP   = textPaint(soc === null ? C_FAINT : C_FG, 76, "sans-serif-medium", 0);
        text(cv, socStr, bx, 170, socP);
        if (soc !== null) {
            var sw = socP.measureText.overload("java.lang.String").call(socP, socStr);
            text(cv, "%", bx + sw + 6, 170, textPaint(C_MUTED, 34, "sans-serif", 0));
        }
        text(cv, "BATERIA", bx + 2, 198, textPaint(C_DIM, 15, "sans-serif-medium", 0.2));

        // ── pastilha da fase + modo imposto, alinhadas à direita ──
        var pillTxt = charge ? "CARGA" : use ? "USO" : "--";
        var pillP   = textPaint(live ? hue : C_FAINT, 19, "monospace", 0.1);
        var pillTW  = pillP.measureText.overload("java.lang.String").call(pillP, pillTxt);
        var pillR   = W - PAD, pillL = pillR - pillTW - 32;
        roundRect(cv, pillL, 100, pillR, 134, 14, newPaint(hueSoft, false, 0));
        roundRect(cv, pillL + 0.5, 100.5, pillR - 0.5, 133.5, 14, newPaint(hueEdge, true, 1));
        text(cv, pillTxt, pillL + 16, 124, pillP);

        // power_model que a fase impõe: CARGA = HEV (motor recarrega), USO = EV.
        // Com o ciclo ligado a fase manda; desligado, mostra o que o carro reporta.
        var modeTxt = live ? (charge ? "HEV" : use ? "EV" : "--")
                    : (e.model === 0 ? "HEV" : e.model === 1 ? "EV" : "--");
        var modeP   = textPaint(live ? C_MUTED : C_FAINT, 17, "monospace", 0.06);
        var modeW   = modeP.measureText.overload("java.lang.String").call(modeP, modeTxt);
        var lblP    = textPaint(C_DIM, 13, "sans-serif-medium", 0.1);
        var lblW    = lblP.measureText.overload("java.lang.String").call(lblP, "MODO");
        text(cv, modeTxt, W - PAD - modeW, 166, modeP);
        text(cv, "MODO", W - PAD - modeW - 8 - lblW, 166, lblP);

        // ── barra: nível + traço no gatilho da próxima virada ──
        var trL = PAD, trR = W - PAD, trT = 232, trB = 246;
        roundRect(cv, trL, trT, trR, trB, 7, newPaint(C_SURFACE2, false, 0));
        roundRect(cv, trL + 0.5, trT + 0.5, trR - 0.5, trB - 0.5, 7, newPaint(C_BORDER, true, 1));
        if (soc !== null && soc > 0) {
            var fw = (trR - trL) * Math.max(0, Math.min(100, soc)) / 100.0;
            var fillC = !live ? C_MUTED : charge ? C_ACCENT : (soc <= 20 ? C_RED : C_AMBER);
            // largura mínima de 14 = o raio do roundRect; abaixo disso ele some
            roundRect(cv, trL, trT, trL + Math.max(14, fw), trB, 7, newPaint(fillC, false, 0));
        }

        // gatilho: na CARGA vira em >=75, no USO vira em <=20 (ver CLAUDE.md do ev-manager)
        var gate = charge ? 75 : use ? 20 : null;
        if (gate !== null) {
            var gx = trL + (trR - trL) * gate / 100.0;
            roundRect(cv, gx - 1.5, trT - 5, gx + 1.5, trB + 5, 2, newPaint(C_FG, false, 0));
        }

        // ── escala ──
        var scaleP = textPaint(C_DIM, 13, "monospace", 0.06);
        text(cv, "0", trL, 272, scaleP);
        var hundredW = scaleP.measureText.overload("java.lang.String").call(scaleP, "100");
        text(cv, "100", trR - hundredW, 272, scaleP);

        var center;
        if (!e.conn)            center = "SEM CONEXAO";
        else if (!e.auto)       center = "CICLO DESLIGADO";
        else if (gate === null) center = "AGUARDANDO SOC TARGET";
        else if (soc === null)  center = "META " + phase + "% · aguardando bateria";
        else {
            var falta = charge ? Math.max(0, gate - soc) : Math.max(0, soc - gate);
            center = (falta === 0)
                ? "VIRANDO DE FASE…"
                : "META " + phase + "% · vira em " + (charge ? ">=" : "<=") + gate
                  + "% (faltam " + falta + ")";
        }
        var cenP = textPaint(C_MUTED, 13, "monospace", 0.06);
        var cenW = cenP.measureText.overload("java.lang.String").call(cenP, center);
        text(cv, center, (W - cenW) / 2, 272, cenP);

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
    var lastSig  = null;        // snapshot do clima já pintado
    var lastEvSig = null;       // snapshot do ciclo já pintado
    var origList  = null;       // CPs de fábrica, para restaurar no "off"
    var origRowTop = null;      // topMargin original da fileira, para restaurar no "off"
    var actRef     = null;      // MediaCenterActivity viva, referência GLOBAL (ver withActivity)
    var choseAt    = 0;         // último Java.choose da Activity (0 = nunca)

    function readCtrl() {
        var l = readLine(CTRL);
        return (l !== null) && (l.toLowerCase() === "on");
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
    function openApp(pkg) {
        try {
            var ctx = Java.use("android.app.ActivityThread")
                .currentApplication().getApplicationContext();
            var i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i === null) { log("app " + pkg + " não instalado"); return false; }
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i);
            log("card clicado -> abrindo " + pkg);
            return true;
        } catch (e) {
            log("openApp(" + pkg + ") err: " + e);
            return false;
        }
    }

    try {
        Java.use(IMPL_CLS).skipApp4OnlineOs.implementation = function (cp) {
            var n = parseInt("" + cp, 10);
            if (n === CP_CARD && openApp(OUR_PKG)) return;
            if (n === CP_EV   && openApp(EV_PKG))  return;
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
     * `this` de hook e referencia JNI LOCAL (handoff 7.4): guardar o wrapper cru
     * entre ticks aborta o processo. Por isso o que fica guardado e o Java.retain
     * dele (referencia GLOBAL) -- e esse e o caminho normal pelo qual o tick acha a
     * Activity, ja que todo onCreate passa por aqui.
     */
    var loadHookOk = false;
    try {
        Java.use(ACT).loadOnlineMusicCard.implementation = function () {
            this.loadOnlineMusicCard();
            actRef = Java.retain(this);
            if (!enabled) return;
            try {
                paintCards(this, readState(), readEvState());
            } catch (e) {
                logOnce("onload:" + e, "paint no loadOnlineMusicCard err: " + e);
            }
        };
        loadHookOk = true;
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
    function findByTag(boxRaw, tag) {
        var boxV = Java.cast(boxRaw, ViewCls);
        return boxV.findViewWithTag.overload("java.lang.Object").call(boxV, Str.$new(tag));
    }

    /**
     * Cria o ImageView de um card e o pendura no container.
     *
     * `clearFirst` só no card de clima, que é o dono da fileira: ele limpa os
     * ícones do OEM e define a ordem — clima primeiro, ciclo à direita com GAP de
     * margem. Se a Activity for recriada, loadOnlineMusicCard refaz a fileira e os
     * dois voltam na mesma ordem no tick seguinte.
     */
    function makeCard(act, boxRaw, tag, cp, clearFirst) {
        var iv  = ImageView.$new.overload("android.content.Context").call(ImageView, act);
        var ivV = Java.cast(iv, ViewCls);
        ivV.setTag.overload("java.lang.Object").call(ivV, Str.$new(tag));
        // elevation = CP virtual: é assim que o onClick do OEM identifica a view
        ivV.setElevation(cp);
        ivV.setOnClickListener(Java.cast(act, Java.use("android.view.View$OnClickListener")));

        var lp = LLParams.$new.overload("int", "int").call(LLParams, W, H);
        // leftMargin é declarado em MarginLayoutParams: via LLParams o wrapper do
        // Frida pode não expor o campo herdado, por isso o cast explícito.
        if (!clearFirst) Java.cast(lp, MarginLP).leftMargin.value = GAP;
        ivV.setLayoutParams(Java.cast(lp, VGLayoutParams));

        var box = Java.cast(boxRaw, ViewGroupCls);
        if (clearFirst) box.removeAllViews();
        box.addView.overload("android.view.View").call(box, ivV);
        log("card " + tag + " criado no container");
        return iv;
    }

    function removeByTag(boxRaw, tag) {
        var found = findByTag(boxRaw, tag);
        if (found === null) return false;
        var box = Java.cast(boxRaw, ViewGroupCls);
        box.removeView.overload("android.view.View").call(box, Java.cast(found, ViewCls));
        return true;
    }

    /**
     * Pinta o card de clima e, quando há estado fresco do haval-ev-manager, o card
     * do ciclo automático ao lado. `e === null` tira o card do ciclo da fileira e
     * deixa só o de clima.
     */
    function paintCards(act, s, e) {
        try {
            var boxRaw = viewOf(act, "online_music_container");
            if (boxRaw === null) { logOnce("nobox", "container não está inflado"); return; }

            // ── card de clima (dono da fileira) ──
            var found = findByTag(boxRaw, CARD_TAG);
            var iv;
            if (found === null) {
                iv = makeCard(act, boxRaw, CARD_TAG, CP_CARD, true);
                lastSig   = null;      // força a primeira pintura
                lastEvSig = null;      // o removeAllViews levou junto o card do ciclo
            } else {
                iv = Java.cast(found, ImageView);
            }
            var sig = stateSig(s);
            if (sig !== lastSig) {
                iv.setImageBitmap(drawCard(s));
                lastSig = sig;
                log("card de clima pintado: " + sig);
            }

            // ── card do ciclo (dados do app vizinho) ──
            var evFound = findByTag(boxRaw, EV_CARD_TAG);
            if (e === null) {
                if (removeByTag(boxRaw, EV_CARD_TAG)) log("card do ciclo removido (sem estado)");
                lastEvSig = null;
            } else {
                var evIv;
                if (evFound === null) {
                    evIv = makeCard(act, boxRaw, EV_CARD_TAG, CP_EV, false);
                    lastEvSig = null;
                } else {
                    evIv = Java.cast(evFound, ImageView);
                }
                var es = evSig(e);
                if (es !== lastEvSig) {
                    evIv.setImageBitmap(drawEvCard(e));
                    lastEvSig = es;
                    log("card do ciclo pintado: " + es);
                }
            }

            setTitleVisible(act, false);
            setRowTop(act, ROW_TOP);
        } catch (err) {
            logOnce("paint:" + err, "paintCards err: " + err + (err.stack ? " | " + err.stack : ""));
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
                removeByTag(boxRaw, CARD_TAG);
                removeByTag(boxRaw, EV_CARD_TAG);
            }
            lastEvSig = null;
            if (origRowTop !== null) setRowTop(act, origRowTop);
            setTitleVisible(act, true);
            act.loadOnlineMusicCard();       // redesenha os ícones de fábrica
            log("fileira original restaurada");
        } catch (e) {
            log("restore err: " + e);
        }
    }

    /**
     * Procura no heap uma MediaCenterActivity ainda não destruída. Instância de
     * Java.choose já é referência GLOBAL — pode ficar guardada entre ticks (o agente
     * do SystemUI guarda as views dele assim desde sempre).
     */
    function chooseActivity() {
        try {
            Java.choose(ACT, {
                onMatch: function (inst) {
                    // isDestroyed só lê um campo: seguro fora da main thread
                    if (Java.cast(inst, ActivityCls).isDestroyed()) return;
                    actRef = inst;
                    return "stop";
                },
                onComplete: function () {}
            });
        } catch (e) {
            logOnce("choose:" + e, "choose err: " + e);
        }
    }

    /**
     * Roda `fn` na main thread com a Activity viva.
     *
     * A Activity vem do hook de loadOnlineMusicCard (todo onCreate) e fica guardada
     * em `actRef`. Até a 1.30.0 isto fazia um Java.choose a CADA tick (40 varreduras
     * do heap por minuto, na thread do agente), e a MediaCenter abortava no GC:
     * "GC tried to mark invalid reference" em HandleScopeVisitRoots da thread do
     * Frida — 3 vezes em 2026-10-24, a primeira 3,5 min depois da injeção. O agente
     * do SystemUI, que faz choose uma vez só, nunca caiu assim. Agora o choose só
     * cobre a Activity que já existia antes da injeção (uma vez) — ou, se o hook
     * não pegou, é o único caminho, mas espaçado.
     */
    var CHOOSE_RETRY_MS = 30000;
    function withActivity(fn) {
        if (actRef === null) {
            var now = Date.now();
            if (choseAt === 0 || (!loadHookOk && now - choseAt >= CHOOSE_RETRY_MS)) {
                choseAt = now;
                chooseActivity();
            }
        }
        var act = actRef;
        if (act === null) return;            // tela fechada: vale no próximo onCreate
        Java.scheduleOnMainThread(function () {
            try {
                if (Java.cast(act, ActivityCls).isDestroyed()) {
                    // a próxima chega pelo hook de loadOnlineMusicCard no onCreate dela
                    if (actRef === act) actRef = null;
                    log("activity destruída — aguardando o próximo onCreate");
                    return;
                }
                fn(act);
            }
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
                lastCtrl  = want;
                enabled   = want;
                lastSig   = null;
                lastEvSig = null;
                log("card na home = " + (want ? "ON" : "OFF"));
                applyToInstances();
            }
            if (enabled) {
                // Os dois snapshots saem daqui (thread do tick) e não da main:
                // readEvState faz I/O de arquivo, que não deve ir no frame.
                var s = readState();
                var e = readEvState();
                withActivity(function (act) {
                    // loadOnlineMusicCard roda no onCreate e faz removeAllViews:
                    // se a Activity foi recriada, paintCards recria os cards sozinho.
                    paintCards(act, s, e);
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
         ["parseKv",    function () { return parseKv("a=1;b=--").a === "1"; }],
         ["intOrNull",  function () { return intOrNull("--") === null && intOrNull("20") === 20; }],
         ["readEvState",function () { var e = readEvState(); return e === null || e.conn !== undefined; }],
         ["evSig",      function () { return evSig(null) === "none"; }],
         // desenha com um estado sintético: sem o ev-manager de pé readEvState é null
         ["drawEvCard", function () {
             return drawEvCard({conn:true, auto:true, phase:80, soc:62, model:0}) !== null; }],
         // teto rígido: 430 (topo do título "Mídia local") - ROW_TOP
         ["altura",     function () { return ROW_TOP + H <= 430; }],
         // a fileira tem 1158 nesta config: os dois cards + o GAP precisam caber
         ["largura",    function () { return W * 2 + GAP <= 1158; }]
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

    log("cards de clima + ciclo na home ativos (alvo com.beantechs.mediacenter)");
});
