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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.com.redesurftank.havalclimatecontrol.ui.theme.HavalClimateControlTheme
import br.com.redesurftank.havalclimatecontrol.utils.FridaUtils
import br.com.redesurftank.havalclimatecontrol.utils.SystemPropsUtils
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
private const val UI_PREFS                 = "climate_ui_prefs"
private const val KEY_AUTO_CONTROL         = "auto_control_enabled"
private const val KEY_LAST_UPDATE_CHECK    = "last_update_check_ms"
private const val KEY_SEAT_VENT_AUTO       = "seat_vent_auto_enabled"
private const val KEY_COMFORT_MODE         = "comfort_mode"
private const val KEY_REAL_OUTSIDE_TEMP    = "real_outside_temp_enabled"
private const val KEY_HOME_CARD            = "home_card_enabled"
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

// ─────────────────────────────────────────────────────────────
// HMI color tokens — monochromatic dark, accent only for active
// ─────────────────────────────────────────────────────────────
private val HmiBg         = Color(0xFF000000)
private val HmiSurface    = Color(0xFF141414)
private val HmiSurface2   = Color(0xFF1C1C1C)
private val HmiFg         = Color(0xFFFAFAFA)
private val HmiFgMuted    = Color(0xFFA3A3A3)
private val HmiFgDim      = Color(0xFF6B6B6B)
private val HmiFgFaint    = Color(0xFF404040)
private val HmiAccent     = Color(0xFF22C55E)
private val HmiAccentSoft = Color(0x1F22C55E)
private val HmiAccentEdge = Color(0x6622C55E)
private val HmiBorder     = Color(0x12FFFFFF)
private val HmiBorderStr  = Color(0x1FFFFFFF)

// ─────────────────────────────────────────────────────────────
// Constantes reutilizáveis — evitam realocação por recomposição/frame
// ─────────────────────────────────────────────────────────────
private val airflowDashInterval = floatArrayOf(12f, 16f)
private val comfortModeList      = listOf("AUTO", "SUAVE", "NORMAL", "FORTE")

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
private val PM25_RANGES   = arrayOf("> 250", "151–250", "116–150", "76–115", "36–75", "0–35")
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

// ─────────────────────────────────────────────────────────────
// System properties de configuração de variante (persist.vendor.gwm.cfg.*)
// ─────────────────────────────────────────────────────────────

/** Gate de HVACSystemPropertiesUtil.hasAutoDemist(): mostra a linha
 *  "Desembaçador dianteiro automático" nas Configurações do HVAC quando == 1. */
private const val PROP_AUTO_DEMIST = "persist.vendor.gwm.cfg.indoor.automatic.demisting"

/** Gate de hasAirPMSystem(): o HVAC só exibe a qualidade do ar quando ∈ {2,3,5}. */
private const val PROP_AIR_PURIFIER = "persist.vendor.gwm.cfg.air.purifier"

private const val HVAC_PKG = "com.beantechs.hvac"

private val pm25TimeFmt = SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

/** Desenha um traço de airflow reaproveitando o mesmo Path/Paint (zero alocação por traço). */
private fun drawAirflow(
    canvas: android.graphics.Canvas,
    path: android.graphics.Path,
    paint: android.graphics.Paint,
    sx: Float, sy: Float, ex: Float, ey: Float
) {
    path.rewind()
    path.moveTo(sx, sy)
    path.quadTo((sx + ex) / 2f, sy + (ey - sy) * 0.35f, ex, ey)
    canvas.drawPath(path, paint)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalClimateControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = HmiBg) {
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
        "main"       -> MainControlScreen(
                            onNavigateToDebug      = { currentScreen = "debug" },
                            onNavigateToScreenInfo = { currentScreen = "screeninfo" },
                            onNavigateToSettings   = { currentScreen = "settings" }
                        )
        "debug"      -> DebugScreen(onNavigateBack = { currentScreen = "main" })
        "screeninfo" -> ScreenInfoScreen(onNavigateBack = { currentScreen = "main" })
        "settings"   -> SettingsScreen(onNavigateBack = { currentScreen = "main" })
    }
}

// ─────────────────────────────────────────────────────────────
// Main Control Screen — HMI wide layout (1792×660dp)
// ─────────────────────────────────────────────────────────────

