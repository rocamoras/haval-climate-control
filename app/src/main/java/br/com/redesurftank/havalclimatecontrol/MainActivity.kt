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
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import br.com.redesurftank.havalclimatecontrol.ui.OemAccent
import br.com.redesurftank.havalclimatecontrol.ui.OemBackHeader
import br.com.redesurftank.havalclimatecontrol.ui.OemButton
import br.com.redesurftank.havalclimatecontrol.ui.OemClimateScreen
import br.com.redesurftank.havalclimatecontrol.ui.OemInk
import br.com.redesurftank.havalclimatecontrol.ui.OemInk2
import br.com.redesurftank.havalclimatecontrol.ui.OemInk3
import br.com.redesurftank.havalclimatecontrol.ui.OemNote
import br.com.redesurftank.havalclimatecontrol.ui.OemRow
import br.com.redesurftank.havalclimatecontrol.ui.OemSectionTitle
import br.com.redesurftank.havalclimatecontrol.ui.OemSeg
import br.com.redesurftank.havalclimatecontrol.ui.theme.HavalClimateControlTheme
import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService
import br.com.redesurftank.havalclimatecontrol.utils.FridaUtils
import br.com.redesurftank.havalclimatecontrol.utils.LogUploader
import br.com.redesurftank.havalclimatecontrol.utils.SystemPropsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
// Canal estavel: /releases/latest, que por definicao da API do GitHub NUNCA devolve
// um prerelease. E essa a barreira que impede o app estavel de enxergar um build de
// preview -- nao o sufixo do versionName, que a comparacao numerica ignoraria.
private const val GITHUB_RELEASES_LATEST =
    "https://api.github.com/repos/rocamoras/haval-climate-control/releases/latest"
// Canal preview: lista todos os releases (prereleases inclusos) e escolhe o maior.
// So o build de preview enxerga os dois canais, e por isso ele sai do preview sozinho
// quando o estavel passa o numero dele.
private const val GITHUB_RELEASES_ALL =
    "https://api.github.com/repos/rocamoras/haval-climate-control/releases?per_page=30"
private const val PREVIEW_SUFFIX = "-preview"
private const val UI_PREFS                 = "climate_ui_prefs"
private const val KEY_AUTO_CONTROL         = "auto_control_enabled"
private const val KEY_LAST_UPDATE_CHECK    = "last_update_check_ms"
private const val KEY_SEAT_VENT_AUTO       = "seat_vent_auto_enabled"
private const val KEY_COMFORT_MODE         = "comfort_mode"
private const val KEY_REAL_OUTSIDE_TEMP    = "real_outside_temp_enabled"
private const val KEY_HOME_CARD            = "home_card_enabled"
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

// Prefs do serviço (device-protected). A flag do Shizuku mora aqui e não em UI_PREFS
// porque o serviço a lê no LOCKED_BOOT_COMPLETED, antes do unlock — em credential
// storage ela leria false em todo boot frio.
private const val SERVICE_PREFS            = "climate_control_prefs"
private const val KEY_START_SHIZUKU        = "start_shizuku_server"

// ─────────────────────────────────────────────────────────────
// HMI color tokens — monochromatic dark, accent only for active
// ─────────────────────────────────────────────────────────────
private val HmiBg         = Color(0xFF000000)
private val HmiFg         = Color(0xFFFAFAFA)
private val HmiFgMuted    = Color(0xFFA3A3A3)

// ─────────────────────────────────────────────────────────────
// Constantes reutilizáveis — evitam realocação por recomposição/frame
// ─────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────
// System properties de configuração de variante (persist.vendor.gwm.cfg.*)
// ─────────────────────────────────────────────────────────────


private val pm25TimeFmt = SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

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
                            onNavigateToScreenInfo = { currentScreen = "screeninfo" },
                            onNavigateToSettings   = { currentScreen = "settings" }
                        )
        "screeninfo" -> ScreenInfoScreen(onNavigateBack = { currentScreen = "main" })
        "settings"   -> SettingsScreen(onNavigateBack = { currentScreen = "main" })
    }
}

// ─────────────────────────────────────────────────────────────
// Main Control Screen — HMI wide layout (1792×660dp)
// ─────────────────────────────────────────────────────────────

