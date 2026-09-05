package br.com.redesurftank.havalclimatecontrol

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import br.com.redesurftank.havalclimatecontrol.utils.PersistentLog

object ClimateStateHolder {

    var vehicleConnected      by mutableStateOf(false)
    var autoControlEnabled    by mutableStateOf(true)
    var autoEnable       by mutableStateOf("--")
    var insideTemp       by mutableStateOf("--")
    var outsideTemp      by mutableStateOf("--")
    var driverTemp       by mutableStateOf("--")
    var powerMode        by mutableStateOf("--")
    val actionLog        = mutableStateListOf<String>()

    // Toggle properties
    var acEnable              by mutableStateOf("--")
    var acMaxEnable           by mutableStateOf("--")
    var frontDefrostEnable    by mutableStateOf("--")
    var rearDefrostEnable     by mutableStateOf("--")
    var heatingEnable         by mutableStateOf("--")
    var intelligentSwitch     by mutableStateOf("--")
    var settingLimitEnable    by mutableStateOf("--")

    // Temperatura
    var passengerTemp    by mutableStateOf("--")
    var syncEnable       by mutableStateOf("--")

    // Ar
    var fanSpeed         by mutableStateOf("--")
    var fanSpeedRange    by mutableStateOf("--")
    var blowerMode       by mutableStateOf("--")
    var cycleMode        by mutableStateOf("--")

    // Qualidade do ar e configurações do HVAC
    var aqsEnable            by mutableStateOf("--")
    var anionEnable          by mutableStateOf("--")
    var autoDefrostEnable    by mutableStateOf("--")
    var fragranceStatus        by mutableStateOf("--")
    var fragranceConcentration by mutableStateOf("--")
    var fragranceType          by mutableStateOf("--")

    // EV properties
    var wadeModeEnable        by mutableStateOf("--")

    // Read-only properties
    var frontTempRange        by mutableStateOf("--")
    var intelligentTempRange  by mutableStateOf("--")
    var pm25Value             by mutableStateOf("--")
    var comfortCurve          by mutableStateOf("--")

    // ── Telemetria do sensor de PM2.5 ────────────────────────────────────────
    // Alimentada por updateFromCache (ou seja, pelo serviço), não pela tela — assim
    // o histórico continua sendo coletado com a tela fechada.

    data class Pm25Sample(val value: Int, val atMs: Long)

    val pm25History = mutableStateListOf<Pm25Sample>()
    var pm25Min by mutableStateOf(-1)
    var pm25Max by mutableStateOf(-1)

    private const val PM25_HISTORY_MAX = 60

    /** Registra a amostra só quando o valor numérico muda — o serviço republica o
     *  cache inteiro a cada onDataChanged, e sem esse filtro o histórico viraria
     *  dezenas de linhas idênticas. -1 (sem dado) não entra em min/max. */
    private fun recordPm25(raw: String?) {
        val v = raw?.toIntOrNull() ?: return
        if (pm25History.firstOrNull()?.value == v) return
        pm25History.add(0, Pm25Sample(v, System.currentTimeMillis()))
        if (pm25History.size > PM25_HISTORY_MAX) pm25History.removeAt(pm25History.lastIndex)
        if (v >= 0) {
            if (pm25Min < 0 || v < pm25Min) pm25Min = v
            if (pm25Max < 0 || v > pm25Max) pm25Max = v
        }
    }

    fun clearPm25History() {
        pm25History.clear()
        pm25Min = -1
        pm25Max = -1
    }

    // Seat properties
    var driverSeatVentLevel    by mutableStateOf("--")
    var passengerSeatVentLevel by mutableStateOf("--")
    var seatVentAutoEnabled    by mutableStateOf(true)
    var comfortMode            by mutableStateOf("AUTO")

    // Configurações — Temperatura Externa Real (UI). Espelho lido pelo serviço.
    var realOutsideTempEnabled by mutableStateOf(false)

    // Configurações — Card na Home da MediaCenter. Espelho lido pelo serviço.
    var homeCardEnabled by mutableStateOf(false)

