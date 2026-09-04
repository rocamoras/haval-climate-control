package br.com.redesurftank.havalclimatecontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.util.Log
import android.graphics.drawable.AnimatedImageDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
// Paleta OEM (com.beantechs.hvac)
// ─────────────────────────────────────────────────────────────

val OemInk      = Color(0xFFFFFFFF)
val OemInk2     = Color(0x8FFFFFFF)   // .56
val OemInk3     = Color(0x52FFFFFF)   // .32
val OemInk4     = Color(0x66FFFFFF)   // .40
val OemAccent   = Color(0xFF5A8BF3)
// Trilho e preenchimento da seekbar do ventilador, lidos do `seekbar_fans` do OEM
// (layer-list: fundo sólido + clip de um gradiente). Os nomes são os de lá, e os valores
// são os do conjunto `night` do arsc, que é o que a central usa.
val OemTrack    = Color(0x1AFFFFFF)   // fans_seekbar_full — .10
val OemFanProA  = Color(0xFF588CF5)   // fans_seekbar_pro — início, OPACO
val OemFanProB  = Color(0xFF457FF5)   // fans_seekbar_pro_end — fim

/**
 * Os drawables extraídos do OEM são máscaras: o estado ligado e o desligado têm alpha
 * pixel a pixel IDÊNTICO e diferem só na cor (#F8F8FA contra #5A8BF3). Por isso um
 * único asset serve para os dois estados — tingir sai mais barato que carregar duas
 * cópias de cada ícone, e mantém os dois estados alinhados por construção.
 *
 * A exceção é o SYNC: lá o OEM muda o DESENHO (elo partido no off, elo fechado no on),
 * então quem chama passa o asset já escolhido pelo estado. Se aparecer outro par assim,
 * é o mesmo caminho — a tintura continua valendo por cima.
 */
@Composable
fun OemIcon(
    resId: Int,
    on: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    /** Altura, quando o drawable nao e quadrado. Sem isto o glifo fica centralizado
     *  numa caixa quadrada e desce (size - alturaReal) / 2 — foi o que desalinhou o
     *  botao de sync, que e 154x56 e ganhava uma caixa de 154x154. */
    height: Dp = size,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    // pointerInput em vez de Button: o OEM não tem ripple, e um Button traria padding
    // e elevação que quebrariam as coordenadas absolutas desta tela.
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(if (on) OemAccent else OemInk),
        modifier = modifier
            .size(size, height)
            .alpha(if (!enabled) 0.25f else if (on) 1f else 0.55f)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onClick() })
            },
    )
}

/**
 * Uma das animações de vento do OEM (WebP animado de 38 frames).
 *
 * Não entra como [Image]: o painter do Compose só desenha o primeiro frame de um WebP
 * animado. Quem anima é o `AnimatedImageDrawable` do próprio Android — disponível desde
 * a API 28, que e o minSdk deste app, então não custa dependência nenhuma. O ritmo vem
 * do arquivo (40ms por frame, 1,52s de loop); o asset do OEM traz 0ms em todo frame e
 * deixa o app decidir, e foi por isso que o reencode gravou a duração.
 *
 * O tamanho vai no DECODE, via `setTargetSize`, e não no scaleType. Na foto do carro de
 * 04/09 a arte apareceu no tamanho nativo do arquivo (575x315) ancorada no canto, com os
 * jatos batendo na fileira de ícones em vez da linha do painel: medindo o asset, a tinta
 * ocupa 49%..69% da altura, e a posição só fecha se a altura desenhada tiver sido ~315 e
 * não ~628. Com o alvo decidido no decode, o drawable já nasce do tamanho da caixa, o
 * scaleType deixa de importar e ainda desaparece o upscale por frame.
 */
@Composable
fun OemWind(resId: Int, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val targetW: Int
    val targetH: Int
    with(LocalDensity.current) {
        targetW = width.roundToPx()
        targetH = height.roundToPx()
    }
    // Fora da main thread: `decodeDrawable` de um WebP animado de 38 frames faz trabalho
    // sincrono, e no `update` do AndroidView isso caía direto no thread da UI.
    val anim by produceState<Drawable?>(null, resId, targetW, targetH) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ctx.resources, resId)
                ) { decoder, _, _ -> decoder.setTargetSize(targetW, targetH) }
            }.onFailure { Log.e("OemWind", "falha ao decodificar $resId", it) }.getOrNull()
        }
    }
    AndroidView(
        modifier = modifier.size(width, height),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                // MATCH_PARENT explicito: com WRAP_CONTENT o ImageView pode se resolver
                // pelo tamanho intrinseco do drawable em vez do tamanho da caixa.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { iv ->
            // O update roda a cada recomposição; sem esta guarda a animação
            // recomeçaria do frame 0 a cada mudança de estado da tela. start() fica aqui
            // de proposito: o drawable precisa ser ligado no thread que o desenha.
            if (iv.drawable !== anim) {
                iv.setImageDrawable(anim)
                (anim as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            }
        },
    )
}

