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

    /** Buffer de eventos: pedimos muito e filtramos aqui, igual ao principal. */
    private const val LOGCAT_EVENTS_LINES = 3000
    private const val LOGCAT_EVENTS_KEEP  = 400

    /**
     * Eventos que dizem quem matou quem. `am_proc_died`/`am_kill` identificam o
     * responsável pela morte de um processo — o buffer de crash só mostra o efeito.
     */
    private val EVENT_KEYS = listOf(
        "am_proc_died", "am_proc_start", "am_kill", "am_crash", "am_anr", "am_wtf",
        "am_low_memory", "am_restart", "watchdog", "boot_progress_start"
    )

    // ── Dropbox ──────────────────────────────────────────────────────────────
    // Onde o Android arquiva a primeira excecao de cada crash, inclusive as do
    // system_server. É o unico lugar que responde por que o framework morreu.

    private const val DROPBOX_DIR = "/data/system/dropbox"
    /** Quantos arquivos abrimos por inteiro (os mais recentes). */
    private const val DROPBOX_MAX_ENTRIES = 3
    /** Teto por arquivo. Cortamos o FIM: num stack trace o topo é o que importa. */
    private const val DROPBOX_MAX_CHARS_EACH = 20_000
    /** Quantos nomes listamos para dar o panorama sem abrir todos. */
    private const val DROPBOX_MAX_LISTED = 40

    /** Prefixos que valem a pena. O dropbox também guarda muita coisa irrelevante. */
    private val DROPBOX_INTERESTING = listOf(
        "system_server_crash", "system_server_wtf", "system_server_anr",
        "system_server_watchdog", "SYSTEM_TOMBSTONE", "SYSTEM_RESTART",
        "system_app_crash", "system_app_anr", "system_app_wtf",
        "data_app_crash", "data_app_anr", "data_app_wtf",
        "SYSTEM_LAST_KMSG", "SYSTEM_BOOT"
    )

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
                // O dropbox guarda a PRIMEIRA excecao — o "earlier logs will point to
                // the root cause" que o DeadSystemException menciona e que o buffer de
                // crash nao tem. Ver a analise do log de 2026-08-24.
                appendLine("----- dropbox ($DROPBOX_DIR) -----")
                appendLine(dropbox())
                appendLine()

                appendLine("----- logcat: buffer crash -----")
                appendLine(crashBuffer())
                appendLine()

                // Diz QUEM matou o processo, em vez de so mostrar o efeito.
                appendLine("----- logcat: buffer events (filtrado) -----")
                appendLine(eventsBuffer())
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

    /**
     * As entradas mais recentes do dropbox. Lista o panorama e abre por inteiro só
     * as [DROPBOX_MAX_ENTRIES] últimas — um `system_server_crash` traz o stack trace
     * da primeira exceção, que é a resposta que o `DeadSystemException` do buffer de
     * crash não dá.
     */
    private fun dropbox(): String {
        val ls = ShizukuUtils.run(arrayOf("ls", DROPBOX_DIR))
        if (ls.stdout.isBlank()) {
            // Sem root no Shizuku este diretório não é legível — vale dizer isso em vez
            // de deixar a seção em branco.
            return "(nada legivel — ${ls.describeFailure()})"
        }

        val all = ls.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hits = all.filter { name -> DROPBOX_INTERESTING.any { name.startsWith(it) } }
            .sortedByDescending { dropboxStamp(it) }
        if (hits.isEmpty()) {
            return "(${all.size} entradas, nenhuma de interesse)"
        }

        return buildString {
            appendLine("${all.size} entradas no total, ${hits.size} de interesse " +
                    "(abrindo as ${minOf(hits.size, DROPBOX_MAX_ENTRIES)} mais recentes):")
            hits.take(DROPBOX_MAX_LISTED).forEach { appendLine("  $it") }
            if (hits.size > DROPBOX_MAX_LISTED) appendLine("  … e ${hits.size - DROPBOX_MAX_LISTED} outras")
            hits.take(DROPBOX_MAX_ENTRIES).forEach { name ->
                appendLine()
                appendLine("··· $name ···")
                appendLine(readDropboxEntry(name))
            }
        }
    }

    /** Os nomes são `tag@<epoch em ms>.txt[.gz]`; o epoch é a ordenação confiável. */
    private fun dropboxStamp(name: String): Long =
        name.substringAfter('@', "").substringBefore('.').toLongOrNull() ?: 0L

    private fun readDropboxEntry(name: String): String {
        val path = "$DROPBOX_DIR/$name"
        val gz   = name.endsWith(".gz")
        var r = ShizukuUtils.run(arrayOf(if (gz) "zcat" else "cat", path))
        // Nem todo toybox traz zcat; gunzip -c é o plano B antes de desistir.
        if (gz && r.stdout.isBlank()) r = ShizukuUtils.run(arrayOf("gunzip", "-c", path))
        if (r.stdout.isBlank()) return "(sem conteudo — ${r.describeFailure()})"
        return if (r.stdout.length > DROPBOX_MAX_CHARS_EACH)
            r.stdout.take(DROPBOX_MAX_CHARS_EACH) +
                    "\n(truncado — arquivo tem ${r.stdout.length} chars)"
        else r.stdout
    }

    /** Buffer de eventos reduzido ao que identifica mortes e reinícios de processo. */
    private fun eventsBuffer(): String {
        val r = ShizukuUtils.run(arrayOf(
            "logcat", "-b", "events", "-d", "-v", "time", "-t", LOGCAT_EVENTS_LINES.toString()
        ))
        if (r.stdout.isBlank()) return "(sem saida — ${r.describeFailure()})"

        val hits = r.stdout.lines().filter { line -> EVENT_KEYS.any { line.contains(it) } }
        if (hits.isEmpty()) {
            return "(${countLines(r.stdout)} eventos lidos, nenhum de interesse" +
                    "${describeWindow(r.stdout.lines())})"
        }
        val kept = if (hits.size > LOGCAT_EVENTS_KEEP) hits.takeLast(LOGCAT_EVENTS_KEEP) else hits
        return "(${kept.size} de ${countLines(r.stdout)} eventos${describeWindow(kept)})\n" +
                kept.joinToString("\n")
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