@Composable
fun MainControlScreen(
    onNavigateToDebug: () -> Unit,
    onNavigateToScreenInfo: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
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
    var seatVentAutoEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_SEAT_VENT_AUTO, true))
    }
    var comfortMode by remember {
        mutableStateOf(prefs.getString(KEY_COMFORT_MODE, "AUTO") ?: "AUTO")
    }
    var realOutsideTempEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_REAL_OUTSIDE_TEMP, false))
    }
    var devMenuVisible by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        state.autoControlEnabled    = autoControlEnabled
        state.seatVentAutoEnabled   = seatVentAutoEnabled
        state.comfortMode           = comfortMode
        state.realOutsideTempEnabled = realOutsideTempEnabled

        // Quando o serviço detecta alteração externa na ventilação, desativa AUTO e persiste
        state.onExternalVentChange = { _ ->
            seatVentAutoEnabled = false
            prefs.edit().putBoolean(KEY_SEAT_VENT_AUTO, false).apply()
        }
        // Quando o serviço detecta alteração externa na curva de conforto, muda para modo manual e persiste
        state.onExternalComfortChange = { newMode ->
            comfortMode = newMode
            prefs.edit().putString(KEY_COMFORT_MODE, newMode).apply()
        }

        try {
            currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}

        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    val c = (URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection)
                        .also { conn = it }
                    c.requestMethod = "GET"
                    c.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    c.connectTimeout = 10_000
                    c.readTimeout    = 10_000
                    if (c.responseCode == 200) {
                        val json   = JSONObject(c.inputStream.bufferedReader().readText())
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
                } finally {
                    conn?.disconnect()
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
            var conn: HttpURLConnection? = null
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val c     = (URL(downloadUrl).openConnection() as HttpURLConnection).also { conn = it }
                val total = c.contentLength
                val buf   = ByteArray(4096)
                var bytes = 0; var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(c.inputStream).use { inp ->
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
            } finally {
                conn?.disconnect()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HmiBg)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        HmiHeader(
            currentVersion         = currentVersion,
            devMenuVisible         = devMenuVisible,
            onVersionDoubleTap     = { devMenuVisible = !devMenuVisible },
            onNavigateToDebug      = onNavigateToDebug,
            onNavigateToScreenInfo = onNavigateToScreenInfo,
            onNavigateToSettings   = onNavigateToSettings
        )

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
                        Text("Baixando $latestVersion… ${(downloadProgress * 100).toInt()}%", fontSize = 16.sp)
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
                    Text("Atualizar para $latestVersion", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 3-column hero
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AutoMasterCard(
                modifier = Modifier.width(380.dp).fillMaxHeight(),
                enabled  = autoControlEnabled,
                onToggle = { enabled ->
                    autoControlEnabled       = enabled
                    state.autoControlEnabled = enabled
                    prefs.edit().putBoolean(KEY_AUTO_CONTROL, enabled).apply()
                }
            )
            CarVisualizationCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                autoOn   = autoControlEnabled
            )
            TempColumn(
                modifier = Modifier.width(380.dp).fillMaxHeight()
            )
        }

        // Bottom info strip
        HmiInfoStripRow(
            state               = state,
            comfortMode         = comfortMode,
            onCycleComfort      = {
                val next = comfortModeList[(comfortModeList.indexOf(comfortMode) + 1) % comfortModeList.size]
                comfortMode       = next
                state.comfortMode = next
                prefs.edit().putString(KEY_COMFORT_MODE, next).apply()
            },
            seatVentAutoEnabled = seatVentAutoEnabled,
            onToggleSeatVent    = {
                val next = !seatVentAutoEnabled
                seatVentAutoEnabled       = next
                state.seatVentAutoEnabled = next
                prefs.edit().putBoolean(KEY_SEAT_VENT_AUTO, next).apply()
                if (!next) {
                    state.sendCommand("car.comfort_setting.driver_seat_ventilation_level",    "0")
                    state.sendCommand("car.comfort_setting.passenger_seat_ventilation_level", "0")
                }
            }
        )
    }

    if (showErrDialog) {
        AlertDialog(
            onDismissRequest  = { showErrDialog = false },
            title             = { Text("Erro") },
            text              = { Text(errDialogText) },
            confirmButton     = { TextButton(onClick = { showErrDialog = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
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
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// HMI composables — Tela Principal
// ─────────────────────────────────────────────────────────────

@Composable
private fun HmiHeader(
    currentVersion: String,
    devMenuVisible: Boolean,
    onVersionDoubleTap: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToScreenInfo: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    // Lido aqui (não na MainControlScreen) para que só o cabeçalho recomponha ao conectar/desconectar.
    val connected = ClimateStateHolder.vehicleConnected
    Row(
        modifier              = Modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // Brand
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(HmiSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, HmiBorderStr, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("H", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HmiFg)
            }
            Text(
                "HAVAL · CLIMATE CONTROL",
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = HmiFgMuted,
                letterSpacing = 2.sp
            )
        }

        // Nav tabs — dev tabs hidden by default, revealed by double-tapping the version
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HmiNavTab(label = "Principal", number = "01", active = true, onClick = {})
            if (devMenuVisible) {
                HmiNavTab(label = "HVAC",    number = "02", active = false, onClick = onNavigateToDebug)
                HmiNavTab(label = "Tela",    number = "03", active = false, onClick = onNavigateToScreenInfo)
            }
        }

        // Status cluster
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .border(1.dp, HmiBorder, RoundedCornerShape(999.dp))
                    .background(HmiSurface, RoundedCornerShape(999.dp))
                    .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (connected) HmiAccent else HmiFgDim,
                            CircleShape
                        )
                )
                Text(
                    if (connected) "online" else "offline",
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = HmiFgMuted,
                    letterSpacing = 1.5.sp
                )
            }
            Text(
                "v$currentVersion",
                fontSize      = 11.sp,
                color         = if (devMenuVisible) HmiAccent else HmiFgDim,
                fontFamily    = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                modifier      = Modifier.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onVersionDoubleTap() })
                }
            )
            // Engrenagem — acesso ao menu de Configurações (sempre visível)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(HmiSurface, RoundedCornerShape(8.dp))
                    .border(1.dp, HmiBorderStr, RoundedCornerShape(8.dp))
                    .clickable(onClick = onNavigateToSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Configurações",
                    tint     = HmiFgMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun HmiNavTab(label: String, number: String, active: Boolean, onClick: () -> Unit) {
    val hPad = if (active) 15.dp else 16.dp
    val vPad = if (active) 7.dp  else 8.dp
    Row(
        modifier = Modifier
            .background(if (active) HmiSurface2 else Color.Transparent, RoundedCornerShape(999.dp))
            .then(
                if (active) Modifier.border(1.dp, HmiBorderStr, RoundedCornerShape(999.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            number,
            fontSize      = 11.sp,
            color         = if (active) HmiFgMuted else HmiFgFaint,
            fontFamily    = FontFamily.Monospace,
            letterSpacing = 0.sp
        )
        Text(
            label,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            color      = if (active) HmiFg else HmiFgDim
        )
    }
}

@Composable
private fun AutoMasterCard(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val bgBrush = if (enabled)
        Brush.verticalGradient(listOf(Color(0x1A22C55E), Color(0x0522C55E)))
    else
        Brush.verticalGradient(listOf(HmiSurface, Color(0xFF0A0A0A)))

    Box(
        modifier = modifier
            .background(bgBrush, RoundedCornerShape(22.dp))
            .border(1.dp, if (enabled) HmiAccentEdge else HmiBorderStr, RoundedCornerShape(22.dp))
            .clickable { onToggle(!enabled) }
            .padding(26.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Eyebrow + LED
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "SERVIÇO",
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.SemiBold,
                    color         = HmiFgDim,
                    letterSpacing = 2.5.sp
                )
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(if (enabled) HmiAccent else HmiFgFaint, CircleShape)
                    )
                    Text(
                        if (enabled) "ON" else "OFF",
                        fontSize      = 10.sp,
                        fontWeight    = FontWeight.SemiBold,
                        color         = if (enabled) HmiAccent else HmiFgDim,
                        fontFamily    = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }
            }

            // Icon + title
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                if (enabled) HmiAccentSoft else HmiSurface2,
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                if (enabled) HmiAccentEdge else HmiBorder,
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Autorenew,
                            contentDescription = null,
                            tint     = if (enabled) HmiAccent else HmiFgMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        "Controle\nAutomático",
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = HmiFg,
                        lineHeight = 30.sp,
                        letterSpacing = (-0.3).sp
                    )
                }
                Text(
                    if (enabled) "Sistema ativo — gerenciando AC e ventilação"
                    else "Desativado — controle manual via painel HVAC",
                    fontSize   = 13.sp,
                    color      = if (enabled) HmiAccent else HmiFgMuted,
                    lineHeight = 18.sp
                )
            }

            // Toggle pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (enabled) HmiAccentSoft else HmiSurface2,
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        1.dp,
                        if (enabled) HmiAccentEdge else HmiBorderStr,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    if (enabled) "Habilitado" else "Toque para ativar",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color      = if (enabled) HmiFg else HmiFgMuted,
                    letterSpacing = 0.5.sp
                )
                Switch(
                    checked         = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White,
                        checkedTrackColor   = HmiAccent,
                        uncheckedThumbColor = HmiFgMuted,
                        uncheckedTrackColor = HmiSurface
                    )
                )
            }

            // Footer
            Row(
                modifier              = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("modo  AUTO", fontSize = 16.sp, color = HmiFgFaint, fontFamily = FontFamily.Monospace)
                Text("HVAC · 16 props", fontSize = 16.sp, color = HmiFgFaint, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CarVisualizationCard(
    modifier: Modifier = Modifier,
    autoOn: Boolean
) {
    val setpoint = ClimateStateHolder.driverTemp
    val infiniteTransition = rememberInfiniteTransition(label = "airflow")
    val dashOffset by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 56f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dashOffset"
    )

    // Paint e Path reaproveitados entre frames; só o DashPathEffect (offset) muda por frame.
    val airflowPaint = remember {
        android.graphics.Paint().apply {
            color       = android.graphics.Color.argb(140, 255, 255, 255)
            style       = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap   = android.graphics.Paint.Cap.ROUND
        }
    }
    val airflowPath = remember { android.graphics.Path() }

    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Setpoint header
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "TEMP. SETADA",
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.SemiBold,
                    color         = HmiFgDim,
                    letterSpacing = 2.5.sp
                )
                Row(
                    verticalAlignment     = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        try { "%.1f".format(setpoint.toFloat()) } catch (_: Exception) { "--" },
                        fontSize      = 36.sp,
                        fontWeight    = FontWeight.Medium,
                        color         = HmiFg,
                        letterSpacing = (-1).sp,
                        fontFamily    = FontFamily.Monospace
                    )
                    Text(
                        "°C",
                        fontSize  = 19.sp,
                        color     = HmiFgMuted,
                        modifier  = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // Car canvas
            Canvas(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                val w = size.width
                val h = size.height

                val bodyL = w * 0.30f
                val bodyR = w * 0.70f
                val bodyT = h * 0.04f
                val bodyB = h * 0.96f

                // Shadow
                drawOval(
                    color    = Color(0x0AFFFFFF),
                    topLeft  = Offset(bodyL + 10f, bodyB - 6f),
                    size     = Size(bodyR - bodyL - 20f, 12f)
                )

                // Body fill
                drawRoundRect(
                    color        = Color(0xFF1A1A1A),
                    topLeft      = Offset(bodyL, bodyT),
                    size         = Size(bodyR - bodyL, bodyB - bodyT),
                    cornerRadius = CornerRadius(32f)
                )
                // Body stroke
                drawRoundRect(
                    color        = Color(0x14FFFFFF),
                    topLeft      = Offset(bodyL, bodyT),
                    size         = Size(bodyR - bodyL, bodyB - bodyT),
                    cornerRadius = CornerRadius(32f),
                    style        = Stroke(width = 1f)
                )

                // Windshield
                val wsPath = Path().apply {
                    moveTo(bodyL + 22f, bodyT + 20f)
                    quadraticTo((bodyL + bodyR) / 2f, bodyT + 4f, bodyR - 22f, bodyT + 20f)
                    lineTo(bodyR - 28f, h * 0.24f)
                    quadraticTo((bodyL + bodyR) / 2f, h * 0.20f, bodyL + 28f, h * 0.24f)
                    close()
                }
                drawPath(wsPath, color = Color(0x07FFFFFF))
                drawPath(wsPath, color = Color(0x10FFFFFF), style = Stroke(width = 1f))

                // Dash vents
                val ventY  = h * 0.27f
                val ventH  = 5f
                val ventSW = (bodyR - bodyL - 48f) / 4.6f
                for (i in 0..3) {
                    drawRoundRect(
                        color        = Color(0x3DFFFFFF),
                        topLeft      = Offset(bodyL + 24f + i * (ventSW + 5f), ventY),
                        size         = Size(ventSW, ventH),
                        cornerRadius = CornerRadius(2f)
                    )
                }

                // Center console
                val consW = w * 0.055f
                val consX = (bodyL + bodyR) / 2f - consW / 2f
                drawRoundRect(
                    color        = Color(0xFF111111),
                    topLeft      = Offset(consX, h * 0.32f),
                    size         = Size(consW, h * 0.54f),
                    cornerRadius = CornerRadius(6f)
                )
                drawRoundRect(
                    color        = Color(0x0AFFFFFF),
                    topLeft      = Offset(consX, h * 0.32f),
                    size         = Size(consW, h * 0.54f),
                    cornerRadius = CornerRadius(6f),
                    style        = Stroke(width = 1f)
                )

                val seatW = w * 0.13f
                val seatH = h * 0.36f
                val seatT = h * 0.42f

                // Left seat (driver)
                drawRoundRect(
                    color        = Color(0xFF181818),
                    topLeft      = Offset(bodyL + 16f, seatT),
                    size         = Size(seatW, seatH),
                    cornerRadius = CornerRadius(12f)
                )
                drawRoundRect(
                    color        = Color(0x10FFFFFF),
                    topLeft      = Offset(bodyL + 16f, seatT),
                    size         = Size(seatW, seatH),
                    cornerRadius = CornerRadius(12f),
                    style        = Stroke(width = 1f)
                )

                // Right seat (passenger)
                drawRoundRect(
                    color        = Color(0xFF181818),
                    topLeft      = Offset(bodyR - 16f - seatW, seatT),
                    size         = Size(seatW, seatH),
                    cornerRadius = CornerRadius(12f)
                )
                drawRoundRect(
                    color        = Color(0x10FFFFFF),
                    topLeft      = Offset(bodyR - 16f - seatW, seatT),
                    size         = Size(seatW, seatH),
                    cornerRadius = CornerRadius(12f),
                    style        = Stroke(width = 1f)
                )

                // Steering wheel
                val swCx = bodyL + 16f + seatW / 2f
                val swCy = h * 0.35f
                val swR  = 18f
                drawCircle(
                    color  = Color(0x52FFFFFF),
                    radius = swR,
                    center = Offset(swCx, swCy),
                    style  = Stroke(width = 2.5f)
                )
                drawLine(
                    color       = Color(0x4CFFFFFF),
                    start       = Offset(swCx - swR + 4f, swCy),
                    end         = Offset(swCx + swR - 4f, swCy),
                    strokeWidth = 2.5f,
                    cap         = StrokeCap.Round
                )
                drawLine(
                    color       = Color(0x4CFFFFFF),
                    start       = Offset(swCx, swCy + 4f),
                    end         = Offset(swCx, swCy + swR - 2f),
                    strokeWidth = 2.5f,
                    cap         = StrokeCap.Round
                )

                // Airflow animations — única alocação por frame é o DashPathEffect (o offset anima)
                if (autoOn) {
                    airflowPaint.pathEffect = android.graphics.DashPathEffect(airflowDashInterval, dashOffset)
                    val sx0 = bodyL + 24f
                    val sy  = ventY + ventH
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        drawAirflow(nc, airflowPath, airflowPaint, sx0 + ventSW * 0.5f, sy, bodyL + 36f, h * 0.52f)
                        drawAirflow(nc, airflowPath, airflowPaint, sx0 + ventSW * 1.5f, sy, bodyL + 48f, h * 0.58f)
                        drawAirflow(nc, airflowPath, airflowPaint, sx0 + ventSW * 2.5f, sy, bodyR - 48f, h * 0.52f)
                        drawAirflow(nc, airflowPath, airflowPaint, sx0 + ventSW * 3.5f, sy, bodyR - 36f, h * 0.58f)
                    }
                }
            }

            // Status pill
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier
                        .background(HmiSurface2, RoundedCornerShape(999.dp))
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (autoOn) {
                        Box(Modifier.size(7.dp).background(HmiAccent, CircleShape))
                    }
                    Text(
                        if (autoOn) "MONITORANDO" else "PARADO",
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.Medium,
                        color         = HmiFgMuted,
                        fontFamily    = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TempColumn(
    modifier: Modifier = Modifier
) {
    // Lido aqui (não na MainControlScreen) para que só esta coluna recomponha ao mudar temperatura.
    val insideTemp   = ClimateStateHolder.insideTemp
    val outsideTemp  = ClimateStateHolder.outsideTemp
    val setpointTemp = ClimateStateHolder.driverTemp
    val setF    = try { setpointTemp.toFloat() } catch (_: Exception) { null }
    val insideF = try { insideTemp.toFloat() }   catch (_: Exception) { null }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TempReadCard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label    = "TEMP. INTERNA",
            value    = insideTemp,
            note     = "cabine",
            delta    = if (setF != null && insideF != null) {
                val d = insideF - setF
                (if (d >= 0) "+%.1f" else "%.1f").format(d) + "°"
            } else "--"
        )
        TempReadCard(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label    = "TEMP. EXTERNA",
            value    = outsideTemp,
            note     = "ambiente",
            delta    = try { "%.1f°".format(outsideTemp.toFloat()) } catch (_: Exception) { "--" }
        )
    }
}