/**
 * Ícone de banco: glifo apagado por baixo, nível aceso por cima. O selo AUTO é nosso —
 * o OEM não tem esse estado, e sem ele não haveria como voltar ao automático depois de
 * mexer no nível.
 */
@Composable
fun OemSeatIcon(
    dimRes: Int,
    litRes: Int?,
    auto: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Image(
            painter = painterResource(dimRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(OemInk),
            modifier = Modifier.fillMaxSize().alpha(0.42f),
        )
        if (litRes != null) {
            Image(
                painter = painterResource(litRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(OemAccent),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (auto) {
            Text(
                "AUTO",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = OemAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Seletor vertical de temperatura (TempScrollViewV3 do OEM). Os vizinhos são clicáveis:
 * numa tela de carro, acertar o passo de 0,5 arrastando é pior do que tocar no número
 * que se quer.
 */
/** A tela do carro escreve temperatura com PONTO, como o OEM e o layout de referencia.
 *  Sem locale fixo os vizinhos sairiam "22,5" em pt-BR enquanto o valor selecionado,
 *  montado por concatenacao, continuaria com ponto — os tres numeros da mesma coluna
 *  discordando entre si. */
fun oemTemp(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

@Composable
fun OemTempScroll(
    value: Float,
    caption: String,
    modifier: Modifier = Modifier,
    min: Float = 16f,
    max: Float = 32f,
    step: Float = 0.5f,
    onChange: (Float) -> Unit,
) {
    fun clamp(v: Float) = v.coerceIn(min, max)
    val whole = value.toInt()
    val frac  = ((value - whole) * 10).toInt()

    Column(
        modifier = modifier.size(200.dp, 228.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // fillMaxWidth: o alvo passa a ser a coluna inteira (200dp) e nao so o glifo.
        // Num toque de carro em movimento, acertar 4 digitos de 40sp e pedir demais.
        Text(
            oemTemp(clamp(value + step)),
            fontSize = 40.sp, fontWeight = FontWeight.ExtraLight, color = OemInk3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().pointerInput(value) {
                detectTapGestures(onTap = { onChange(clamp(value + step)) })
            },
        )
        Row(verticalAlignment = Alignment.Top) {
            Text("$whole", fontSize = 76.sp, fontWeight = FontWeight.ExtraLight, color = OemInk)
            Text(
                ".$frac", fontSize = 30.sp, fontWeight = FontWeight.ExtraLight, color = OemInk,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            oemTemp(clamp(value - step)),
            fontSize = 40.sp, fontWeight = FontWeight.ExtraLight, color = OemInk3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().pointerInput(value) {
                detectTapGestures(onTap = { onChange(clamp(value - step)) })
            },
        )
        Text(
            caption.uppercase(),
            fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp,
            color = OemInk3,
        )
    }
}

// Geometria do SeekBar do OEM, tirada do `fragment_hvac_main`: layout_width 350,
// layout_height 80 e **maxHeight 6**. Ou seja, a área de toque tem 80 de altura mas o
// trilho desenhado tem 6 — a tela chegou a pintar os 80 como uma pílula de raio 40, que
// e o dobro de tinta que o OEM usa. O knob (`hvac_fans_pop_slider`) tem 58 e sobra
// FAN_INSET em cada ponta, o que dá 270 de curso.
private val FAN_SEEK_W  = 350.dp
private val FAN_SEEK_H  = 80.dp
private val FAN_TRACK_H = 6.dp
private val FAN_THUMB   = 58.dp
private val FAN_INSET   = 11.dp
private val FAN_TRAVEL  = FAN_SEEK_W - FAN_THUMB - FAN_INSET * 2   // 270.dp

/**
 * Seekbar do ventilador (`seekbar_fans` do OEM). Toque e arraste caem no mesmo cálculo, e
 * o valor é arredondado para o inteiro mais próximo — o OEM não tem passo fracionário.
 *
 * Não há marcas de passo: o `layer-list` do OEM tem só o fundo e o gradiente recortado.
 * A tela desenhava um ponto por passo com um comentário dizendo que era "como no drawable
 * do OEM", o que simplesmente não era verdade.
 */
@Composable
fun OemFanSeek(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    thumbRes: Int,
    onChange: (Int) -> Unit,
) {
    val pct = (if (max > 0) value.toFloat() / max else 0f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .size(FAN_SEEK_W, FAN_SEEK_H)
            .pointerInput(max) {
                fun emit(x: Float) {
                    if (size.width <= 0) return
                    onChange(((x / size.width).coerceIn(0f, 1f) * max).toInt().coerceIn(0, max))
                }
                detectTapGestures(onTap = { emit(it.x) })
            }
            .pointerInput(max) {
                detectHorizontalDragGestures { change, _ ->
                    if (size.width > 0) {
                        onChange(
                            ((change.position.x / size.width).coerceIn(0f, 1f) * max)
                                .toInt().coerceIn(0, max)
                        )
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h    = FAN_TRACK_H.toPx()
            val top  = (size.height - h) / 2f
            val edge = CornerRadius(h / 2f)
            drawRoundRect(OemTrack, Offset(0f, top), Size(size.width, h), edge)

            // O gradiente do OEM é definido sobre a barra INTEIRA e depois recortado no
            // nível (`<clip><shape><gradient>`), e não esticado até o valor atual: em
            // 3/7 aparece o começo do gradiente, não ele todo comprimido.
            val fill = FAN_INSET.toPx() + FAN_THUMB.toPx() / 2f + pct * FAN_TRAVEL.toPx()
            if (pct > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(OemFanProA, OemFanProB), startX = 0f, endX = size.width,
                    ),
                    topLeft = Offset(0f, top),
                    size = Size(fill, h),
                    cornerRadius = edge,
                )
            }
        }
        Image(
            painter = painterResource(thumbRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(start = FAN_INSET + FAN_TRAVEL * pct, top = FAN_INSET)
                .size(FAN_THUMB),
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Configurações — geometria do bg_setting do OEM
// ─────────────────────────────────────────────────────────────

/** Cabeçalho com seta de voltar (.sback: left 50, top 32, altura 88). */
@Composable
fun OemBackHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .absoluteOffset(50.dp, 32.dp)
            .height(88.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) },
    ) {
        Canvas(Modifier.size(44.dp)) {
            val w = size.width
            // Chevron do OEM: viewBox 0 0 90 90, path "M52 26 33 45l19 19".
            val k = w / 90f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 4f * k,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(52f * k, 26f * k); lineTo(33f * k, 45f * k); lineTo(52f * k, 64f * k)
            }
            drawPath(path, OemInk, style = stroke)
        }
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Normal, color = OemInk)
    }
}

/** Título de seção. Não existe no OEM — entrou para separar o que é do carro do que é
 *  do app, que é a distinção que mais confunde nesta tela. */
@Composable
fun OemSectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.4.sp,
        color = OemInk4,
        modifier = Modifier.padding(top = 26.dp, bottom = 10.dp),
    )
}

/** Controle segmentado do OEM (.seg / .seg-item), 64dp de altura. */
@Composable
fun OemSeg(options: List<String>, selected: Int, width: Dp, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.width(width).height(64.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) OemAccent.copy(alpha = 0.20f) else Color.Transparent)
                    .pointerInput(i) { detectTapGestures(onTap = { onSelect(i) }) },
            ) {
                Text(
                    label, fontSize = 32.sp,
                    color = if (on) OemAccent else OemInk2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Linha rótulo + controle (.srow: rótulo de 400dp, gap 40). */
@Composable
fun OemRow(label: String, minHeight: Dp = 96.dp, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
    ) {
        Text(label, fontSize = 28.sp, color = OemInk, modifier = Modifier.width(400.dp))
        content()
    }
}

/** Aviso/erro sob uma linha. Amarelo é ressalva, vermelho é "não vai funcionar". */
@Composable
fun OemNote(text: String, severe: Boolean = false) {
    Text(
        text, fontSize = 18.sp, lineHeight = 24.sp,
        color = if (severe) Color(0xFFFF7043) else Color(0xFFFFB74D),
        modifier = Modifier.padding(start = 440.dp, bottom = 12.dp),
    )
}

/** Botão de ação (atualizar, enviar log) no estilo dos segmentos. */
@Composable
fun OemButton(label: String, enabled: Boolean = true, width: Dp = 400.dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width).height(64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) OemAccent.copy(alpha = 0.20f) else Color(0x14FFFFFF))
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { onClick() }) },
    ) {
        Text(label, fontSize = 28.sp, color = if (enabled) OemAccent else OemInk3)
    }
}

/**
 * Botão do controle automático DO APP.
 *
 * Reaproveita o glifo AUTO do OEM com um ponto de acento no canto. O ícone
 * "auto inteligente" do mock não existe no com.beantechs.hvac (o APK só traz
 * hvac_auto_*), e inventar um asset seria pior do que marcar o que já existe — ainda
 * mais porque esta função é nossa, não do carro, e convém que ela não se confunda com
 * o AUTO nativo que fica logo ao lado.
 */
@Composable
fun OemAppAutoIcon(
    baseRes: Int,
    on: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Image(
            painter = painterResource(baseRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(if (on) OemAccent else OemInk),
            modifier = Modifier.fillMaxSize().alpha(if (on) 1f else 0.55f),
        )
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension * 0.075f
            drawCircle(
                color = if (on) OemAccent else OemInk,
                radius = r,
                center = Offset(this.size.width - r * 2.2f, r * 2.2f),
                alpha = if (on) 1f else 0.55f,
            )
        }
    }
}
