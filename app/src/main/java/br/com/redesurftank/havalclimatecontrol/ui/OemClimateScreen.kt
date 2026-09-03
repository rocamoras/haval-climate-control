package br.com.redesurftank.havalclimatecontrol.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalclimatecontrol.CarProps
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder
import br.com.redesurftank.havalclimatecontrol.R

// ─────────────────────────────────────────────────────────────
// Geometria — medida do com.beantechs.hvac (_O.xml)
//
// A central tem fator de escala 1.00 (1 dp = 1 px), então estas constantes são os
// mesmos números do layout OEM. Não troque por pesos/arranjos: a tela é um posicionamento
// absoluto de 1732 x 628 dentro de 1792 x 660, e qualquer reflow desalinha do plate de
// fundo, que é uma imagem só.
// ─────────────────────────────────────────────────────────────

private const val FRAME_LEFT = 30
private const val FRAME_W    = 1732
private const val FRAME_H    = 628

/**
 * Distribuição de ar (car.hvac.blower_mode) — tratada como BITMASK de saídas.
 *
 * Evidência: MainViewModel.windClick do com.beantechs.hvac compara o parâmetro com 15
 * (0b1111) antes de chamar setBlower, e os próprios nomes do OEM são combinações
 * (hvac_face_foot = rosto + pés). Daí rosto=1, pés=2, desembaçador=4.
 *
 * Os valores em si ainda NÃO foram lidos do carro: os literais de cada ícone vivem no
 * onClick do layout, que é AXML binário. Para confirmar, mexa na fileira pelo app OEM e
 * mande o log pelo botão "Enviar log" das Configurações.
 */
private val BLOWER_MODES = listOf(
    "1" to R.drawable.hvac_face_on,          // rosto
    "3" to R.drawable.hvac_face_foot_off,    // rosto + pés
    "2" to R.drawable.hvac_foot_off,         // pés
    "6" to R.drawable.hvac_defrost_foot_off, // pés + desembaçador
)

/**
 * Recirculação × ar externo (car.hvac.cycle_mode).
 *
 * NÃO é 0/1: o MainViewModel.exchangeClick do OEM compara com 1 e alterna entre 2 e 0,
 * ou seja, o eixo trabalha com 1 e 2. Confirmado até aí; qual dos dois é recirculação
 * ainda depende de teste no carro.
 */
private const val CYCLE_RECIRC = "1"
private const val CYCLE_FRESH  = "2"

// ─────────────────────────────────────────────────────────────
// PM2.5 — enumeradores extraídos do com.beantechs.hvac
//
// HVACValue.EXCHANGE e HVACValue.QUALITY_DESCRIBE. São os breakpoints de PM2.5 do
// AQI chinês (GB 3095-2012 / HJ 633-2012), por isso "Bom" só começa abaixo de 75.
// ─────────────────────────────────────────────────────────────
private val PM25_EXCHANGE = intArrayOf(250, 150, 115, 75, 35, -1)
private val PM25_LABELS   = arrayOf(
    "Poluição séria", "Poluição pesada", "Poluição média",
    "Poluição leve",  "Bom",             "Excelente"
)
private val PM25_COLORS   = arrayOf(
    Color(0xFFFF5252), Color(0xFFFF7043), Color(0xFFFFA726),
    Color(0xFFFFD54F), Color(0xFF9CCC65), Color(0xFF4CAF50)
)

/**
 * Reproduz MainFragment.bindLiveData$lambda-36 do app OEM: procura o PRIMEIRO índice
 * i em que `valor > EXCHANGE[i]`. Como o último elemento é -1, um valor de -1 (o
 * default de getPM() quando o fetch volta vazio) não casa com nenhuma faixa — é o
 * caminho de "sem dado", em que o OEM simplesmente não escreve nada na tela.
 *
 * @return índice 0..5, ou -1 para sem dado / valor não numérico.
 */
private fun pm25BandIndex(raw: String): Int {
    val v = raw.toIntOrNull() ?: return -1
    for (i in PM25_EXCHANGE.indices) if (v > PM25_EXCHANGE[i]) return i
    return -1
}

private fun ClimateStateHolder.isOn(v: String) = v == "1"

/**
 * O carro reporta com ponto, mas basta um valor com virgula entrar no cache para a tela
 * parar de ler — por isso a leitura tolera os dois.
 */
private fun tempOf(raw: String, fallback: Float) =
    raw.replace(',', '.').toFloatOrNull() ?: fallback

/**
 * Valor de temperatura para MANDAR ao carro.
 *
 * Locale.US nao e decoracao: "%.1f".format() usa o locale do aparelho e, numa central
 * em pt-BR, 22.5f vira "22,5". O servico do veiculo nao parseia isso e, pior, o proprio
 * cache do servico guarda a string enviada e a devolve para a tela — que tambem nao a
 * le e cai no fallback. O resultado era o ajuste de temperatura nao sair do lugar.
 */
