package br.com.redesurftank.havalclimatecontrol

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-climate-control/releases/latest"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalClimateControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    MonitoringScreen()
                }
            }
        }
    }
}

@Composable
fun MonitoringScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = ClimateStateHolder

    // --- update state ---
    var currentVersion  by remember { mutableStateOf("--") }
    var isChecking      by remember { mutableStateOf(false) }
    var isDownloading   by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable by remember { mutableStateOf(false) }
    var latestVersion   by remember { mutableStateOf("") }
    var downloadUrl     by remember { mutableStateOf("") }
    var updateMessage   by remember { mutableStateOf("") }
    var showMsgDialog   by remember { mutableStateOf(false) }
    var showPermDialog  by remember { mutableStateOf(false) }
    var downloadJob     by remember { mutableStateOf<Job?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* after user grants install permission, re-trigger install */ }

    LaunchedEffect(Unit) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            currentVersion = info.versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    fun compareVersions(v1: String, v2: String): Int {
        val p1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until minOf(p1.size, p2.size)) {
            if (p1[i] > p2[i]) return 1
            if (p1[i] < p2[i]) return -1
        }
        return p1.size.compareTo(p2.size)
    }

    fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermDialog = true
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startDownload() {
        isDownloading = true
        downloadProgress = 0f
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file = File(context.getExternalFilesDir(null), "update.apk")
                val conn = URL(downloadUrl).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val input = BufferedInputStream(conn.inputStream)
                val output = FileOutputStream(file)
                val buffer = ByteArray(4096)
                var bytesRead: Int
                var downloaded = 0
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (total > 0) downloadProgress = downloaded.toFloat() / total
                }
                output.close(); input.close()
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    updateAvailable = false
                    installApk(file)
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
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                var dlUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        dlUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                withContext(Dispatchers.Main) {
                    isChecking = false
                    if (dlUrl != null && compareVersions(tag, currentVersion) > 0) {
                        latestVersion = tag
                        downloadUrl = dlUrl
                        updateAvailable = true
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

    // --- AC state ---
    val isAuto   = state.autoEnable == "1"
    val isAcOn   = state.powerMode  == "1"
    val acColor   = if (isAcOn)  Color(0xFF00BCD4) else Color(0xFF757575)
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
            Column {
                Text("Controle Climático", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("v$currentVersion", fontSize = 11.sp, color = Color(0xFF666666))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { checkForUpdates() },
                    enabled = !isChecking && !isDownloading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
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

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TempCard(modifier = Modifier.weight(1f), label = "Temperatura Interna", value = formatTemp(state.insideTemp), color = Color(0xFFFFB74D))
            TempCard(modifier = Modifier.weight(1f), label = "Temperatura Setada", value = formatTemp(state.driverTemp), color = Color(0xFF64B5F6))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(modifier = Modifier.weight(1f), label = "Modo AC",
                value = if (state.autoEnable == "--") "--" else if (isAuto) "Automático" else "Manual", color = autoColor)
            StatusCard(modifier = Modifier.weight(1f), label = "Estado AC",
                value = if (state.powerMode == "--") "--" else if (isAcOn) "Ligado" else "Desligado", color = acColor)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Histórico de Ações", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFAAAAAA))
        Spacer(modifier = Modifier.height(8.dp))

        if (state.actionLog.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.vehicleConnected)
                        "Nenhuma ação registrada ainda.\nAC será controlado quando o modo Automático estiver ativo."
                    else "Aguardando conexão com o veículo...",
                    color = Color(0xFF666666), fontSize = 13.sp, textAlign = TextAlign.Center,
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
                    Text(text = entry, fontSize = 12.sp, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace)
                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                }
            }
        }
    }

    // --- Dialogs ---

    if (showMsgDialog) {
        AlertDialog(
            onDismissRequest = { showMsgDialog = false },
            title = { Text("Atualização") },
            text = { Text(updateMessage) },
            confirmButton = { TextButton(onClick = { showMsgDialog = false }) { Text("OK") } },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }

    if (updateAvailable) {
        AlertDialog(
            onDismissRequest = { updateAvailable = false },
            title = { Text("Atualização disponível") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nova versão disponível: $latestVersion\nVersão atual: $currentVersion")
                    if (isDownloading) {
                        Spacer(Modifier.height(4.dp))
                        Text("Baixando... ${(downloadProgress * 100).toInt()}%", fontSize = 13.sp, color = Color(0xFF4FC3F7))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4FC3F7)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!isDownloading) startDownload() }, enabled = !isDownloading) {
                    Text(if (isDownloading) "Baixando..." else "Baixar e Instalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { updateAvailable = false; downloadJob?.cancel() }) { Text("Cancelar") }
            },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Permissão necessária") },
            text = { Text("Para instalar o app é necessário habilitar a instalação de fontes desconhecidas nas configurações.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDialog = false
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    permLauncher.launch(intent)
                }) { Text("Abrir Configurações") }
            },
            dismissButton = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }
}

@Composable
fun TempCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun StatusCard(modifier: Modifier = Modifier, label: String, value: String, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFF888888), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
fun StatusDot(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(10.dp).background(
            color = if (connected) Color(0xFF4CAF50) else Color(0xFF757575),
            shape = RoundedCornerShape(50)
        ))
        Text(
            text = if (connected) "Conectado" else "Aguardando",
            fontSize = 12.sp,
            color = if (connected) Color(0xFF4CAF50) else Color(0xFF757575)
        )
    }
}

private fun formatTemp(value: String) = try { "%.1f°C".format(value.toFloat()) } catch (_: Exception) { value }
