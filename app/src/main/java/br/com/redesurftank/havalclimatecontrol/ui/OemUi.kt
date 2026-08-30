package br.com.redesurftank.havalclimatecontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

// ─────────────────────────────────────────────────────────────
// Paleta OEM (com.beantechs.hvac)
// ─────────────────────────────────────────────────────────────

val OemInk      = Color(0xFFFFFFFF)
val OemInk2     = Color(0x8FFFFFFF)   // .56
val OemInk3     = Color(0x52FFFFFF)   // .32
val OemInk4     = Color(0x66FFFFFF)   // .40
val OemAccent   = Color(0xFF5A8BF3)
val OemTrack    = Color(0x1AFFFFFF)   // .102 — fundo da seekbar
val OemTrackFil = Color(0x295A8BF3)   // .16 do acento

/**
 * Os drawables extraídos do OEM são máscaras: o estado ligado e o desligado têm alpha
 * pixel a pixel IDÊNTICO e diferem só na cor (#F8F8FA contra #5A8BF3). Por isso um
 * único asset serve para os dois estados — tingir sai mais barato que carregar duas
 * cópias de cada ícone, e mantém os dois estados alinhados por construção.
 */
@Composable
fun OemIcon(
    resId: Int,
    on: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
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
            .size(size)
            .alpha(if (!enabled) 0.25f else if (on) 1f else 0.55f)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onClick() })
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
        Text(
            "%.1f".format(clamp(value + step)),
            fontSize = 40.sp, fontWeight = FontWeight.ExtraLight, color = OemInk3,
            modifier = Modifier.pointerInput(value) {
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
            "%.1f".format(clamp(value - step)),
            fontSize = 40.sp, fontWeight = FontWeight.ExtraLight, color = OemInk3,
            modifier = Modifier.pointerInput(value) {
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

/**
 * Seekbar do ventilador (hvac_fans_seekbar). Toque e arraste caem no mesmo cálculo, e o
 * valor é arredondado para o inteiro mais próximo — o OEM não tem passo fracionário.
 */
@Composable
fun OemFanSeek(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    thumbRes: Int,
    onChange: (Int) -> Unit,
) {
    val width  = 350.dp
    val height = 80.dp
    val pct = if (max > 0) value.toFloat() / max else 0f

    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(40.dp))
            .background(OemTrack)
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
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(0f, 1f)).background(OemTrackFil)
        )
        // Marcas: uma por passo, como no drawable do OEM.
        Canvas(Modifier.fillMaxSize().padding(horizontal = 34.dp)) {
            if (max <= 0) return@Canvas
            val y = size.height / 2f
            for (i in 0..max) {
                val x = if (max == 0) 0f else size.width * i / max
                drawCircle(Color(0x33FFFFFF), radius = 1.dp.toPx(), center = Offset(x, y))
            }
        }
        Image(
            painter = painterResource(thumbRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(start = (11 + pct.coerceIn(0f, 1f) * 270).dp, top = 11.dp)
                .size(58.dp),
        )
    }
}
