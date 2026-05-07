package br.com.redesurftank.havalclimatecontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.com.redesurftank.havalclimatecontrol.ui.theme.HavalClimateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-climate-control/releases/latest"
private const val UI_PREFS                  = "climate_ui_prefs"
private const val KEY_AUTO_CONTROL          = "auto_control_enabled"
private const val KEY_LAST_UPDATE_CHECK     = "last_update_check_ms"
private const val UPDATE_CHECK_INTERVAL_MS  = 24 * 60 * 60 * 1000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalClimateControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    AppRoot()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Root navigation
// ─────────────────────────────────────────────────────────────

@Composable
fun AppRoot() {
    var currentScreen by remember { mutableStateOf("main") }
    when (currentScreen) {
        "main"    -> MainControlScreen(
                         onNavigateToDebug    = { currentScreen = "debug" },
                         onNavigateToAssento  = { currentScreen = "assento" }
                     )
        "debug"   -> DebugScreen(onNavigateBack = { currentScreen = "main" })
        "assento" -> AssentoScreen(onNavigateBack = { currentScreen = "main" })
    }
}

// ─────────────────────────────────────────────────────────────
// Main Control Screen — "Controle Automático de Conforto"
// ─────────────────────────────────────────────────────────────

@Composable
fun MainControlScreen(onNavigateToDebug: () -> Unit, onNavigateToAssento: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = ClimateStateHolder
    val prefs   = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

    var currentVersion   by remember { mutableStateOf("--") }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var showErrDialog    by remember { mutableStateOf(false) }
    var errDialogText    by remember { mutableStateOf("") }
    var showPermDialog   by remember { mutableStateOf(false) }
    var downloadJob      by remember { mutableStateOf<Job?>(null) }

    var autoControlEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_AUTO_CONTROL, true))
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // On start: sync toggle with holder + silent 24h update check
    LaunchedEffect(Unit) {
        state.autoControlEnabled = autoControlEnabled

        try {
            currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}

        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.connectTimeout = 10_000
                    conn.readTimeout    = 10_000
                    if (conn.responseCode == 200) {
                        val json   = JSONObject(conn.inputStream.bufferedReader().readText())
                        val tag    = json.getString("tag_name")
                        val assets = json.getJSONArray("assets")
                        var dlUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.getString("name").endsWith(".apk")) {
                                dlUrl = a.getString("browser_download_url"); break
                            }
                        }
                        withContext(Dispatchers.Main) {
                            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                            if (dlUrl != null && compareVersions(tag, currentVersion) > 0) {
                                latestVersion   = tag
                                downloadUrl     = dlUrl
                                updateAvailable = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background update check failed: ${e.message}")
                }
            }
        }
    }

    fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermDialog = true; return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun startDownload() {
        isDownloading = true; downloadProgress = 0f
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val conn  = URL(downloadUrl).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val buf   = ByteArray(4096)
                var bytes = 0; var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(conn.inputStream).use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); bytes += read
                            if (total > 0) downloadProgress = bytes.toFloat() / total
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isDownloading = false; updateAvailable = false; installApk(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    errDialogText = "Erro no download: ${e.message}"
                    showErrDialog = true
                }
            }
        }
    }

    // Derived state
    val isAcOn       = state.powerMode == "1"
    val comfortLabel = when (state.comfortCurve) {
        "0"  -> "Suave"
        "1"  -> "Normal"
        "2"  -> "Forte"
        else -> state.comfortCurve
    }
    val comfortColor = when (state.comfortCurve) {
        "0"  -> Color(0xFF81C784)
        "1"  -> Color(0xFFFFB74D)
        "2"  -> Color(0xFFEF5350)
        else -> Color(0xFF888888)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // ── Header ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "Controle Automático\nde Conforto",
                    fontSize    = 20.sp,
                    fontWeight  = FontWeight.Bold,
                    color       = Color.White,
                    lineHeight  = 27.sp
                )
                Text("v$currentVersion", fontSize = 11.sp, color = Color(0xFF555555))
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusDot(connected = state.vehicleConnected)
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(
                        onClick        = onNavigateToDebug,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors         = ButtonDefaults.textButtonColors(contentColor = Color(0xFF555555))
                    ) { Text("HVAC ›", fontSize = 11.sp) }
                    TextButton(
                        onClick        = onNavigateToAssento,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors         = ButtonDefaults.textButtonColors(contentColor = Color(0xFF555555))
                    ) { Text("Assento ›", fontSize = 11.sp) }
                }
            }
        }

        // ── Update banner (only when a new version is found) ────
        if (updateAvailable) {
            Button(
                onClick        = { startDownload() },
                enabled        = !isDownloading,
                modifier       = Modifier.fillMaxWidth(),
                colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                shape          = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (isDownloading) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Baixando $latestVersion… ${(downloadProgress * 100).toInt()}%",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color    = Color.White
                        )
                    }
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Atualizar para $latestVersion",
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── On/Off toggle card ───────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(
                containerColor = if (autoControlEnabled) Color(0xFF0D2B0D) else Color(0xFF1E1E1E)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Controle Automático",
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (autoControlEnabled) "Ativo — monitorando temperatura" else "Inativo",
                        fontSize = 12.sp,
                        color    = if (autoControlEnabled) Color(0xFF69F0AE) else Color(0xFF888888)
                    )
                }
                Switch(
                    checked         = autoControlEnabled,
                    onCheckedChange = { enabled ->
                        autoControlEnabled         = enabled
                        state.autoControlEnabled   = enabled
                        prefs.edit().putBoolean(KEY_AUTO_CONTROL, enabled).apply()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White,
                        checkedTrackColor   = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color(0xFF888888),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }
        }

        // ── Info cards — row 1 ──────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                modifier    = Modifier.weight(1f),
                label       = "Temp. Interna",
                value       = formatTemp(state.insideTemp),
                valueColor  = Color(0xFFFFB74D)
            )
            InfoCard(
                modifier    = Modifier.weight(1f),
                label       = "Temp. Setada",
                value       = formatTemp(state.driverTemp),
                valueColor  = Color(0xFF64B5F6)
            )
        }

        // ── Info cards — row 2 ──────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                modifier       = Modifier.weight(1f),
                label          = "Estado do AC",
                value          = when (state.powerMode) {
                    "1"  -> "Ligado"
                    "0"  -> "Desligado"
                    else -> "--"
                },
                valueColor     = if (isAcOn) Color(0xFF00BCD4) else Color(0xFF757575),
                valueFontSize  = 20
            )
            InfoCard(
                modifier       = Modifier.weight(1f),
                label          = "Modo Conforto",
                value          = if (state.comfortCurve == "--") "--" else comfortLabel,
                valueColor     = comfortColor,
                valueFontSize  = 20
            )
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────
    if (showErrDialog) {
        AlertDialog(
            onDismissRequest = { showErrDialog = false },
            title = { Text("Erro") },
            text  = { Text(errDialogText) },
            confirmButton = { TextButton(onClick = { showErrDialog = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor  = Color(0xFFCCCCCC)
        )
    }
    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Permissão necessária") },
            text  = { Text("Para instalar o app é necessário habilitar a instalação de fontes desconhecidas nas configurações.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDialog = false
                    permLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }) { Text("Abrir Configurações") }
            },
            dismissButton    = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor   = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor  = Color(0xFFCCCCCC)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Debug Screen (tela original de diagnóstico)
// ─────────────────────────────────────────────────────────────

@Composable
fun DebugScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = ClimateStateHolder

    var currentVersion   by remember { mutableStateOf("--") }
    var isChecking       by remember { mutableStateOf(false) }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var updateMessage    by remember { mutableStateOf("") }
    var showMsgDialog    by remember { mutableStateOf(false) }
    var showPermDialog   by remember { mutableStateOf(false) }
    var downloadJob      by remember { mutableStateOf<Job?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        try {
            currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermDialog = true; return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun startDownload() {
        isDownloading = true; downloadProgress = 0f
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val conn  = URL(downloadUrl).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val buf   = ByteArray(4096)
                var bytes = 0; var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(conn.inputStream).use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); bytes += read
                            if (total > 0) downloadProgress = bytes.toFloat() / total
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isDownloading = false; updateAvailable = false; installApk(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    updateMessage = "Erro no download: ${e.message}"
                    showMsgDialog = true
                }
            }
        }
    }

    fun checkForUpdates() {
        isChecking = true
        scope.launch(Dispatchers.IO) {
            try {
                val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                val json   = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag    = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                var dlUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        dlUrl = a.getString("browser_download_url"); break
                    }
                }
                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (dlUrl != null && compareVersions(tag, currentVersion) > 0) {
                        latestVersion = tag; downloadUrl = dlUrl; updateAvailable = true
                    } else {
                        updateMessage = "Você já está na versão mais recente ($currentVersion)"
                        showMsgDialog = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                withContext(Dispatchers.Main) {
                    isChecking = false
                    updateMessage = "Erro ao verificar atualizações: ${e.message}"
                    showMsgDialog = true
                }
            }
        }
    }

    val isAuto    = state.autoEnable == "1"
    val isAcOn    = state.powerMode  == "1"
    val acColor   = if (isAcOn)  Color(0xFF00BCD4) else Color(0xFF757575)
    val autoColor = if (isAuto) Color(0xFF4CAF50)  else Color(0xFFFF5722)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Header
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint               = Color.White,
                        modifier           = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text("Debug", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("v$currentVersion", fontSize = 11.sp, color = Color(0xFF666666))
                }
            }
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick        = { checkForUpdates() },
                    enabled        = !isChecking && !isDownloading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape          = RoundedCornerShape(8.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            color       = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Verificando...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Atualizar", fontSize = 12.sp)
                    }
                }
                StatusDot(connected = state.vehicleConnected)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TempCard(Modifier.weight(1f), "Temperatura Interna", formatTemp(state.insideTemp), Color(0xFFFFB74D))
            TempCard(Modifier.weight(1f), "Temperatura Setada",  formatTemp(state.driverTemp),  Color(0xFF64B5F6))
            StatusCard(Modifier.weight(1f), "Modo AC",
                if (state.autoEnable == "--") "--" else if (isAuto) "Automático" else "Manual", autoColor)
            StatusCard(Modifier.weight(1f), "Estado AC",
                if (state.powerMode == "--") "--" else if (isAcOn) "Ligado" else "Desligado", acColor)
        }

        Spacer(Modifier.height(12.dp))

        Text("Controles HVAC", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HvacToggle("power_mode",     state.powerMode,          "car.hvac.power_mode")
            HvacToggle("ac_enable",      state.acEnable,           "car.hvac.ac_enable")
            HvacToggle("front_defrost",  state.frontDefrostEnable, "car.hvac.front_defrost_enable")
            HvacToggle("heating_enable", state.heatingEnable,      "car.hvac.heating_enable")
            HvacToggle("intelligent_sw", state.intelligentSwitch,  "car.hvac.Intelligent_switch_enable")
            HvacToggle("limit_enable",   state.settingLimitEnable, "car.hvac.setting.limit_enable")
        }

        Spacer(Modifier.height(10.dp))

        Text("Sensores HVAC", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HvacReadOnly(Modifier.weight(1f), "front_temp_range",  state.frontTempRange)
            HvacReadOnly(Modifier.weight(1f), "intelligent_range", state.intelligentTempRange)
            HvacReadOnly(Modifier.weight(1f), "pm2.5_value",       state.pm25Value)
            HvacReadOnly(Modifier.weight(1f), "comfort_curve",     state.comfortCurve)
        }

        Spacer(Modifier.height(10.dp))

        Text("Histórico de Ações", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(6.dp))

        if (state.actionLog.isEmpty()) {
            Box(
                modifier        = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.vehicleConnected)
                        "Nenhuma ação registrada ainda.\nAC será controlado quando o modo Automático estiver ativo."
                    else "Aguardando conexão com o veículo...",
                    color    = Color(0xFF666666),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.actionLog.toList()) { entry ->
                    Text(entry, fontSize = 12.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace)
                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                }
            }
        }
    }

    if (showMsgDialog) {
        AlertDialog(
            onDismissRequest  = { showMsgDialog = false },
            title             = { Text("Atualização") },
            text              = { Text(updateMessage) },
            confirmButton     = { TextButton(onClick = { showMsgDialog = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor  = Color(0xFFCCCCCC)
        )
    }
    if (updateAvailable) {
        AlertDialog(
            onDismissRequest = { updateAvailable = false },
            title  = { Text("Atualização disponível") },
            text   = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nova versão disponível: $latestVersion\nVersão atual: $currentVersion")
                    if (isDownloading) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Baixando… ${(downloadProgress * 100).toInt()}%",
                            fontSize = 13.sp,
                            color    = Color(0xFF4FC3F7)
                        )
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color    = Color(0xFF4FC3F7)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick  = { if (!isDownloading) startDownload() },
                    enabled  = !isDownloading
                ) { Text(if (isDownloading) "Baixando..." else "Baixar e Instalar") }
            },
            dismissButton = {
                TextButton(onClick = { updateAvailable = false; downloadJob?.cancel() }) { Text("Cancelar") }
            },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor  = Color(0xFFCCCCCC)
        )
    }
    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Permissão necessária") },
            text  = { Text("Para instalar o app é necessário habilitar a instalação de fontes desconhecidas nas configurações.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDialog = false
                    permLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }) { Text("Abrir Configurações") }
            },
            dismissButton     = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor  = Color(0xFFCCCCCC)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Assento Debug Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun AssentoScreen(onNavigateBack: () -> Unit) {
    val state = ClimateStateHolder

    // Definição de todas as propriedades monitoradas
    data class SeatProp(
        val label    : String,
        val propKey  : String,
        val value    : String,
        val sendValues: List<String>?,  // null = read-only
        val isBoolean: Boolean = false
    )

    val props = listOf(
        SeatProp(
            label      = "chair_memory.auto_enable",
            propKey    = "car.comfort_setting.chair_memory.auto_enable",
            value      = state.chairMemoryAutoEnable,
            sendValues = listOf("0", "1"),
            isBoolean  = true
        ),
        SeatProp(
            label      = "ass_memory_setting",
            propKey    = "car.configure.ass_memory_setting",
            value      = state.assMemorySetting,
            sendValues = listOf("0", "1", "2", "3")
        ),
        SeatProp(
            label      = "chair_mem_pos_set_action",
            propKey    = "car.comfort_setting.chair_mem_pos_set_action",
            value      = state.chairMemPosSetAction,
            sendValues = listOf("1", "2", "3")
        ),
        SeatProp(
            label      = "chair_mem_pos_set_feedback",
            propKey    = "car.comfort_setting.chair_mem_pos_set_feedback",
            value      = state.chairMemPosSetFeedback,
            sendValues = null   // read-only
        )
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // ── Header ─────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint               = Color.White,
                        modifier           = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text("Assento", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Monitoramento de memória de assento", fontSize = 11.sp, color = Color(0xFF666666))
                }
            }
            StatusDot(connected = state.vehicleConnected)
        }

        Spacer(Modifier.height(20.dp))

        // ── Cards de propriedades ───────────────────────────
        props.forEach { prop ->
            SeatPropCard(
                label      = prop.label,
                propKey    = prop.propKey,
                value      = prop.value,
                sendValues = prop.sendValues,
                isBoolean  = prop.isBoolean
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(6.dp))

        // ── Histórico de ações ──────────────────────────────
        Text(
            "Histórico de Ações",
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color(0xFFAAAAAA)
        )
        Spacer(Modifier.height(6.dp))

        if (state.seatActionLog.isEmpty()) {
            Box(
                modifier         = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text      = "Nenhum comando enviado ainda.",
                    color     = Color(0xFF666666),
                    fontSize  = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.seatActionLog.toList()) { entry ->
                    Text(
                        entry,
                        fontSize   = 12.sp,
                        color      = Color(0xFFCCCCCC),
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun SeatPropCard(
    label      : String,
    propKey    : String,
    value      : String,
    sendValues : List<String>?,
    isBoolean  : Boolean = false
) {
    val isUnknown = value == "--"
    val isOn      = value == "1"

    val valueColor = when {
        isUnknown          -> Color(0xFF555555)
        isBoolean && isOn  -> Color(0xFF69F0AE)
        isBoolean && !isOn -> Color(0xFFB39DDB)
        else               -> Color(0xFF64B5F6)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = if (isBoolean && isOn && !isUnknown) Color(0xFF0D2B0D) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Label + valor atual
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = label,
                        fontSize   = 11.sp,
                        color      = Color(0xFF888888),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text       = if (isUnknown) "--"
                                     else if (isBoolean) if (isOn) "ON (1)" else "OFF (0)"
                                     else value,
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color      = valueColor
                    )
                }
                if (sendValues == null) {
                    Text(
                        "somente leitura",
                        fontSize = 10.sp,
                        color    = Color(0xFF444444)
                    )
                }
            }

            // Botões de envio
            if (sendValues != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
                    sendValues.forEach { v ->
                        val isCurrent = value == v
                        Button(
                            onClick = {
                                ClimateStateHolder.sendCommand(propKey, v)
                                val display = if (isBoolean) (if (v == "1") "ON" else "OFF") else v
                                ClimateStateHolder.addSeatLog(
                                    timeFmt.format(java.util.Date()) + "  $label → $display"
                                )
                            },
                            colors         = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrent) Color(0xFF1B5E20) else Color(0xFF2A2A2A)
                            ),
                            shape          = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier       = Modifier.height(34.dp)
                        ) {
                            Text(
                                text      = if (isBoolean) (if (v == "1") "ON" else "OFF") else v,
                                fontSize  = 13.sp,
                                color     = if (isCurrent) Color(0xFF69F0AE) else Color(0xFF888888),
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeatValueCard(modifier: Modifier = Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier            = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp, color = Color(0xFF888888), textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Text(
                text       = value,
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                color      = if (value == "--") Color(0xFF555555) else Color(0xFF64B5F6),
                textAlign  = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────────────────────

@Composable
fun InfoCard(
    modifier      : Modifier = Modifier,
    label         : String,
    value         : String,
    valueColor    : Color,
    valueFontSize : Int = 28
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier             = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment  = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                fontSize   = valueFontSize.sp,
                fontWeight = FontWeight.Bold,
                color      = valueColor,
                textAlign  = TextAlign.Center
            )
        }
    }
}

@Composable
fun HvacToggle(label: String, value: String, propKey: String) {
    val isOn      = value == "1"
    val isUnknown = value == "--"
    val bgColor = when {
        isUnknown -> Color(0xFF2A2A2A)
        isOn      -> Color(0xFF1B5E20)
        else      -> Color(0xFF311B92)
    }
    val textColor = when {
        isUnknown -> Color(0xFF888888)
        isOn      -> Color(0xFF69F0AE)
        else      -> Color(0xFFB39DDB)
    }
    Button(
        onClick        = { if (!isUnknown) ClimateStateHolder.sendCommand(propKey, if (isOn) "0" else "1") },
        enabled        = !isUnknown,
        colors         = ButtonDefaults.buttonColors(
            containerColor         = bgColor,
            disabledContainerColor = Color(0xFF2A2A2A)
        ),
        shape          = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = Color(0xFFAAAAAA), textAlign = TextAlign.Center)
            Text(
                text       = when (value) { "1" -> "ON"; "0" -> "OFF"; else -> "--" },
                fontSize   = 14.sp,
                fontWeight = FontWeight.Bold,
                color      = textColor
            )
        }
    }
}

@Composable
fun HvacReadOnly(modifier: Modifier = Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun TempCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun StatusCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
fun StatusDot(connected: Boolean) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(10.dp).background(
                color = if (connected) Color(0xFF4CAF50) else Color(0xFF757575),
                shape = RoundedCornerShape(50)
            )
        )
        Text(
            text     = if (connected) "Conectado" else "Aguardando",
            fontSize = 12.sp,
            color    = if (connected) Color(0xFF4CAF50) else Color(0xFF757575)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────

private fun compareVersions(v1: String, v2: String): Int {
    val p1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val p2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until minOf(p1.size, p2.size)) {
        if (p1[i] > p2[i]) return 1
        if (p1[i] < p2[i]) return -1
    }
    return p1.size.compareTo(p2.size)
}

private fun formatTemp(value: String) =
    try { "%.1f°C".format(value.toFloat()) } catch (_: Exception) { value }