@Composable
private fun TempReadCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    note: String,
    delta: String
) {
    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Label row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.SemiBold,
                    color         = HmiFgDim,
                    letterSpacing = 2.sp
                )
                Icon(
                    Icons.Default.Thermostat,
                    contentDescription = null,
                    tint     = HmiFgFaint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Big value
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    try { "%.1f".format(value.toFloat()) } catch (_: Exception) { "--" },
                    fontSize      = 64.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = HmiFg,
                    letterSpacing = (-2).sp,
                    fontFamily    = FontFamily.Monospace
                )
                Text(
                    "°C",
                    fontSize  = 24.sp,
                    color     = HmiFgMuted,
                    modifier  = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }

            // Footer
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(note, fontSize = 17.sp, color = HmiFgDim, fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(delta, fontSize = 17.sp, color = HmiFgMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun HmiInfoStripRow(
    state: ClimateStateHolder,
    comfortMode: String,
    onCycleComfort: () -> Unit,
    seatVentAutoEnabled: Boolean,
    onToggleSeatVent: () -> Unit
) {
    val isAcOn = state.acEnable == "1"

    Row(
        modifier              = Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Estado do AC
        HmiInfoCardBox(modifier = Modifier.weight(1f)) {
            Row(
                modifier              = Modifier.fillMaxSize(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isAcOn) HmiAccentSoft else HmiSurface2,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (isAcOn) HmiAccentEdge else HmiBorder,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AcUnit,
                        contentDescription = null,
                        tint     = if (isAcOn) HmiAccent else HmiFgMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ESTADO DO AC", fontSize = 16.sp, color = HmiFgDim, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            when (state.acEnable) { "1" -> "Ligado"; "0" -> "Desligado"; else -> "--" },
                            fontSize      = 22.sp,
                            fontWeight    = FontWeight.Medium,
                            color         = HmiFg,
                            letterSpacing = (-0.3).sp
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (isAcOn) HmiAccent else HmiFgFaint, CircleShape)
                        )
                    }
                }
            }
        }

        // 2. Modo Conforto
        val resolvedCurve = if (comfortMode == "AUTO") {
            val oTemp = state.outsideTemp.toFloatOrNull() ?: 0f
            when { oTemp >= 24f -> "2"; oTemp >= 19f -> "1"; else -> "0" }
        } else null
        val curveLabel = mapOf("0" to "SUAVE", "1" to "NORMAL", "2" to "FORTE")
        HmiInfoCardBox(modifier = Modifier.weight(1f).clickable(onClick = onCycleComfort)) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("MODO CONFORTO", fontSize = 16.sp, color = HmiFgDim, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    comfortModeList.forEach { mode ->
                        val isActive = mode == comfortMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isActive) HmiAccentSoft else HmiSurface2,
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isActive) HmiAccentEdge else HmiBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                mode,
                                fontSize      = 13.sp,
                                fontWeight    = FontWeight.SemiBold,
                                fontFamily    = FontFamily.Monospace,
                                color         = if (isActive) HmiAccent else HmiFgFaint,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
                if (resolvedCurve != null) {
                    Text(
                        "→ ${curveLabel[resolvedCurve]} · ${state.outsideTemp}°C ext.",
                        fontSize      = 12.sp,
                        color         = HmiFgDim,
                        fontFamily    = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. Ventilação Motorista
        VentInfoCard(
            modifier    = Modifier.weight(1f),
            label       = "VENTILAÇÃO MOTORISTA",
            level       = state.driverSeatVentLevel,
            autoEnabled = seatVentAutoEnabled,
            onToggle    = onToggleSeatVent
        )

        // 4. Ventilação Passageiro
        VentInfoCard(
            modifier    = Modifier.weight(1f),
            label       = "VENTILAÇÃO PASSAGEIRO",
            level       = state.passengerSeatVentLevel,
            autoEnabled = seatVentAutoEnabled,
            onToggle    = onToggleSeatVent
        )
    }
}

