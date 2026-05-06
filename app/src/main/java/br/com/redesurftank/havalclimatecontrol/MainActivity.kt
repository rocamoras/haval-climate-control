package br.com.redesurftank.havalclimatecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService
import br.com.redesurftank.havalclimatecontrol.ui.theme.HavalClimateControlTheme

class MainActivity : ComponentActivity() {

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateState(intent)
        }
    }

    private var serviceReady = mutableStateOf(false)
    private var autoEnable   = mutableStateOf("--")
    private var insideTemp   = mutableStateOf("--")
    private var driverTemp   = mutableStateOf("--")
    private var powerMode    = mutableStateOf("--")
    private var actionLog    = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalClimateControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    MonitoringScreen(
                        serviceReady = serviceReady.value,
                        autoEnable   = autoEnable.value,
                        insideTemp   = insideTemp.value,
                        driverTemp   = driverTemp.value,
                        powerMode    = powerMode.value,
                        actionLog    = actionLog
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(ClimateControlService.ACTION_STATUS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Ask service for current state
        sendBroadcast(Intent(ClimateControlService.ACTION_REQUEST_STATUS).apply {
            setPackage(packageName)
        })
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) { /* ignore */ }
    }

    private fun updateState(intent: Intent) {
        serviceReady.value = intent.getBooleanExtra(ClimateControlService.EXTRA_SERVICE_READY, false)
        autoEnable.value   = intent.getStringExtra(ClimateControlService.EXTRA_AUTO_ENABLE)   ?: "--"
        insideTemp.value   = intent.getStringExtra(ClimateControlService.EXTRA_INSIDE_TEMP)   ?: "--"
        driverTemp.value   = intent.getStringExtra(ClimateControlService.EXTRA_DRIVER_TEMP)   ?: "--"
        powerMode.value    = intent.getStringExtra(ClimateControlService.EXTRA_POWER_MODE)    ?: "--"
        intent.getStringExtra(ClimateControlService.EXTRA_ACTION_LOG)?.let { log ->
            actionLog.add(0, log)
            if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
        }
    }
}

@Composable
fun MonitoringScreen(
    serviceReady: Boolean,
    autoEnable: String,
    insideTemp: String,
    driverTemp: String,
    powerMode: String,
    actionLog: List<String>
) {
    val isAuto   = autoEnable == "1"
    val isAcOn   = powerMode == "1"
    val acColor  = if (isAcOn) Color(0xFF00BCD4) else Color(0xFF757575)
    val autoColor = if (isAuto) Color(0xFF4CAF50) else Color(0xFFFF5722)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Controle Climático",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            StatusDot(connected = serviceReady)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Temperature cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TempCard(
                modifier = Modifier.weight(1f),
                label = "Temperatura Interna",
                value = formatTemp(insideTemp),
                color = Color(0xFFFFB74D)
            )
            TempCard(
                modifier = Modifier.weight(1f),
                label = "Temperatura Setada",
                value = formatTemp(driverTemp),
                color = Color(0xFF64B5F6)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                modifier = Modifier.weight(1f),
                label = "Modo AC",
                value = if (isAuto) "Automático" else "Manual",
                color = autoColor
            )
            StatusCard(
                modifier = Modifier.weight(1f),
                label = "Estado AC",
                value = if (isAcOn) "Ligado" else "Desligado",
                color = acColor
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action log
        Text(
            text = "Histórico de Ações",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFAAAAAA)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (actionLog.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (serviceReady) "Nenhuma ação registrada ainda"
                           else "Aguardando conexão com o veículo...",
                    color = Color(0xFF666666),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(actionLog) { entry ->
                    Text(
                        text = entry,
                        fontSize = 12.sp,
                        color = Color(0xFFCCCCCC),
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun TempCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun StatusCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
fun StatusDot(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (connected) Color(0xFF4CAF50) else Color(0xFF757575),
                    shape = RoundedCornerShape(50)
                )
        )
        Text(
            text = if (connected) "Conectado" else "Aguardando",
            fontSize = 12.sp,
            color = if (connected) Color(0xFF4CAF50) else Color(0xFF757575)
        )
    }
}

private fun formatTemp(value: String): String {
    return try {
        val f = value.toFloat()
        String.format("%.1f°C", f)
    } catch (e: Exception) {
        value
    }
}
