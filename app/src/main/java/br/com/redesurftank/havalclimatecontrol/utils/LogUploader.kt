package br.com.redesurftank.havalclimatecontrol.utils

import android.content.Context
import android.os.Build
import android.util.Log
import br.com.redesurftank.havalclimatecontrol.ClimateStateHolder
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coleta os logs do app e faz upload para o Firebase Storage do projeto
 * `havalenginereverse` (o mesmo do app irmão), em `logs/`.
 *
 * Por que REST e não o SDK do Firebase: o `google-services.json` do projeto irmão
 * registra só os applicationIds DELE, e o plugin Gradle `google-services` falha o
 * build quando o applicationId não está no arquivo ("No matching client found").
 * Adicionar o SDK aqui exigiria registrar este pacote no console e trocar o JSON —
 * e, sem isso, quebraria o build e o CI. A API REST não precisa de nada disso:
 * autentica anonimamente pelo Identity Toolkit e sobe por HTTP.
 *
 * Tudo aqui é bloqueante — chame de uma thread de fundo.
 */
object LogUploader {

    private const val TAG = "LogUploader"

    // Mesmo projeto/bucket do haval-engine-reverse (app/google-services.json).
    private const val API_KEY = "AIzaSyDZB2Uwb3ZRVteDX-LN0lKrdk2LR8qVRws"
    private const val BUCKET  = "havalenginereverse.firebasestorage.app"

    private const val SIGNUP_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY"

    /**
     * Quantas entradas pedimos ao logd. `-t N` faz o tail NO SERVIDOR, antes de
     * qualquer filtro do cliente — então silenciar tags não alarga a janela de
     * tempo, só devolve menos linhas do mesmo intervalo. Por isso pedimos muito e
     * cortamos aqui: medido em campo, 3000 entradas cruas cobriam 1min44.
     */
    private const val LOGCAT_BUFFER_LINES = 20_000

    /** Teto do que vai para o arquivo depois do filtro. */
    private const val LOGCAT_KEEP_LINES = 4000

    /** O buffer `crash` é minúsculo e é onde mora o que interessa de verdade. */
    private const val LOGCAT_CRASH_LINES = 300

    /**
     * Tags do OEM que só fazem volume. Medido no log de 2026-08-23: sete tags
     * ocupavam 57% de uma janela de 1min44, e `PhoneInterfaceManager: No UICC`
     * sozinho fazia ~7 linhas/s. `TransferThread` NÃO entra aqui de propósito — é
     * sinal de diagnóstico do Shizuku. Nada com ':' no nome: o filterspec do logcat
     * é `tag:prioridade`, e um ':' no meio da tag quebra o parsing.
     */
    private val LOGCAT_NOISY_TAGS = listOf(
        "PhoneInterfaceManager", "RBSLibWrapper", "BeanSystemUILog",
        "GnpSdk", "SBRAudio", "AppList", "beantee", "IqqiInputType"
    )

    private val LOGCAT_STAMP = Regex("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+")

    /** Resultado do upload: URL de download, ou a mensagem de erro. */
    sealed class Result {
        data class Ok(val url: String, val sizeBytes: Int) : Result()
        data class Err(val message: String) : Result()
    }

