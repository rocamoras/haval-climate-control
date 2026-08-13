package br.com.redesurftank.havalclimatecontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    var frontDefrostEnable    by mutableStateOf("--")
    var heatingEnable         by mutableStateOf("--")
    var intelligentSwitch     by mutableStateOf("--")
    var settingLimitEnable    by mutableStateOf("--")

    // EV properties
    var wadeModeEnable        by mutableStateOf("--")

    // Read-only properties
    var frontTempRange        by mutableStateOf("--")
    var intelligentTempRange  by mutableStateOf("--")
    var pm25Value             by mutableStateOf("--")
    var comfortCurve          by mutableStateOf("--")

    // Seat properties
    var driverSeatVentLevel    by mutableStateOf("--")
    var passengerSeatVentLevel by mutableStateOf("--")
    var seatVentAutoEnabled    by mutableStateOf(true)
    var comfortMode            by mutableStateOf("AUTO")

    // Configurações — Temperatura Externa Real (UI). Espelho lido pelo serviço.
    var realOutsideTempEnabled by mutableStateOf(false)

    /** Callback UI → serviço: acionado quando a opção "Temperatura Externa Real" é alternada.
     *  fun interface para permitir lambda de Java (o serviço). */
    fun interface RealTempToggleCallback {
        fun onToggle(enabled: Boolean)
    }

    @Volatile var onRealOutsideTempToggle: RealTempToggleCallback? = null

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

    fun sendCommand(key: String, value: String) {
        commandCallback?.onCommand(key, value)
    }

    fun updateVehicleData(
        connected: Boolean,
        inside: String?,
        driver: String?,
        power: String?,
        auto: String?,
        outside: String?
    ) {
        vehicleConnected = connected
        insideTemp       = inside   ?: "--"
        outsideTemp      = outside  ?: "--"
        driverTemp       = driver   ?: "--"
        powerMode        = power    ?: "--"
        autoEnable       = auto     ?: "--"
    }

    fun updateHvacExtras(
        acEn: String?,
        frontDefrost: String?,
        heating: String?,
        intSwitch: String?,
        limitEn: String?,
        frontTRange: String?,
        intTRange: String?,
        pm25: String?,
        comfort: String?,
        wadeMode: String? = null
    ) {
        acEnable             = acEn         ?: "--"
        frontDefrostEnable   = frontDefrost ?: "--"
        heatingEnable        = heating      ?: "--"
        intelligentSwitch    = intSwitch    ?: "--"
        settingLimitEnable   = limitEn      ?: "--"
        frontTempRange       = frontTRange  ?: "--"
        intelligentTempRange = intTRange    ?: "--"
        pm25Value            = pm25         ?: "--"
        comfortCurve         = comfort      ?: "--"
        wadeModeEnable       = wadeMode     ?: "--"
    }

    fun updateSeatData(
        driverVent    : String?,
        passengerVent : String?
    ) {
        driverSeatVentLevel    = driverVent    ?: "--"
        passengerSeatVentLevel = passengerVent ?: "--"
    }

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }
}