@Composable
fun MainControlScreen(
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
                try {
                    val rel = fetchNewerRelease(currentVersion)
                    withContext(Dispatchers.Main) {
                        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                        if (rel != null) {
                            latestVersion   = rel.tag
                            downloadUrl     = rel.apkUrl
                            updateAvailable = true
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

    OemClimateScreen(
        onNavigateToSettings   = onNavigateToSettings,
        onNavigateToScreenInfo = onNavigateToScreenInfo,
        onToggleAutoControl    = { enabled ->
            autoControlEnabled       = enabled
            state.autoControlEnabled = enabled
            prefs.edit().putBoolean(KEY_AUTO_CONTROL, enabled).apply()
        },
        onSeatVentAuto         = { enabled ->
            seatVentAutoEnabled       = enabled
            state.seatVentAutoEnabled = enabled
            prefs.edit().putBoolean(KEY_SEAT_VENT_AUTO, enabled).apply()
            if (!enabled) {
                state.sendCommand(CarProps.DRIVER_SEAT_VENT,    "0")
                state.sendCommand(CarProps.PASSENGER_SEAT_VENT, "0")
            }
        },
    )

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
// Debug Screen
// ─────────────────────────────────────────────────────────────

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
    val scope   = rememberCoroutineScope()
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

    // Device-protected: é o mesmo store que o serviço lê no boot travado.
    val servicePrefs = remember {
        App.getDeviceProtectedContext().getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
    }
    var startShizukuEnabled by remember {
        mutableStateOf(servicePrefs.getBoolean(KEY_START_SHIZUKU, false))
    }
    var shizukuUp       by remember { mutableStateOf(SystemPropsUtils.isShizukuReady()) }
    var showShizukuHelp by remember { mutableStateOf(false) }
    // Acima de 10999 o firewall por uid do Android barra a conexao no telnet:23, que e
    // o unico caminho para subir o servidor. Sem uid baixo a flag nao tem como funcionar.
    val selfUid = remember { runCatching { context.applicationInfo.uid }.getOrDefault(-1) }
    val canReachTelnet = selfUid in 0..10999

    // Envio de log — veio da tela de debug, que deixou de existir. O log em si ficou
    // invisível: o serviço continua escrevendo no PersistentLog, e é esse arquivo que
    // este botão empacota.
    var isUploading   by remember { mutableStateOf(false) }
    var uploadStatus  by remember { mutableStateOf("") }
    var uploadUrl     by remember { mutableStateOf("") }
    var showUploadDlg by remember { mutableStateOf(false) }

    fun uploadLog() {
        isUploading   = true
        uploadUrl     = ""
        uploadStatus  = "Iniciando…"
        showUploadDlg = true
        scope.launch(Dispatchers.IO) {
            val result = LogUploader.collectAndUpload(context) { msg ->
                scope.launch(Dispatchers.Main) { uploadStatus = msg }
            }
            withContext(Dispatchers.Main) {
                isUploading = false
                when (result) {
                    is LogUploader.Result.Ok -> {
                        uploadUrl    = result.url
                        uploadStatus = "Enviado (${result.sizeBytes / 1024} KB)"
                        state.addLog("[${pm25TimeFmt.format(java.util.Date())}] log enviado ao Firebase")
                    }
                    is LogUploader.Result.Err -> uploadStatus = "Erro: ${result.message}"
                }
            }
        }
    }

    /** Linha Desl./Lig. ligada direto a uma propriedade do carro. */
    @Composable
    fun PropRow(label: String, value: String, propKey: String) {
        OemRow(label) {
            OemSeg(listOf("Desl.", "Lig."), if (value == "1") 1 else 0, 400.dp) { i ->
                state.sendCommand(propKey, i.toString())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        OemBackHeader("Configurações", onNavigateBack)

        Column(
            modifier = Modifier
                .absoluteOffset(175.dp, 120.dp)
                .size(1248.dp, 508.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── ar-condicionado (propriedades do carro) ───────────────────────
            OemSectionTitle("Ar-condicionado")

            PropRow("AQS", state.aqsEnable, CarProps.AQS_ENABLE)
            PropRow("Íons negativos", state.anionEnable, CarProps.ANION_ENABLE)

            OemRow("Fragrância") {
                OemSeg(
                    listOf("Desl.", "Suave", "Normal", "Intenso"),
                    state.fragranceConcentration.toIntOrNull()?.coerceIn(0, 3) ?: 0,
                    800.dp,
                ) { i -> state.sendCommand(CarProps.FRAGRANCE_CONCENTRATION, i.toString()) }
            }
            FragranceCards(
                selected = state.fragranceType.toIntOrNull() ?: -1,
                onSelect = { i -> state.sendCommand(CarProps.FRAGRANCE_TYPE, i.toString()) },
            )

            PropRow(
                "Desembaçador dianteiro\nautomático",
                state.autoDefrostEnable, CarProps.AUTO_DEFROST,
            )

            OemRow("Conforto do\nar-condicionado") {
                // 0/1/2 confirmados: é a mesma codificação que notifyExternalComfortChange
                // usa para converter a curva vinda do carro em SUAVE/NORMAL/FORTE.
                OemSeg(
                    listOf("Econômico", "Padrão", "Potência"),
                    state.comfortCurve.toIntOrNull()?.coerceIn(0, 2) ?: 1,
                    600.dp,
                ) { i -> state.sendCommand(CarProps.COMFORT_CURVE, i.toString()) }
            }

            PropRow("Limite de\nbateria baixa", state.settingLimitEnable, CarProps.LIMIT_ENABLE)

            // O botão "AC Inteligente" da tela principal hospeda o controle automático do
            // APP. A propriedade homônima do OEM ganhou casa aqui — é o único lugar onde
            // ela é escrita.
            PropRow("A/C inteligente (OEM)", state.intelligentSwitch, CarProps.INTELLIGENT_SWITCH)

            // ── app ───────────────────────────────────────────────────────────
            OemSectionTitle("Aplicativo")

            OemRow("Temperatura externa real") {
                OemSeg(listOf("Desl.", "Lig."), if (realOutsideTempEnabled) 1 else 0, 400.dp) { i ->
                    val enabled = i == 1
                    realOutsideTempEnabled       = enabled
                    state.realOutsideTempEnabled = enabled
                    prefs.edit().putBoolean(KEY_REAL_OUTSIDE_TEMP, enabled).apply()
                    state.onRealOutsideTempToggle?.onToggle(enabled)
                }
                HelpDot { showHelp = true }
            }
            if (!fridaAvailable) {
                OemNote("Recursos do Frida ausentes neste APK (build debug). Esta opção só funciona no APK do Release/CI.")
            }

            OemRow("Mostrar card na Home") {
                OemSeg(listOf("Desl.", "Lig."), if (homeCardEnabled) 1 else 0, 400.dp) { i ->
                    val enabled = i == 1
                    homeCardEnabled       = enabled
                    state.homeCardEnabled = enabled
                    prefs.edit().putBoolean(KEY_HOME_CARD, enabled).apply()
                    state.onHomeCardToggle?.onToggle(enabled)
                }
                HelpDot { showCardHelp = true }
            }

            OemRow("Subir servidor do Shizuku") {
                OemSeg(listOf("Desl.", "Lig."), if (startShizukuEnabled) 1 else 0, 400.dp) { i ->
                    val enabled = i == 1
                    startShizukuEnabled = enabled
                    servicePrefs.edit().putBoolean(KEY_START_SHIZUKU, enabled).apply()
                    // A decisão é tomada no onStartCommand, então só vale no próximo
                    // ciclo: para e sobe de novo para a pref valer agora.
                    val intent = Intent(context, ClimateControlService::class.java)
                    runCatching {
                        context.stopService(intent)
                        context.startForegroundService(intent)
                    }
                    shizukuUp = SystemPropsUtils.isShizukuReady()
                }
                HelpDot { showShizukuHelp = true }
            }
            Text(
                (if (shizukuUp) "Shizuku agora: ativo" else "Shizuku agora: indisponível") +
                    "   ·   uid do app: " + (if (selfUid >= 0) selfUid.toString() else "?"),
                fontSize = 18.sp,
                color = if (shizukuUp) OemInk3 else Color(0xFFFF7043),
                modifier = Modifier.padding(start = 440.dp, bottom = 12.dp),
            )
            if (startShizukuEnabled) {
                OemNote(
                    "Só mantenha ligado em centrais sem Impulse. Se já houver um servidor do " +
                        "Shizuku no ar, o app anexa nele e não sobe o seu."
                )
                if (!canReachTelnet) {
                    OemNote(
                        "uid acima de 10999: o firewall da central barra o telnet na porta 23, " +
                            "então subir o servidor vai falhar. Precisa reinstalar o app pelo " +
                            "método que dá uid baixo (script de instalação com injeção no " +
                            "system_server).",
                        severe = true,
                    )
                }
            }

            OemRow("Diagnóstico") {
                OemButton(if (isUploading) "Enviando…" else "Enviar log", enabled = !isUploading) {
                    uploadLog()
                }
            }

            UpdateRow()

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showUploadDlg) {
        AlertDialog(
            onDismissRequest = { if (!isUploading) showUploadDlg = false },
            title            = { Text("Enviar log ao Firebase") },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(uploadStatus, fontSize = 16.sp, color = HmiFgMuted)
                    if (isUploading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OemAccent)
                    }
                    if (uploadUrl.isNotEmpty()) {
                        Text(
                            uploadUrl,
                            fontSize   = 13.sp,
                            color      = Color(0xFF4FC3F7),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                if (uploadUrl.isNotEmpty()) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", uploadUrl))
                        showUploadDlg = false
                    }) { Text("Copiar link") }
                } else {
                    TextButton(onClick = { showUploadDlg = false }, enabled = !isUploading) {
                        Text("Fechar")
                    }
                }
            },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
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

    if (showShizukuHelp) {
        AlertDialog(
            onDismissRequest  = { showShizukuHelp = false },
            title             = { Text("Subir servidor do Shizuku") },
            text              = { Text(
                "Todo acesso ao veículo passa pelo Shizuku, e normalmente quem sobe esse " +
                "servidor é outro app da central (o Impulse). Se ele não estiver instalado, " +
                "ninguém sobe o servidor e este app fica sem funcionar." +
                "\n\n" +
                "Ative esta opção para que o próprio app suba o servidor. Deixe desligada se " +
                "a central tiver o Impulse: existe só um servidor por central, e quem sobe " +
                "depois derruba o anterior."
            ) },
            confirmButton     = { TextButton(onClick = { showShizukuHelp = false }) { Text("OK") } },
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

/** "?" clicável ao lado do rótulo — as três opções do app mexem em coisas do sistema e
 *  ninguém acerta o que elas fazem só pelo nome. */
@Composable
private fun HelpDot(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick),
    ) {
        Text("?", fontSize = 22.sp, color = OemInk2)
    }
}

/** Os três frascos do OEM (.fragrow / .fragcard), 254x184 com gap de 19. */
@Composable
private fun FragranceCards(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Wonderland", "Sea breeze", "Flavour mocha")
    Row(
        horizontalArrangement = Arrangement.spacedBy(19.dp),
        modifier = Modifier.padding(start = 440.dp, bottom = 16.dp),
    ) {
        names.forEachIndexed { i, name ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .size(254.dp, 184.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) OemAccent.copy(alpha = 0.18f) else Color(0x14F0F1FF))
                    .clickable { onSelect(i) },
            ) {
                Text(
                    name,
                    fontSize = 28.sp,
                    color = if (on) OemInk else OemInk2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
                Image(
                    painter = painterResource(R.drawable.hvac_frag_bottle),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(if (on) OemAccent else OemInk3),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 24.dp)
                        .size(206.dp, 96.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Atualização do app (linha das Configurações)
// ─────────────────────────────────────────────────────────────

/**
 * Verifica o último release no GitHub e, se houver versão nova, baixa e dispara a
 * instalação. A tag do release precisa bater com o versionName (ex. v1.13.0).
 */
@Composable
private fun UpdateRow() {
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
                val rel = fetchNewerRelease(currentVersion)
                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (rel != null) {
                        latestVersion = rel.tag; downloadUrl = rel.apkUrl; updateAvailable = true
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

    OemRow("Atualização") {
        OemButton(
            when {
                isChecking      -> "Verificando…"
                isDownloading   -> "Baixando ${(downloadProgress * 100).toInt()}%"
                updateAvailable -> "Baixar e instalar"
                else            -> "Verificar agora"
            },
            enabled = !isChecking && !isDownloading,
        ) { if (updateAvailable) startDownload() else checkForUpdates() }
    }
    Text(
        if (updateAvailable) "Versão $latestVersion disponível — atual $currentVersion"
        else "Versão instalada: $currentVersion" +
            (if (isPreviewBuild(currentVersion)) "  ·  canal de preview" else ""),
        fontSize = 18.sp,
        color    = if (updateAvailable) OemAccent else OemInk3,
        modifier = Modifier.padding(start = 440.dp, bottom = 4.dp),
    )
    if (isDownloading) {
        LinearProgressIndicator(
            progress = { downloadProgress },
            modifier = Modifier.width(400.dp).padding(start = 440.dp, bottom = 12.dp),
            color    = OemAccent,
        )
    }
    if (statusText.isNotEmpty()) {
        Text(
            statusText, fontSize = 18.sp, color = OemInk3,
            modifier = Modifier.padding(start = 440.dp, bottom = 12.dp),
        )
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

// ─────────────────────────────────────────────────────────────
// PM2.5 — leitura crua + faixa do enumerador OEM + histórico
// ─────────────────────────────────────────────────────────────


// ─────────────────────────────────────────────────────────────
// System properties de variante — leitura e escrita via Shizuku
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────

/** O canal sai do proprio versionName: um APK cujo nome de versao traz "-preview" e
 *  um build de preview, e so ele consulta a lista completa de releases. */
fun isPreviewBuild(version: String) = version.contains(PREVIEW_SUFFIX)

data class ReleaseInfo(val tag: String, val apkUrl: String)

/** Busca o release mais novo do canal deste build; null quando ja estamos na frente
 *  dele. Bloqueante -- so chame em Dispatchers.IO. */
private fun fetchNewerRelease(currentVersion: String): ReleaseInfo? {
    val preview = isPreviewBuild(currentVersion)
    val url     = if (preview) GITHUB_RELEASES_ALL else GITHUB_RELEASES_LATEST
    val conn    = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().readText()
        val releases = if (preview) {
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } else {
            listOf(JSONObject(body))
        }
        var best: ReleaseInfo? = null
        for (r in releases) {
            if (r.optBoolean("draft", false)) continue
            val tag = r.optString("tag_name")
            val apk = apkAssetUrl(r)
            if (tag.isEmpty() || apk == null) continue
            if (compareVersions(tag, currentVersion) <= 0) continue
            val b = best
            if (b == null || compareVersions(tag, b.tag) > 0) best = ReleaseInfo(tag, apk)
        }
        return best
    } finally {
        conn.disconnect()
    }
}

private fun apkAssetUrl(release: JSONObject): String? {
    val assets = release.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.optString("name").endsWith(".apk")) {
            val u = a.optString("browser_download_url")
            if (u.isNotEmpty()) return u
        }
    }
    return null
}

/** Semver reduzido: compara os numeros e, no empate, trata o pre-lancamento como
 *  ANTERIOR ao release limpo (1.21.0-preview < 1.21.0). Sem essa regra o build de
 *  preview nunca enxergaria o estavel de mesmo numero que o sucede. */
private fun compareVersions(v1: String, v2: String): Int {
    val (n1, pre1) = splitVersion(v1)
    val (n2, pre2) = splitVersion(v2)
    for (i in 0 until maxOf(n1.size, n2.size)) {
        val a = n1.getOrElse(i) { 0 }
        val b = n2.getOrElse(i) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return when {
        pre1.isEmpty() && pre2.isEmpty() -> 0
        pre1.isEmpty()                   -> 1
        pre2.isEmpty()                   -> -1
        else                             -> pre1.compareTo(pre2)
    }
}

/** "v1.21.0-preview.2+build7" -> ([1,21,0], "preview.2") */
private fun splitVersion(v: String): Pair<List<Int>, String> {
    val s    = v.removePrefix("v").substringBefore('+')
    val i    = s.indexOf('-')
    val core = if (i >= 0) s.substring(0, i) else s
    val pre  = if (i >= 0) s.substring(i + 1) else ""
    return core.split(".").map { it.toIntOrNull() ?: 0 } to pre
}

