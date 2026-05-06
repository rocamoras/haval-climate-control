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

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }
}