    /** Callback UI → serviço para as opções que dependem de injeção Frida.
     *  fun interface para permitir lambda de Java (o serviço). */
    fun interface ToggleCallback {
        fun onToggle(enabled: Boolean)
    }

    @Volatile var onRealOutsideTempToggle: ToggleCallback? = null
    @Volatile var onHomeCardToggle: ToggleCallback? = null

    /**
     * Avisa o serviço quando a nossa Activity entra e sai de foco.
     *
     * Serve para o serviço manter o HVAC do OEM desabilitado durante a sessão inteira,
     * em vez de desabilitar e reabilitar a cada escrita: enquanto a nossa tela está na
     * frente, é ela que precisa de proteção contra o app do OEM roubar o foco.
     */
    @Volatile var onUiVisibilityChange: ToggleCallback? = null

    /** Espelho do último estado enviado, para o serviço decidir sem consultar a UI. */
    @Volatile var uiVisible: Boolean = false
        private set

    fun setUiVisible(visible: Boolean) {
        if (uiVisible == visible) return
        uiVisible = visible
        onUiVisibilityChange?.onToggle(visible)
    }

    // Callbacks invocados pelo serviço (via mainHandler) quando uma mudança externa é detectada
    @Volatile var onExternalVentChange:    ((String) -> Unit)? = null
    @Volatile var onExternalComfortChange: ((String) -> Unit)? = null

    /** Chamado pelo serviço quando a ventilação do banco é alterada externamente.
     *  Desativa o modo AUTO e notifica a UI para persistir a mudança. */
    fun notifyExternalVentChange(newLevel: String) {
        seatVentAutoEnabled = false
        onExternalVentChange?.invoke(newLevel)
    }

    /** Chamado pelo serviço quando a curva de conforto é alterada externamente.
     *  Converte o valor numérico ("0"/"1"/"2") para o modo textual e notifica a UI. */
    fun notifyExternalComfortChange(curve: String) {
        val newMode = when (curve) {
            "0"  -> "SUAVE"
            "1"  -> "NORMAL"
            "2"  -> "FORTE"
            else -> return   // valor desconhecido, ignora
        }
        comfortMode = newMode
        onExternalComfortChange?.invoke(newMode)
    }

    fun interface CommandCallback {
        fun onCommand(key: String, value: String)
    }

    @JvmField @Volatile var commandCallback: CommandCallback? = null