private fun tempCmd(v: Float) = String.format(java.util.Locale.US, "%.1f", v)

private fun intOf(raw: String, fallback: Int) =
    Regex("\\d+").findAll(raw).lastOrNull()?.value?.toIntOrNull() ?: fallback

@Composable
fun OemClimateScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToScreenInfo: () -> Unit,
    onToggleAutoControl: (Boolean) -> Unit,
    onSeatVentAuto: (Boolean) -> Unit,
    rightHandDrive: Boolean = false,
) {
    val s = ClimateStateHolder

    val fanMax   = intOf(s.fanSpeedRange, 7).coerceAtLeast(1)
    val fanValue = intOf(s.fanSpeed, 0).coerceIn(0, fanMax)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.absoluteOffset(x = FRAME_LEFT.dp, y = 0.dp).size(FRAME_W.dp, FRAME_H.dp)) {

            // Render do interior. O asset do OEM vem para volante à direita; o padrão
            // brasileiro é o espelhado.
            Image(
                painter = painterResource(R.drawable.bg_main),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().scale(scaleX = if (rightHandDrive) 1f else -1f, scaleY = 1f),
            )
            Plate(R.drawable.top_line, 406, 0, 920, 100)
            Plate(R.drawable.bg_toolbar_pop, 0, 516, 1732, 112)

            // ── sair ──────────────────────────────────────────────────────────
            OemIcon(
                resId = R.drawable.btn_exit, on = false,
                contentDescription = "Informações da tela",
                modifier = Modifier.absoluteOffset(22.dp, 20.dp),
                onClick = onNavigateToScreenInfo,
            )

            // ── fileira de cima ───────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(30.dp),
                modifier = Modifier.absoluteOffset(503.dp, 0.dp),
            ) {
                // Controle automático DO APP (histerese + proteção de partida). Não
                // escreve car.hvac.Intelligent_switch_enable — a propriedade do OEM
                // continua só de leitura para nós.
                OemAppAutoIcon(
                    R.drawable.hvac_auto_off, s.autoControlEnabled,
                    contentDescription = "Controle automático do app",
                ) { onToggleAutoControl(!s.autoControlEnabled) }

                OemIcon(R.drawable.hvac_power_off, s.isOn(s.powerMode), contentDescription = "Ligar/desligar") {
                    s.sendCommand(CarProps.POWER_MODE, if (s.isOn(s.powerMode)) "0" else "1")
                }
                OemIcon(R.drawable.hvac_auto_off, s.isOn(s.autoEnable), contentDescription = "Modo automático do HVAC") {
                    s.sendCommand(CarProps.AUTO_ENABLE, if (s.isOn(s.autoEnable)) "0" else "1")
                }
                OemIcon(R.drawable.hvac_ac_off, s.isOn(s.acEnable), contentDescription = "Compressor A/C") {
                    s.sendCommand(CarProps.AC_ENABLE, if (s.isOn(s.acEnable)) "0" else "1")
                }
                OemIcon(R.drawable.hvac_ac_max_off, s.isOn(s.acMaxEnable), contentDescription = "A/C máximo") {
                    s.sendCommand(CarProps.ACMAX_ENABLE, if (s.isOn(s.acMaxEnable)) "0" else "1")
                }
                OemIcon(R.drawable.hvac_heat_off, s.isOn(s.heatingEnable), contentDescription = "Aquecimento") {
                    s.sendCommand(CarProps.HEATING, if (s.isOn(s.heatingEnable)) "0" else "1")
                }
            }

            // ── leitura cabine / externa / PM2.5 ──────────────────────────────
            ReadoutColumn(
                cabin = s.insideTemp, outside = s.outsideTemp, pm25 = s.pm25Value,
                modifier = Modifier.absoluteOffset(1400.dp, 40.dp),
            )

            // ── distribuição de ar ────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.absoluteOffset(626.dp, 124.dp),
            ) {
                BLOWER_MODES.forEach { (value, res) ->
                    OemIcon(res, s.blowerMode == value, contentDescription = "Distribuição de ar $value") {
                        s.sendCommand(CarProps.BLOWER_MODE, value)
                    }
                }
            }

            // ── temperaturas ──────────────────────────────────────────────────
            OemTempScroll(
                value = tempOf(s.driverTemp, 22f), caption = "motorista",
                modifier = Modifier.absoluteOffset(250.dp, 173.dp),
            ) { v ->
                s.sendCommand(CarProps.DRIVER_TEMP, tempCmd(v))
                if (s.isOn(s.syncEnable)) s.sendCommand(CarProps.PASS_TEMP, tempCmd(v))
            }
            OemTempScroll(
                value = tempOf(s.passengerTemp, 22f), caption = "passageiro",
                modifier = Modifier.absoluteOffset(1282.dp, 173.dp),
            ) { v ->
                s.sendCommand(CarProps.PASS_TEMP, tempCmd(v))
                if (s.isOn(s.syncEnable)) s.sendCommand(CarProps.DRIVER_TEMP, tempCmd(v))
            }

            // Unico botao da tela com DOIS glifos: o OEM troca o desenho, nao so a cor —
            // elo partido (com as fagulhas em volta) no off, elo fechado no on. Tingir o
            // asset de off deixava a corrente partida e azul, que nao existe no OEM.
            val syncOn = s.isOn(s.syncEnable)
            OemIcon(
                if (syncOn) R.drawable.hvac_sync_on else R.drawable.hvac_sync_off,
                syncOn, size = 154.dp, height = 56.dp,
                contentDescription = "Sincronizar temperaturas",
                modifier = Modifier.absoluteOffset(283.dp, 438.dp),
            ) {
                val turningOn = !syncOn
                s.sendCommand(CarProps.SYNC_ENABLE, if (turningOn) "1" else "0")
                // Ao ligar, o passageiro assume a temperatura do motorista — é o que o
                // OEM faz, e sem isso os dois lados ficariam "sincronizados" divergentes.
                if (turningOn) s.sendCommand(CarProps.PASS_TEMP, s.driverTemp)
            }

            // ── ventilador ────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.absoluteOffset(842.dp, 452.dp).size(120.dp, 54.dp),
            ) {
                Text("$fanValue", fontSize = 44.sp, fontWeight = FontWeight.ExtraLight, color = OemInk)
                Text(
                    "/$fanMax", fontSize = 22.sp, fontWeight = FontWeight.ExtraLight, color = OemInk3,
                    modifier = Modifier.padding(top = 19.dp),
                )
            }

            Box(Modifier.absoluteOffset(635.dp, 532.dp).size(462.dp, 80.dp)) {
                OemIcon(
                    R.drawable.ic_fans_mix_nor, s.cycleMode == CYCLE_RECIRC, size = 32.dp,
                    contentDescription = "Recirculação",
                    modifier = Modifier.absoluteOffset(0.dp, 24.dp),
                ) {
                    s.sendCommand(
                        CarProps.CYCLE_MODE,
                        if (s.cycleMode == CYCLE_RECIRC) CYCLE_FRESH else CYCLE_RECIRC,
                    )
                }
                OemFanSeek(
                    value = fanValue, max = fanMax, thumbRes = R.drawable.thumb,
                    modifier = Modifier.absoluteOffset(52.dp, 0.dp),
                ) { v -> s.sendCommand(CarProps.FAN_SPEED, v.toString()) }
                OemIcon(
                    R.drawable.ic_fans_max_nor, fanValue == fanMax, size = 40.dp,
                    contentDescription = "Ventilação máxima",
                    modifier = Modifier.absoluteOffset(422.dp, 20.dp),
                ) {
                    s.sendCommand(CarProps.FAN_SPEED, if (fanValue == fanMax) "3" else fanMax.toString())
                }
            }

            // ── fileira de baixo ──────────────────────────────────────────────
            OemIcon(
                R.drawable.hvac_front_defrost_off, s.isOn(s.frontDefrostEnable),
                contentDescription = "Desembaçador dianteiro",
                modifier = Modifier.absoluteOffset(197.dp, 524.dp),
            ) { s.sendCommand(CarProps.FRONT_DEFROST, if (s.isOn(s.frontDefrostEnable)) "0" else "1") }

            OemIcon(
                R.drawable.hvac_back_defrost_off, s.isOn(s.rearDefrostEnable),
                contentDescription = "Desembaçador traseiro",
                modifier = Modifier.absoluteOffset(343.dp, 524.dp),
            ) { s.sendCommand(CarProps.REAR_DEFROST, if (s.isOn(s.rearDefrostEnable)) "0" else "1") }

            SeatVent(
                zone = SeatZone.PASSENGER, level = intOf(s.passengerSeatVentLevel, 0),
                auto = s.seatVentAutoEnabled, onSeatVentAuto = onSeatVentAuto,
                modifier = Modifier.absoluteOffset(489.dp, 524.dp),
            )
            SeatVent(
                zone = SeatZone.DRIVER, level = intOf(s.driverSeatVentLevel, 0),
                auto = s.seatVentAutoEnabled, onSeatVentAuto = onSeatVentAuto,
                modifier = Modifier.absoluteOffset(1147.dp, 524.dp),
            )

            OemIcon(
                R.drawable.hvac_exchange_in, s.cycleMode == CYCLE_FRESH,
                contentDescription = "Troca de ar",
                modifier = Modifier.absoluteOffset(1293.dp, 524.dp),
            ) {
                s.sendCommand(
                    CarProps.CYCLE_MODE,
                    if (s.cycleMode == CYCLE_FRESH) CYCLE_RECIRC else CYCLE_FRESH,
                )
            }

            OemIcon(
                R.drawable.hvac_more, on = false, contentDescription = "Configurações",
                modifier = Modifier.absoluteOffset(1439.dp, 524.dp),
                onClick = onNavigateToSettings,
            )
        }
    }
}

