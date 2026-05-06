package br.com.redesurftank.havalclimatecontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ClimateStateHolder {

    var vehicleConnected by mutableStateOf(false)
    var autoEnable       by mutableStateOf("--")
    var insideTemp       by mutableStateOf("--")
    var driverTemp       by mutableStateOf("--")
    var powerMode        by mutableStateOf("--")
    val actionLog        = mutableStateListOf<String>()

    // Toggle properties
    var acEnable              by mutableStateOf("--")
    var frontDefrostEnable    by mutableStateOf("--")
    var heatingEnable         by mutableStateOf("--")
    var intelligentSwitch     by mutableStateOf("--")
    var settingLimitEnable    by mutableStateOf("--")

    // Read-only properties
    var frontTempRange        by mutableStateOf("--")
    var intelligentTempRange  by mutableStateOf("--")
    var pm25Value             by mutableStateOf("--")
    var comfortCurve          by mutableStateOf("--")

    // Set by the service when connected; called by the UI to send a command
    @Volatile var commandCallback: ((String, String) -> Unit)? = null

    fun setCommandCallback(cb: ((String, String) -> Unit)?) {
        commandCallback = cb
    }

    fun sendCommand(key: String, value: String) {
        commandCallback?.invoke(key, value)
    }

    fun updateVehicleData(
        connected: Boolean,
        inside: String?,
        driver: String?,
        power: String?,
        auto: String?
    ) {
        vehicleConnected = connected
        insideTemp       = inside ?: "--"
        driverTemp       = driver ?: "--"
        powerMode        = power  ?: "--"
        autoEnable       = auto   ?: "--"
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
        comfort: String?
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
    }

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }
}