    /**
     * Valor que a UI pediu e o carro ainda nao confirmou.
     *
     * A tela espelha o que o VEICULO reporta, e entre o clique e a confirmacao o carro
     * continua devolvendo o valor antigo. No log de 05/09 isso apareceu inteiro: doze
     * escritas de `passenger_seat_ventilation_level = 1` em 5,5s, todas do mesmo valor,
     * porque o nivel exibido voltava a 0 entre um clique e outro e o ciclo recomecava do
     * zero. Enquanto o pedido esta pendente a tela mostra o que foi pedido, e o proximo
     * clique parte dali — que e o que faz 0→1→2→3 funcionar.
     */
    private class Pending(val value: String, val atMs: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Ate quando insistir no valor pedido. Curto o bastante para a tela nao mentir se o
     * carro recusar (ventilacao de banco depende de condicoes que ele nao conta), e longo
     * o bastante para cobrir a escrita, que no log levou de 0,2s a 2,1s dependendo da fila.
     */
    private const val PENDING_TTL_MS = 4_000L

    /** Ultimo espelho recebido, para reaplicar na hora do clique sem esperar o servico. */
    private var lastCache: Map<String, String?> = emptyMap()
    private var lastConnected = false

    fun sendCommand(key: String, value: String) {
        pending[key] = Pending(value, SystemClock.elapsedRealtime())
        // Reaplica o espelho JA com o pendente: sem isto a tela so mudaria quando a
        // escrita voltasse, e e nessa janela que o usuario clica de novo. Só depois do
        // primeiro espelho: com o cache vazio isto marcaria o veiculo como desconectado.
        if (lastCache.isNotEmpty()) updateFromCache(lastConnected, lastCache)
        commandCallback?.onCommand(key, value)
    }

    /**
     * Espelha o cache inteiro do serviço nos campos observáveis.
     *
     * Substitui os antigos updateVehicleData/updateHvacExtras/updateSeatData, que eram
     * posicionais: com 31 propriedades, trocar dois argumentos de lugar viraria um bug
     * silencioso que só aparece na tela. Aqui a associação é pela chave.
     */
    fun updateFromCache(connected: Boolean, cache: Map<String, String?>) {
        lastCache = cache
        lastConnected = connected

        fun v(key: String): String {
            val p = pending[key]
            if (p != null) {
                when {
                    // O carro confirmou: o pendente cumpriu o papel e sai.
                    cache[key] == p.value -> pending.remove(key)
                    // Estourou a janela: melhor mostrar a verdade do carro do que insistir.
                    SystemClock.elapsedRealtime() - p.atMs > PENDING_TTL_MS -> pending.remove(key)
                    else -> return p.value
                }
            }
            return cache[key] ?: "--"
        }

        vehicleConnected = connected

        insideTemp   = v(CarProps.INSIDE_TEMP)
        outsideTemp  = v(CarProps.OUTSIDE_TEMP)
        powerMode    = v(CarProps.POWER_MODE)
        autoEnable   = v(CarProps.AUTO_ENABLE)

        acEnable           = v(CarProps.AC_ENABLE)
        acMaxEnable        = v(CarProps.ACMAX_ENABLE)
        heatingEnable      = v(CarProps.HEATING)
        intelligentSwitch  = v(CarProps.INTELLIGENT_SWITCH)

        driverTemp    = v(CarProps.DRIVER_TEMP)
        passengerTemp = v(CarProps.PASS_TEMP)
        syncEnable    = v(CarProps.SYNC_ENABLE)

        fanSpeed      = v(CarProps.FAN_SPEED)
        fanSpeedRange = v(CarProps.FAN_SPEED_RANGE)
        blowerMode    = v(CarProps.BLOWER_MODE)
        cycleMode     = v(CarProps.CYCLE_MODE)

        frontDefrostEnable = v(CarProps.FRONT_DEFROST)
        rearDefrostEnable  = v(CarProps.REAR_DEFROST)

        aqsEnable   = v(CarProps.AQS_ENABLE)
        anionEnable = v(CarProps.ANION_ENABLE)

        comfortCurve         = v(CarProps.COMFORT_CURVE)
        settingLimitEnable   = v(CarProps.LIMIT_ENABLE)
        autoDefrostEnable    = v(CarProps.AUTO_DEFROST)
        frontTempRange       = v(CarProps.FRONT_TEMP_RANGE)
        intelligentTempRange = v(CarProps.INTELLIGENT_TEMP_RANGE)

        fragranceStatus        = v(CarProps.FRAGRANCE_STATUS)
        fragranceConcentration = v(CarProps.FRAGRANCE_CONCENTRATION)
        fragranceType          = v(CarProps.FRAGRANCE_TYPE)

        driverSeatVentLevel    = v(CarProps.DRIVER_SEAT_VENT)
        passengerSeatVentLevel = v(CarProps.PASSENGER_SEAT_VENT)

        wadeModeEnable = v(CarProps.WADE_MODE)

        pm25Value = v(CarProps.PM25)
        recordPm25(cache[CarProps.PM25])
    }

    /** Serviço caiu ou desconectou: tudo volta para "--" e o indicador de conexão apaga. */
    fun clearVehicleData() {
        // Os pendentes tambem: sem carro nao ha o que confirmar, e insistir neles deixaria
        // a tela mostrando valor de veiculo desconectado.
        pending.clear()
        updateFromCache(false, emptyMap())
    }

    /** A lista em memória é só o que a tela mostra; o espelho em disco é o que
     *  sobrevive a um reinício e permite reconstruir o que o app fez antes de cair. */
    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
        PersistentLog.write("ACAO", entry)
    }
}