@Composable
private fun Plate(resId: Int, x: Int, y: Int, w: Int, h: Int) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.absoluteOffset(x.dp, y.dp).size(w.dp, h.dp),
    )
}

/**
 * O slot de qualidade do ar do OEM. O PM2.5 entra como terceira linha, logo abaixo da
 * temperatura externa — é o único lugar da tela onde já há um bloco numérico alinhado
 * à direita, e a leitura fica onde o motorista já procura os números.
 */
@Composable
private fun ReadoutColumn(cabin: String, outside: String, pm25: String, modifier: Modifier) {
    Column(
        modifier = modifier.width(332.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Label("CABINE · EXTERNA")
        Value("${deg(cabin)}  /  ${deg(outside)}")
        Spacer(Modifier.height(6.dp))
        Label("PM2.5")
        val band = pm25BandIndex(pm25)
        if (band < 0) {
            Value("--")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    PM25_LABELS[band], fontSize = 22.sp, fontWeight = FontWeight.Light,
                    color = PM25_COLORS[band],
                )
                Value("$pm25 µg/m³")
            }
        }
    }
}

// Ponto, nao virgula: e o que a tela do OEM mostra, e o resto da coluna de leitura
// precisa concordar com os seletores de temperatura.
private fun deg(v: String) = v.replace(',', '.').toFloatOrNull()?.let { oemTemp(it) + "°" } ?: "--"