    /**
     * Monta o pacote de log e sobe. `onProgress` é chamado na thread do chamador.
     */
    fun collectAndUpload(context: Context, onProgress: (String) -> Unit): Result {
        return try {
            onProgress("Coletando logs…")
            val content = collect(context)
            val bytes   = content.toByteArray(Charsets.UTF_8)

            onProgress("Autenticando…")
            val token = signInAnonymously()
                ?: return Result.Err("falha na autenticação anônima")

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val name  = "climate_$stamp.txt"
            onProgress("Enviando $name (${bytes.size / 1024} KB)…")

            val url = upload(name, bytes, token)
                ?: return Result.Err("falha no upload")
            Result.Ok(url, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "upload falhou", e)
            Result.Err(e.message ?: "erro desconhecido")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Coleta
    // ─────────────────────────────────────────────────────────────

    /**
     * Junta num único texto tudo que é útil pra diagnosticar: versões, estado do
     * veículo, histórico de ações do app, status das duas injeções Frida, os logs
     * que os scripts escrevem em /data/local/tmp e o logcat do nosso processo.
     */
    private fun collect(context: Context): String {
        val state = ClimateStateHolder
        val now   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "?" }

        return buildString {
            appendLine("===== Haval Climate Control — log =====")
            appendLine("gerado em      : $now")
            appendLine("versao do app  : $version")
            appendLine("device         : ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("build           : ${Build.DISPLAY}")
            appendLine()

            appendLine("----- propriedades do sistema -----")
            listOf(
                "persist.bean.country.code",
                "persist.vendor.gwm.cfg.regional",
                "ro.build.version.incremental"
            ).forEach { appendLine("$it = ${shell(arrayOf("getprop", it))}") }
            appendLine()

            appendLine("----- estado do veiculo -----")
            appendLine("conectado           : ${state.vehicleConnected}")
            appendLine("temp interna        : ${state.insideTemp}")
            appendLine("temp externa        : ${state.outsideTemp}")
            appendLine("temp setada         : ${state.driverTemp}")
            appendLine("power_mode          : ${state.powerMode}")
            appendLine("auto_enable         : ${state.autoEnable}")
            appendLine("ac_enable           : ${state.acEnable}")
            appendLine("pm2.5               : ${state.pm25Value}")
            appendLine("vent motorista      : ${state.driverSeatVentLevel}")
            appendLine("vent passageiro     : ${state.passengerSeatVentLevel}")
            appendLine("modo conforto       : ${state.comfortMode}")
            appendLine()

            appendLine("----- configuracoes -----")
            appendLine("temp externa real   : ${state.realOutsideTempEnabled}")
            appendLine("card na home        : ${state.homeCardEnabled}")
            appendLine("controle automatico : ${state.autoControlEnabled}")
            appendLine()

            appendLine("----- injecoes Frida -----")
            appendLine("binarios embutidos  : ${FridaUtils.fridaToolsEmbedded()}")
            appendLine("systemui pid        : ${FridaUtils.systemUiPid()}")
            appendLine("systemui injetado   : ${FridaUtils.isInjectionAlive()}")
            appendLine("mediacenter pid     : ${FridaUtils.mediaCenterPid()}")
            appendLine("mediacenter injetado: ${FridaUtils.isHomeCardInjectionAlive()}")
            appendLine("arquivo de controle : ${shell(arrayOf("cat", FridaUtils.HOME_CARD_CTRL_PATH))}")
            appendLine()

            appendLine("----- historico de acoes (${state.actionLog.size}) -----")
            if (state.actionLog.isEmpty()) appendLine("(vazio)")
            else state.actionLog.forEach { appendLine(it) }
            appendLine()

            listOf(
                FridaUtils.TARGET_SYSTEM_UI,
                FridaUtils.TARGET_MEDIA_CENTER
            ).forEach { t ->
                appendLine("----- ${t.logPath()} -----")
                val body = shell(arrayOf("cat", t.logPath()))
                appendLine(if (body.isBlank()) "(vazio ou inacessivel)" else body)
                appendLine()
            }

            // Este e o log que sobrevive a morte do processo — vem primeiro porque, na
            // pratica, e ele que responde "por que reiniciou?". Ver PersistentLog.
            appendLine("----- log persistente em disco -----")
            val persisted = PersistentLog.dump(PersistentLog.DUMP_MAX_CHARS)
            appendLine(if (persisted.isBlank()) "(vazio)" else persisted.trim())
            appendLine()

            if (!ShizukuUtils.isAvailable()) {
                appendLine("----- logcat -----")
                appendLine("(Shizuku indisponivel — logcat nao coletado)")
            } else {
                appendLine("----- logcat: buffer crash -----")
                appendLine(crashBuffer())
                appendLine()

                appendLine("----- logcat: buffer principal -----")
                appendLine(mainLogcat())
            }
        }
    }

    /**
     * Roda um comando por Shizuku. Em caso de falha devolve a explicação entre
     * parênteses em vez de "" — no log de diagnóstico, saber que o comando falhou
     * (e por quê) vale mais que um campo em branco.
     */
    private fun shell(cmd: Array<String>): String {
        val r = try {
            ShizukuUtils.run(cmd)
        } catch (e: Exception) {
            return "(falhou: ${e.message})"
        }
        return when {
            r.stdout.isNotBlank() -> r.stdout
            r.ok()                -> ""
            else                  -> "(falhou: ${r.describeFailure()})"
        }
    }

    /** Só os crashes. Buffer separado, pequeno e de altíssimo valor por byte. */
    private fun crashBuffer(): String {
        val r = ShizukuUtils.run(arrayOf(
            "logcat", "-b", "crash", "-d", "-v", "time", "-t", LOGCAT_CRASH_LINES.toString()
        ))
        return when {
            r.stdout.isNotBlank() -> r.stdout
            r.ok()                -> "(vazio — nenhum crash registrado)"
            else                  -> "(sem saida — ${r.describeFailure()})"
        }
    }

    /**
     * Buffer principal com as tags de ruído silenciadas, pedindo bem mais entradas
     * do que vamos guardar — é o que efetivamente alarga a janela de tempo.
     */
    private fun mainLogcat(): String {
        // O `*:V` no fim é obrigatório: com qualquer filterspec presente, o que não
        // for citado herda a prioridade default e o resultado viraria quase nada.
        val cmd = mutableListOf("logcat", "-d", "-v", "time", "-t", LOGCAT_BUFFER_LINES.toString())
        LOGCAT_NOISY_TAGS.forEach { cmd.add("$it:S") }
        cmd.add("*:V")

        var result = ShizukuUtils.run(cmd.toTypedArray())
        var note   = "${LOGCAT_NOISY_TAGS.size} tags do OEM silenciadas"

        // Se este device não aceitar o filterspec como esperado, a saída vem vazia ou
        // absurdamente curta — melhor cair pro logcat cru que entregar nada.
        if (countLines(result.stdout) < 50) {
            val raw = ShizukuUtils.run(arrayOf(
                "logcat", "-d", "-v", "time", "-t", LOGCAT_KEEP_LINES.toString()
            ))
            if (countLines(raw.stdout) > countLines(result.stdout)) {
                result = raw
                note   = "sem filtro (o filterspec nao pegou)"
            }
        }

        if (result.stdout.isBlank()) return "(sem saida — ${result.describeFailure()})"

        val all  = result.stdout.lines()
        val kept = if (all.size > LOGCAT_KEEP_LINES) all.takeLast(LOGCAT_KEEP_LINES) else all
        return "($note; ${kept.size} de ${all.size} linhas${describeWindow(kept)})\n" +
                kept.joinToString("\n")
    }

    private fun countLines(s: String): Int = if (s.isBlank()) 0 else s.count { it == '\n' } + 1

    /** ", janela HH:MM:SS → HH:MM:SS" — dá a dimensão temporal do que foi coletado. */
    private fun describeWindow(lines: List<String>): String {
        val stamps = lines.mapNotNull { LOGCAT_STAMP.find(it)?.value }
        if (stamps.isEmpty()) return ""
        return ", janela ${stamps.first()} → ${stamps.last()}"
    }

    // ─────────────────────────────────────────────────────────────
    // Firebase por REST
    // ─────────────────────────────────────────────────────────────

    /** idToken de um usuário anônimo, ou null. As regras do bucket exigem auth. */
    private fun signInAnonymously(): String? {
        val conn = (URL(SIGNUP_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout    = 15_000
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write("{\"returnSecureToken\":true}")
            }
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "signUp HTTP ${conn.responseCode}: ${errorBody(conn)}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("idToken")
                .takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    /** Sobe os bytes e devolve a URL de download, ou null. */
    private fun upload(name: String, bytes: ByteArray, idToken: String): String? {
        val objectPath = URLEncoder.encode("logs/$name", "UTF-8")
        val url = "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o?name=$objectPath"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            doOutput       = true
            connectTimeout = 20_000
            readTimeout    = 60_000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Authorization", "Firebase $idToken")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "upload HTTP ${conn.responseCode}: ${errorBody(conn)}")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val token = json.optString("downloadTokens")
            "https://firebasestorage.googleapis.com/v0/b/$BUCKET/o/$objectPath?alt=media" +
                if (token.isNotEmpty()) "&token=$token" else ""
        } finally {
            conn.disconnect()
        }
    }

    private fun errorBody(conn: HttpURLConnection): String =
        try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
}
