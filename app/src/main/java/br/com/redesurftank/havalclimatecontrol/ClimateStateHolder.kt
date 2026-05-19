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
    val seatActionLog    = mutableStateListOf<String>()

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
    var chairMemoryAutoEnable  by mutableStateOf("--")
    var assMemorySetting       by mutableStateOf("--")
    var chairMemPosSetAction   by mutableStateOf("--")
    var chairMemPosSetFeedback by mutableStateOf("--")
    var driverSeatVentLevel    by mutableStateOf("--")
    var passengerSeatVentLevel by mutableStateOf("--")
    var seatVentAutoEnabled    by mutableStateOf(true)

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
        chairMemAutoEnable : String?,
        assMemSetting      : String?,
        chairMemPosAction  : String?,
        chairMemPosFeedback: String?,
        driverVent         : String?,
        passengerVent      : String?
    ) {
        chairMemoryAutoEnable  = chairMemAutoEnable  ?: "--"
        assMemorySetting       = assMemSetting       ?: "--"
        chairMemPosSetAction   = chairMemPosAction   ?: "--"
        chairMemPosSetFeedback = chairMemPosFeedback ?: "--"
        driverSeatVentLevel    = driverVent          ?: "--"
        passengerSeatVentLevel = passengerVent       ?: "--"
    }

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }

    fun addSeatLog(entry: String) {
        seatActionLog.add(0, entry)
        if (seatActionLog.size > 50) seatActionLog.removeAt(seatActionLog.lastIndex)
    }
}