@Composable
private fun HmiInfoCardBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(HmiSurface, RoundedCornerShape(16.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
private fun VentInfoCard(
    modifier: Modifier = Modifier,
    label: String,
    level: String,
    autoEnabled: Boolean,
    onToggle: () -> Unit
) {
    val levelInt = if (autoEnabled) (level.toIntOrNull() ?: 0) else 0

    HmiInfoCardBox(
        modifier = modifier.clickable(onClick = onToggle)
    ) {
        Row(
            modifier              = Modifier.fillMaxSize(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(HmiSurface2, RoundedCornerShape(12.dp))
                    .border(1.dp, HmiBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Air,
                    contentDescription = null,
                    tint     = HmiFgMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Label + badge de modo
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        fontSize      = 10.sp,
                        color         = HmiFgDim,
                        letterSpacing = 2.sp,
                        fontWeight    = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (autoEnabled) HmiAccent else HmiFgFaint,
                                    CircleShape
                                )
                        )
                        Text(
                            if (autoEnabled) "AUTO" else "OFF",
                            fontSize      = 12.sp,
                            fontWeight    = FontWeight.SemiBold,
                            fontFamily    = FontFamily.Monospace,
                            color         = if (autoEnabled) HmiAccent else HmiFgDim,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Text(
                    if (autoEnabled) when (level) {
                        "0" -> "Off"; "1" -> "Nível 1"; "2" -> "Nível 2"; "3" -> "Nível 3"; else -> "--"
                    } else "--",
                    fontSize      = 20.sp,
                    fontWeight    = FontWeight.Medium,
                    color         = if (autoEnabled) HmiFg else HmiFgFaint,
                    letterSpacing = (-0.3).sp
                )
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (i in 1..3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .background(
                                    if (autoEnabled && i <= levelInt) HmiFg else HmiSurface2,
                                    RoundedCornerShape(1.dp)
                                )
                                .border(
                                    0.5.dp,
                                    if (autoEnabled && i <= levelInt) HmiFgMuted else HmiBorder,
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Debug Screen
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

    Column(modifier = Modifier.fillMaxSize().background(HmiBg).padding(16.dp)) {

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
                        tint               = HmiFg,
                        modifier           = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text("Debug", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HmiFg)
                    Text("v$currentVersion", fontSize = 17.sp, color = Color(0xFF666666))
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
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = HmiFg, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Verificando...", fontSize = 15.sp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Atualizar", fontSize = 15.sp)
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

        Text("Controles HVAC", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
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

        Text("Controles EV", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HvacToggle("wade_mode", state.wadeModeEnable, "car.ev.setting.wade_mode_enable")
        }

        Spacer(Modifier.height(10.dp))

        Text("Sensores HVAC", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HvacReadOnly(Modifier.weight(1f), "front_temp_range",  state.frontTempRange)
            HvacReadOnly(Modifier.weight(1f), "intelligent_range", state.intelligentTempRange)
            HvacReadOnly(Modifier.weight(1f), "comfort_curve",     state.comfortCurve)
        }

        Spacer(Modifier.height(10.dp))

        // A tela é 1792×660 — larga e baixa. Em vez de empilhar mais seções (que
        // forçariam scroll vertical), o rodapé vira três colunas lado a lado.
        Row(
            modifier              = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                Text("Histórico de Ações", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
                Spacer(Modifier.height(6.dp))
                if (state.actionLog.isEmpty()) {
                    Box(
                        modifier         = Modifier.fillMaxWidth().weight(1f)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text      = if (state.vehicleConnected)
                                "Nenhuma ação registrada ainda.\nAC será controlado quando o modo Automático estiver ativo."
                            else "Aguardando conexão com o veículo...",
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
                        items(state.actionLog) { entry ->
                            Text(entry, fontSize = 15.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace)
                            HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                        }
                    }
                }
            }

            Pm25Panel(modifier = Modifier.weight(1.15f).fillMaxHeight())
            VendorPropsPanel(modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }

    if (showMsgDialog) {
        AlertDialog(
            onDismissRequest  = { showMsgDialog = false },
            title             = { Text("Atualização") },
            text              = { Text(updateMessage) },
            confirmButton     = { TextButton(onClick = { showMsgDialog = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
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
                        Text("Baixando… ${(downloadProgress * 100).toInt()}%", fontSize = 16.sp, color = Color(0xFF4FC3F7))
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF4FC3F7))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!isDownloading) startDownload() }, enabled = !isDownloading) {
                    Text(if (isDownloading) "Baixando..." else "Baixar e Instalar")
                }
            },
            dismissButton = { TextButton(onClick = { updateAvailable = false; downloadJob?.cancel() }) { Text("Cancelar") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
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
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Screen Info Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun ScreenInfoScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current

    val dm        = context.resources.displayMetrics
    val widthPx   = dm.widthPixels
    val heightPx  = dm.heightPixels
    val densityDpi = dm.densityDpi
    val density   = dm.density
    val xdpi      = dm.xdpi
    val ydpi      = dm.ydpi
    val widthDp   = (widthPx / density).toInt()
    val heightDp  = (heightPx / density).toInt()

    val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    val realMetrics = android.util.DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(realMetrics)
    val realWidthPx  = realMetrics.widthPixels
    val realHeightPx = realMetrics.heightPixels

    val config         = context.resources.configuration
    val smallestDp     = config.smallestScreenWidthDp
    val screenWidthDp  = config.screenWidthDp
    val screenHeightDp = config.screenHeightDp

    data class InfoRow(val label: String, val value: String)

    val rows = listOf(
        InfoRow("Resolução (px)",        "$widthPx × $heightPx"),
        InfoRow("Resolução real (px)",   "$realWidthPx × $realHeightPx"),
        InfoRow("Tamanho (dp)",          "$widthDp × $heightDp dp"),
        InfoRow("Config screenWidthDp",  "$screenWidthDp dp"),
        InfoRow("Config screenHeightDp", "$screenHeightDp dp"),
        InfoRow("smallestScreenWidthDp", "$smallestDp dp"),
        InfoRow("Densidade (dpi)",       "$densityDpi dpi"),
        InfoRow("Fator de escala",       String.format("%.2f", density)),
        InfoRow("DPI físico X",          String.format("%.1f", xdpi)),
        InfoRow("DPI físico Y",          String.format("%.1f", ydpi)),
        InfoRow("Proporção (W/H)",       String.format("%.3f", widthPx.toFloat() / heightPx))
    )

    Column(modifier = Modifier.fillMaxSize().background(HmiBg).padding(20.dp)) {

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = HmiFg, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Info da Tela", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HmiFg)
                    Text("Métricas do display", fontSize = 17.sp, color = Color(0xFF666666))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(row.label, fontSize = 16.sp, color = Color(0xFF888888))
                        Text(row.value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64B5F6), fontFamily = FontFamily.Monospace)
                    }
                    if (row != rows.last()) {
                        HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Settings Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val state   = ClimateStateHolder
    val prefs   = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

    var realOutsideTempEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_REAL_OUTSIDE_TEMP, false))
    }
    var homeCardEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_HOME_CARD, false))
    }
    var showHelp     by remember { mutableStateOf(false) }
    var showCardHelp by remember { mutableStateOf(false) }
    val fridaAvailable = remember { FridaUtils.fridaToolsEmbedded() }

    Column(modifier = Modifier.fillMaxSize().background(HmiBg).padding(20.dp)) {

        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = HmiFg, modifier = Modifier.size(20.dp))
            }
            Column {
                Text("Configurações", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HmiFg)
                Text("Preferências do app", fontSize = 17.sp, color = Color(0xFF666666))
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        modifier              = Modifier.weight(1f),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Ativar Temperatura Externa Real (UI)",
                            fontSize   = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color      = HmiFg
                        )
                        IconButton(onClick = { showHelp = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Ajuda",
                                tint     = HmiFgMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Switch(
                        checked         = realOutsideTempEnabled,
                        onCheckedChange = { enabled ->
                            realOutsideTempEnabled       = enabled
                            state.realOutsideTempEnabled = enabled
                            prefs.edit().putBoolean(KEY_REAL_OUTSIDE_TEMP, enabled).apply()
                            state.onRealOutsideTempToggle?.onToggle(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = Color.White,
                            checkedTrackColor   = HmiAccent,
                            uncheckedThumbColor = HmiFgMuted,
                            uncheckedTrackColor = HmiSurface
                        )
                    )
                }
                if (!fridaAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ Recursos do Frida ausentes neste APK (build debug). Esta opção só funciona no APK do Release/CI.",
                        fontSize = 13.sp,
                        color    = Color(0xFFFFB74D)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        modifier              = Modifier.weight(1f),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Mostrar Card na Home",
                            fontSize   = 17.sp,
                            fontWeight = FontWeight.Medium,
                            color      = HmiFg
                        )
                        IconButton(onClick = { showCardHelp = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Ajuda",
                                tint     = HmiFgMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Switch(
                        checked         = homeCardEnabled,
                        onCheckedChange = { enabled ->
                            homeCardEnabled       = enabled
                            state.homeCardEnabled = enabled
                            prefs.edit().putBoolean(KEY_HOME_CARD, enabled).apply()
                            state.onHomeCardToggle?.onToggle(enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = Color.White,
                            checkedTrackColor   = HmiAccent,
                            uncheckedThumbColor = HmiFgMuted,
                            uncheckedTrackColor = HmiSurface
                        )
                    )
                }
                if (!fridaAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ Recursos do Frida ausentes neste APK (build debug). Esta opção só funciona no APK do Release/CI.",
                        fontSize = 13.sp,
                        color    = Color(0xFFFFB74D)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        UpdateCard()
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest  = { showHelp = false },
            title             = { Text("Temperatura Externa Real (UI)") },
            text              = { Text("Ativando essa opção irá desabilitar o serviço nativo da central de previsão do tempo e será mostrado a temperatura externa real.") },
            confirmButton     = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }

    if (showCardHelp) {
        AlertDialog(
            onDismissRequest  = { showCardHelp = false },
            title             = { Text("Mostrar Card na Home") },
            text              = { Text("Substitui a fileira de mídia online da tela principal da central por um card com o estado do ar-condicionado: A/C ligado, temperatura interna e velocidade do vento. Tocar no card abre este app. Ao desativar, a fileira original volta.") },
            confirmButton     = { TextButton(onClick = { showCardHelp = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Atualização do app (Configurações)
// ─────────────────────────────────────────────────────────────

/**
 * Verifica o último release no GitHub e, se houver versão nova, baixa e dispara a
 * instalação. A tag do release precisa bater com o versionName (ex. v1.13.0).
 */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var currentVersion   by remember { mutableStateOf("--") }
    var isChecking       by remember { mutableStateOf(false) }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var statusText       by remember { mutableStateOf("") }
    var showPermDialog   by remember { mutableStateOf(false) }

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
        isDownloading = true; downloadProgress = 0f; statusText = ""
        scope.launch(Dispatchers.IO) {
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
                    statusText    = "Erro no download: ${e.message}"
                }
            }
        }
    }

    fun checkForUpdates() {
        isChecking = true; statusText = ""
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
                        statusText    = ""
                    } else {
                        updateAvailable = false
                        statusText      = "Você já está na versão mais recente."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                withContext(Dispatchers.Main) {
                    isChecking = false
                    statusText = "Erro ao verificar: ${e.message}"
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Atualizar versão",
                        fontSize   = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color      = HmiFg
                    )
                    Text(
                        if (updateAvailable) "Versão $latestVersion disponível — atual $currentVersion"
                        else "Versão instalada: $currentVersion",
                        fontSize = 15.sp,
                        color    = if (updateAvailable) HmiAccent else Color(0xFF888888)
                    )
                }
                Button(
                    onClick = { if (updateAvailable) startDownload() else checkForUpdates() },
                    enabled = !isChecking && !isDownloading,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (updateAvailable) HmiAccent else Color(0xFF1565C0)
                    ),
                    shape   = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        when {
                            isChecking      -> "Verificando…"
                            isDownloading   -> "Baixando ${(downloadProgress * 100).toInt()}%"
                            updateAvailable -> "Baixar e instalar"
                            else            -> "Verificar agora"
                        },
                        fontSize = 16.sp
                    )
                }
            }
            if (isDownloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color    = HmiAccent
                )
            }
            if (statusText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(statusText, fontSize = 14.sp, color = HmiFgMuted)
            }
        }
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title            = { Text("Permissão necessária") },
            text             = { Text("Autorize a instalação de apps de fontes desconhecidas para concluir a atualização.") },
            confirmButton    = {
                TextButton(onClick = {
                    showPermDialog = false
                    permLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Abrir ajustes") }
            },
            dismissButton     = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Shared composables (used by secondary screens)
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
            modifier            = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 17.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = valueFontSize.sp, fontWeight = FontWeight.Bold, color = valueColor, textAlign = TextAlign.Center)
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
            Text(label, fontSize = 16.sp, color = Color(0xFFAAAAAA), textAlign = TextAlign.Center)
            Text(
                text       = when (value) { "1" -> "ON"; "0" -> "OFF"; else -> "--" },
                fontSize   = 14.sp,
                fontWeight = FontWeight.Bold,
                color      = textColor
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// PM2.5 — leitura crua + faixa do enumerador OEM + histórico
// ─────────────────────────────────────────────────────────────

@Composable
fun Pm25Panel(modifier: Modifier = Modifier) {
    val state = ClimateStateHolder
    val raw   = state.pm25Value
    val band  = pm25BandIndex(raw)
    val hasData = band >= 0

    val valueColor = if (hasData) PM25_COLORS[band] else Color(0xFF666666)
    val bandLabel  = if (hasData) PM25_LABELS[band] else "sem dado"

    Column(modifier = modifier) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text("PM2.5 / Qualidade do ar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
            Text(
                text     = "limpar",
                fontSize = 14.sp,
                color    = Color(0xFF4FC3F7),
                modifier = Modifier.clickable { state.clearPm25History() }
            )
        }
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            // Valor cru + faixa
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text       = if (raw == "--") "--" else raw,
                    fontSize   = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color      = valueColor,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(4.dp))
                Text("µg/m³", fontSize = 14.sp, color = Color(0xFF777777), modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(bandLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = valueColor)
                    Text(
                        text     = if (hasData) "idx $band · ${PM25_RANGES[band]}" else "fora de todas as faixas",
                        fontSize = 13.sp,
                        color    = Color(0xFF777777)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text       = "min ${fmtPm(state.pm25Min)}   max ${fmtPm(state.pm25Max)}   amostras ${state.pm25History.size}",
                fontSize   = 13.sp,
                color      = Color(0xFF777777),
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))

            // Tabela das 6 faixas, com a ativa destacada
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in PM25_LABELS.indices) {
                    val active = i == band
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(
                                if (active) PM25_COLORS[i].copy(alpha = 0.18f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).background(PM25_COLORS[i], CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text       = PM25_RANGES[i],
                            fontSize   = 13.sp,
                            color      = if (active) PM25_COLORS[i] else Color(0xFF888888),
                            fontFamily = FontFamily.Monospace,
                            modifier   = Modifier.width(70.dp)
                        )
                        Text(
                            text       = PM25_LABELS[i],
                            fontSize   = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color      = if (active) PM25_COLORS[i] else Color(0xFF888888)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))

            if (state.pm25History.isEmpty()) {
                Text("Aguardando amostras…", fontSize = 13.sp, color = Color(0xFF666666))
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(state.pm25History) { s ->
                        val b = pm25BandIndex(s.value.toString())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = pm25TimeFmt.format(java.util.Date(s.atMs)),
                                fontSize   = 13.sp,
                                color      = Color(0xFF666666),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text       = s.value.toString().padStart(4),
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color      = if (b >= 0) PM25_COLORS[b] else Color(0xFF666666),
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text     = if (b >= 0) PM25_LABELS[b] else "sem dado",
                                fontSize = 13.sp,
                                color    = Color(0xFF999999)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmtPm(v: Int) = if (v < 0) "--" else v.toString()

// ─────────────────────────────────────────────────────────────
// System properties de variante — leitura e escrita via Shizuku
// ─────────────────────────────────────────────────────────────

@Composable
fun VendorPropsPanel(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var demist    by remember { mutableStateOf("…") }
    var purifier  by remember { mutableStateOf("…") }
    var busy      by remember { mutableStateOf(false) }
    var feedback  by remember { mutableStateOf("") }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val d = SystemPropsUtils.get(PROP_AUTO_DEMIST)
            val p = SystemPropsUtils.get(PROP_AIR_PURIFIER)
            withContext(Dispatchers.Main) {
                demist   = d.ifEmpty { "<vazio>" }
                purifier = p.ifEmpty { "<vazio>" }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun writeDemist(value: String) {
        busy = true; feedback = ""
        scope.launch(Dispatchers.IO) {
            val err = SystemPropsUtils.set(PROP_AUTO_DEMIST, value)
            withContext(Dispatchers.Main) {
                busy = false
                feedback = err ?: "Gravado = $value. Reinicie o app HVAC para ele reler."
                ClimateStateHolder.addLog(
                    "[prop] indoor.automatic.demisting → $value ${if (err == null) "OK" else "FALHOU: $err"}"
                )
                reload()
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text("Config de variante", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
            Text(
                text     = "reler",
                fontSize = 14.sp,
                color    = Color(0xFF4FC3F7),
                modifier = Modifier.clickable(enabled = !busy) { reload() }
            )
        }
        Spacer(Modifier.height(6.dp))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text("indoor.automatic.demisting", fontSize = 14.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace)
            Text("Desembaçador dianteiro automático", fontSize = 13.sp, color = Color(0xFF777777))
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = demist,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color      = when (demist) {
                        "1"  -> Color(0xFF69F0AE)
                        "0"  -> Color(0xFFB39DDB)
                        else -> Color(0xFF666666)
                    },
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick        = { writeDemist("0") },
                    enabled        = !busy && demist != "0",
                    colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF311B92)),
                    shape          = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("0 · off", fontSize = 15.sp) }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick        = { writeDemist("1") },
                    enabled        = !busy && demist != "1",
                    colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                    shape          = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("1 · on", fontSize = 15.sp) }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "hasAutoDemist() == 1 revela a linha nas Configurações do app HVAC. " +
                "A property é lida só no init do ViewModel — sem reiniciar o HVAC nada muda na tela dele.",
                fontSize = 12.sp,
                color    = Color(0xFF666666)
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    busy = true; feedback = ""
                    scope.launch(Dispatchers.IO) {
                        val err = SystemPropsUtils.restartPackage(HVAC_PKG)
                        withContext(Dispatchers.Main) {
                            busy = false
                            feedback = err ?: "HVAC encerrado — abra o app de clima do carro para ver o efeito."
                        }
                    }
                },
                enabled        = !busy,
                colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                shape          = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier       = Modifier.fillMaxWidth()
            ) { Text("Reiniciar app HVAC (force-stop)", fontSize = 15.sp) }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))

            Text("air.purifier", fontSize = 14.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = purifier,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFFFFB74D),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text     = if (purifier in listOf("2", "3", "5"))
                        "tem sensor de PM2.5" else "sem sensor de PM2.5",
                    fontSize = 13.sp,
                    color    = if (purifier in listOf("2", "3", "5")) Color(0xFF9CCC65) else Color(0xFF888888)
                )
            }
            Text(
                "Somente leitura. hasAirPMSystem() exige ∈ {2,3,5} — é o que decide se o HVAC mostra a qualidade do ar.",
                fontSize = 12.sp,
                color    = Color(0xFF666666)
            )

            if (feedback.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(feedback, fontSize = 13.sp, color = Color(0xFF4FC3F7))
            }
            if (!SystemPropsUtils.isShizukuReady()) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ Shizuku indisponível — escrita desabilitada.", fontSize = 13.sp, color = Color(0xFFFF7043))
            }
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
            Text(label, fontSize = 16.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = HmiFg, textAlign = TextAlign.Center)
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
            Text(label, fontSize = 17.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
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
            Text(label, fontSize = 17.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
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
                shape = CircleShape
            )
        )
        Text(
            text     = if (connected) "Conectado" else "Aguardando",
            fontSize = 15.sp,
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