@Composable
private fun Label(text: String) = Text(
    text, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp,
    color = OemInk4, textAlign = TextAlign.End,
)

@Composable
private fun Value(text: String) = Text(
    text, fontSize = 22.sp, fontWeight = FontWeight.Light, color = OemInk2, textAlign = TextAlign.End,
)

/**
 * O OEM tem duas familias de banco com a mesma silhueta: `main_1..3` traz as ondas de
 * calor (AQUECIMENTO) e `main_c1..c3` traz a ventoinha (VENTILACAO), as duas com a
 * barra de tracos do nivel embaixo. A tela nasceu com a primeira, que e o controle
 * errado — este botao escreve DRIVER_SEAT_VENT.
 *
 * O nivel 0 usa o asset de cadeira cheia, e nao o `_dis`: aquele tem alpha maximo de
 * 51, que o [OemSeatIcon] ainda multiplica por 0,42 — na tela do carro nao dava para
 * ver que havia um botao ali.
 */
private enum class SeatZone(val prop: String, val dim: Int, val levels: List<Int>) {
    DRIVER(
        CarProps.DRIVER_SEAT_VENT, R.drawable.hvac_seat_main_0,
        listOf(R.drawable.hvac_seat_main_c1, R.drawable.hvac_seat_main_c2,
               R.drawable.hvac_seat_main_c3),
    ),
    PASSENGER(
        CarProps.PASSENGER_SEAT_VENT, R.drawable.hvac_seat_second_0,
        listOf(R.drawable.hvac_seat_second_c1, R.drawable.hvac_seat_second_c2,
               R.drawable.hvac_seat_second_c3),
    ),
}

/**
 * Ciclo 0 → 1 → 2 → 3 → AUTO → 0, num toque só. O AUTO precisa estar no ciclo porque o
 * serviço já derruba o modo automático sozinho quando detecta alteração externa — sem
 * um caminho de volta na própria tela, o motorista nunca mais o reativaria.
 */
@Composable
private fun SeatVent(
    zone: SeatZone,
    level: Int,
    auto: Boolean,
    onSeatVentAuto: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val s = ClimateStateHolder
    OemSeatIcon(
        dimRes = zone.dim,
        litRes = zone.levels.getOrNull(level - 1),
        auto = auto,
        modifier = modifier,
        contentDescription = "Ventilação do banco — " +
            (if (zone == SeatZone.DRIVER) "motorista" else "passageiro") +
            (if (auto) " (automático)" else " ($level/3)"),
    ) {
        if (auto) {
            onSeatVentAuto(false)
            s.sendCommand(zone.prop, "0")
        } else if (level >= 3) {
            onSeatVentAuto(true)
        } else {
            s.sendCommand(zone.prop, (level + 1).toString())
        }
    }
}
