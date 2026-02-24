package com.coderred.andclaw.proot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * 게이트웨이 WebSocket JSON-RPC 클라이언트.
 *
 * 프로토콜:
 * - 요청: {"type":"req","id":"<uuid>","method":"<method>","params":{...}}
 * - 응답: {"type":"res","id":"<id>","ok":true,"payload":{...}}
 * - 이벤트: {"type":"event","event":"<name>","payload":{...}}
 */
class GatewayWsClient(private val prootManager: ProotManager) {

    companion object {
        private const val TAG = "GatewayWsClient"
        private const val MAX_CLI_OUTPUT_CHARS = 1_000_000
        private val METHOD_NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    @Volatile
    private var lastCallErrorMessage: String? = null

    fun getLastCallErrorMessage(): String? {
        val msg = lastCallErrorMessage?.trim().orEmpty()
        return msg.ifBlank { null }
    }

    /**
     * openclaw.json에서 gateway.auth.token을 읽어 반환한다.
     */
    fun getAuthToken(): String {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")
        if (!configFile.exists()) return ""
        return try {
            val json = JSONObject(configFile.readText())
            json.optJSONObject("gateway")?.optJSONObject("auth")?.optString("token", "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * WebSocket 연결 + connect 핸드셰이크.
     * @return 연결 성공 여부
     */
    suspend fun connect(): Boolean {
        val token = getAuthToken()
        Log.d(TAG, "connect: tokenPresent=${token.isNotBlank()}")
        if (token.isBlank()) return false

        // WebSocket 연결
        val connected = suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url("ws://127.0.0.1:18789")
                .header("Origin", "http://localhost:18789")
                .build()

            Log.d(TAG, "connect: opening WebSocket to ws://127.0.0.1:18789")
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "connect: WebSocket opened")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "onMessage: ${text.take(200)}")
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "connect: WebSocket failure: ${t.message}", t)
                    if (cont.isActive) cont.resume(false)
                    // 모든 대기 중인 요청 실패 처리
                    pendingRequests.values.forEach { deferred ->
                        deferred.completeExceptionally(t)
                    }
                    pendingRequests.clear()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    pendingRequests.values.forEach { deferred ->
                        deferred.completeExceptionally(Exception("WebSocket closed: $reason"))
                    }
                    pendingRequests.clear()
                }
            })

            cont.invokeOnCancellation { ws?.cancel() }
        }

        Log.d(TAG, "connect: WebSocket connected=$connected")
        if (!connected) return false

        // connect 핸드셰이크
        Log.d(TAG, "connect: sending handshake")
        val handshakeResult = try {
            call(
                "connect",
                JSONObject().apply {
                    put("minProtocol", 3)
                    put("maxProtocol", 3)
                    put("client", JSONObject().apply {
                        put("id", "webchat-ui")
                        put("version", "dev")
                        put("platform", "android")
                        put("mode", "webchat")
                    })
                    // Newer gateway builds require operator scopes for privileged RPCs
                    // such as `web.login.start` (WhatsApp QR bootstrap).
                    put("role", "operator")
                    put("scopes", JSONArray().apply {
                        put("operator.read")
                        put("operator.write")
                        put("operator.admin")
                    })
                    put("auth", JSONObject().apply {
                        put("token", token)
                    })
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "connect: handshake exception: ${e.message}", e)
            null
        }

        Log.d(TAG, "connect: handshake result=${handshakeResult != null}")
        return handshakeResult != null
    }

    /**
     * RPC 메서드를 호출하고 응답을 기다린다.
     * @param method RPC 메서드 이름
     * @param params 파라미터 (빈 JSONObject 가능)
     * @param timeoutMs 타임아웃 (밀리초)
     * @return 성공 시 payload JSONObject, 실패 시 null
     */
    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000L,
    ): JSONObject? {
        lastCallErrorMessage = null
        val socket = ws ?: run {
            lastCallErrorMessage = "WebSocket not connected"
            return null
        }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        val request = JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        socket.send(request.toString())

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            lastCallErrorMessage = e.message
            Log.w(TAG, "call($method) failed: ${e.message}")
            pendingRequests.remove(id)
            null
        }
    }

    /**
     * WhatsApp QR 로그인을 시작한다.
     * @return QR 데이터 URL (data:image/png;base64,... 또는 일반 문자열), 실패 시 null
     */
    suspend fun startWhatsAppLogin(): String? {
        val params = JSONObject()
        val result = callViaGatewayCli("web.login.start", params, timeoutMs = 70_000L)

        Log.d(TAG, "startWhatsAppLogin result: ${result?.toString()?.take(300)}")
        val qrDataUrl = extractQrDataUrl(result)
        if (result != null && qrDataUrl == null) {
            lastCallErrorMessage = extractGatewayMessage(result) ?: "Gateway response missing qrDataUrl"
        }
        return qrDataUrl
    }

    suspend fun logoutChannel(channelId: String, accountId: String = "default"): Boolean {
        val safeChannel = channelId.trim()
        if (safeChannel.isBlank()) {
            lastCallErrorMessage = "Invalid channel id"
            return false
        }

        val params = JSONObject().apply {
            put("channel", safeChannel)
            put("accountId", accountId)
        }
        val result = callViaGatewayCli("channels.logout", params, timeoutMs = 40_000L) ?: return false

        if (!result.has("loggedOut")) {
            lastCallErrorMessage = extractGatewayMessage(result) ?: "Gateway response missing loggedOut"
            return false
        }

        val loggedOut = result.optBoolean("loggedOut", false)
        if (!loggedOut) {
            lastCallErrorMessage = extractGatewayMessage(result) ?: "Channel logout failed"
        }
        return loggedOut
    }

    fun isLastCallWhatsAppAlreadyLinked(): Boolean {
        val message = lastCallErrorMessage ?: return false
        return message.contains("already linked", ignoreCase = true)
    }

    /**
     * WhatsApp 로그인 완료를 대기한다.
     * @return 연결 성공 여부
     */
    suspend fun waitWhatsAppLogin(): Boolean {
        val params = JSONObject().apply {
            put("timeoutMs", 120_000)
        }
        val result = callViaGatewayCli("web.login.wait", params, timeoutMs = 130_000L)

        return result != null
    }

    /**
     * WebSocket 연결을 닫는다.
     */
    fun close() {
        ws?.close(1000, "Client closing")
        ws = null
        pendingRequests.values.forEach { deferred ->
            deferred.completeExceptionally(Exception("Client closed"))
        }
        pendingRequests.clear()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "res" -> {
                    val id = json.optString("id")
                    val deferred = pendingRequests.remove(id) ?: return
                    val ok = json.optBoolean("ok", false)
                    if (ok) {
                        deferred.complete(json.optJSONObject("payload") ?: JSONObject())
                    } else {
                        val error = json.optJSONObject("error")?.optString("message") ?: "Unknown error"
                        deferred.completeExceptionally(Exception(error))
                    }
                }
                // 이벤트는 현재 무시 (필요 시 확장 가능)
            }
        } catch (_: Exception) {
            // 잘못된 JSON은 무시
        }
    }

    private suspend fun callViaGatewayCli(
        method: String,
        params: JSONObject,
        timeoutMs: Long,
    ): JSONObject? {
        lastCallErrorMessage = null
        val safeMethod = method.takeIf { METHOD_NAME_REGEX.matches(it) } ?: run {
            lastCallErrorMessage = "Invalid RPC method: $method"
            return null
        }
        if (!ensureNodeCompatPatchFile()) {
            lastCallErrorMessage = "Missing runtime patch file: /root/.openclaw-patch.js"
            return null
        }
        val paramsArg = escapeSingleQuotedShell(params.toString())
        val token = getAuthToken()
        val tokenArg = token.takeIf { it.isNotBlank() }?.let { " --token '${escapeSingleQuotedShell(it)}'" } ?: ""
        val profileBootstrap = buildCliProfileBootstrap()
        val envBootstrap = buildGatewayCliEnvBootstrap(readConfigEnvVarNames())
        val cliBootstrap =
            "OPENCLAW_BIN=\"\"; " +
                "if [ -x /usr/local/bin/openclaw ]; then OPENCLAW_BIN=/usr/local/bin/openclaw; " +
                "elif command -v openclaw >/dev/null 2>&1; then OPENCLAW_BIN=\"$(command -v openclaw)\"; " +
                "else echo '{\"ok\":false,\"error\":{\"message\":\"openclaw binary not found\"}}'; exit 127; fi;"
        val attempts = listOf(
            // Local gateway fixed target (config/remote mode independent) with profile bootstrap.
            "$profileBootstrap $envBootstrap $cliBootstrap \"\$OPENCLAW_BIN\" gateway call $safeMethod --url ws://127.0.0.1:18789$tokenArg --timeout $timeoutMs --params '$paramsArg' --json",
            // Loopback hostname variant with profile bootstrap.
            "$profileBootstrap $envBootstrap $cliBootstrap \"\$OPENCLAW_BIN\" gateway call $safeMethod --url ws://localhost:18789$tokenArg --timeout $timeoutMs --params '$paramsArg' --json",
            // Fallback without profile bootstrap for environments where profile scripts are broken.
            "$envBootstrap $cliBootstrap \"\$OPENCLAW_BIN\" gateway call $safeMethod --url ws://127.0.0.1:18789$tokenArg --timeout $timeoutMs --params '$paramsArg' --json",
            // Loopback fallback without profile bootstrap.
            "$envBootstrap $cliBootstrap \"\$OPENCLAW_BIN\" gateway call $safeMethod --url ws://localhost:18789$tokenArg --timeout $timeoutMs --params '$paramsArg' --json",
        )

        var lastFailureMessage: String? = null
        for ((index, command) in attempts.withIndex()) {
            val result = executeWithResultCancellable(command, timeoutMs = timeoutMs + 10_000L)
            if (result == null) {
                lastFailureMessage = "CLI call failed to execute"
                Log.w(TAG, "callViaGatewayCli($safeMethod): attempt=${index + 1} executeWithResult returned null")
                continue
            }
            if (result.timedOut) {
                lastFailureMessage = "CLI call timed out"
                Log.w(TAG, "callViaGatewayCli($safeMethod): attempt=${index + 1} timed out")
                continue
            }

            val output = result.output.trim()
            if (result.exitCode != 0) {
                val reason = extractCliFailureReason(output)
                lastFailureMessage = reason ?: if (result.exitCode == 127) {
                    "openclaw binary not found"
                } else {
                    "CLI call failed (exit=${result.exitCode})"
                }
                Log.w(
                    TAG,
                    "callViaGatewayCli($safeMethod): attempt=${index + 1} failed exitCode=${result.exitCode}, output=${output.take(300)}",
                )
                continue
            }

            if (output.isBlank()) {
                lastFailureMessage = "CLI call returned empty output"
                Log.w(TAG, "callViaGatewayCli($safeMethod): attempt=${index + 1} empty output")
                continue
            }

            val parsed = parseJsonObjectFromOutput(output)
            if (parsed == null) {
                lastFailureMessage = "Failed to parse CLI JSON response"
                Log.w(TAG, "callViaGatewayCli($safeMethod): attempt=${index + 1} JSON parse failed, output=${output.take(300)}")
                continue
            }

            val unwrapped = unwrapGatewayCliResponse(parsed)
            if (unwrapped == null) {
                lastFailureMessage = getLastCallErrorMessage() ?: "Gateway CLI returned error"
                continue
            }
            return unwrapped
        }

        lastCallErrorMessage = lastFailureMessage ?: "CLI call failed"
        return null
    }

    /**
     * openclaw 설정 JSON들에서 `${ENV_NAME}` 형태로 참조하는 env 키를 수집한다.
     * gateway call CLI는 config를 파싱하므로, 이 키들이 비어 있으면 MissingEnvVarError가 발생할 수 있다.
     */
    private fun readConfigEnvVarNames(): Set<String> {
        val regex = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)[^}]*\}""")
        val envVars = linkedSetOf<String>()
        val openclawDir = File(prootManager.rootfsDir, "root/.openclaw")
        if (!openclawDir.exists()) return envVars

        val candidateFiles = linkedSetOf<File>().apply {
            add(File(openclawDir, "openclaw.json"))
            val agentsDir = File(openclawDir, "agents")
            if (agentsDir.exists()) {
                agentsDir.walkTopDown()
                    .maxDepth(8)
                    .filter {
                        it.isFile &&
                            it.extension.equals("json", ignoreCase = true) &&
                            it.length() <= 2_000_000L &&
                            !it.invariantSeparatorsPath.contains("/sessions/")
                    }
                    .forEach { add(it) }
            }
        }

        candidateFiles.forEach { file ->
            if (!file.exists()) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            regex.findAll(text).forEach { match ->
                envVars.add(match.groupValues[1])
            }
        }

        return envVars
    }

    /**
     * CLI RPC에서 config env 치환 실패를 막기 위해 기본 env를 선주입한다.
     * 이미 값이 설정된 경우 `${VAR:-fallback}` 패턴으로 기존 값을 유지한다.
     */
    private fun buildGatewayCliEnvBootstrap(configEnvVars: Set<String>): String {
        val knownEnvVars = linkedSetOf(
            "TELEGRAM_BOT_TOKEN",
            "DISCORD_BOT_TOKEN",
            "OPENROUTER_API_KEY",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "GOOGLE_API_KEY",
            "GEMINI_API_KEY",
            "BRAVE_API_KEY",
            "BRAVE_SEARCH_API_KEY",
            "OPENCLAW_GATEWAY_TOKEN",
            "NODE_OPTIONS",
            "UV_USE_IO_URING",
            "PLAYWRIGHT_BROWSERS_PATH",
            "HOME",
            "PATH",
            "LANG",
        )
        val fallbackByEnv = mapOf(
            "UV_USE_IO_URING" to "0",
            "PLAYWRIGHT_BROWSERS_PATH" to "/root/.cache/ms-playwright",
            "HOME" to "/root",
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "LANG" to "C.UTF-8",
        )

        val allEnvVars = linkedSetOf<String>().apply {
            addAll(knownEnvVars)
            addAll(configEnvVars)
        }

        val baseExports = allEnvVars
            .filterNot { it == "NODE_OPTIONS" }
            .joinToString(" ") { envName ->
            val fallback = fallbackByEnv[envName] ?: "__andclaw_env_placeholder__"
            "export $envName=\"${'$'}{" + envName + ":-${escapeDoubleQuotedShell(fallback)}}\";"
        }
        val nodeOptionsExport =
            "if [ -n \"${'$'}{NODE_OPTIONS:-}\" ]; then " +
                "case \" ${'$'}NODE_OPTIONS \" in " +
                "*\" --require /root/.openclaw-patch.js \"*) ;; " +
                "*) export NODE_OPTIONS=\"--require /root/.openclaw-patch.js ${'$'}NODE_OPTIONS\" ;; " +
                "esac; " +
                "else export NODE_OPTIONS=\"--require /root/.openclaw-patch.js\"; fi;"

        return "$baseExports $nodeOptionsExport"
    }

    private fun buildCliProfileBootstrap(): String {
        return "for f in /etc/profile /root/.profile /root/.bash_profile /root/.bashrc; do if [ -f \"\$f\" ] && /bin/sh -n \"\$f\" >/dev/null 2>&1; then . \"\$f\"; fi; done;"
    }

    private fun ensureNodeCompatPatchFile(): Boolean {
        val patchFile = File(prootManager.rootfsDir, "root/.openclaw-patch.js")
        if (patchFile.exists() && patchFile.length() > 0) return true

        val script = buildString {
            appendLine("const os = require('os');")
            appendLine("const _ni = os.networkInterfaces;")
            appendLine("os.networkInterfaces = function() {")
            appendLine("  try { return _ni.call(this); } catch(e) {")
            appendLine("    return {")
            appendLine("      lo: [{")
            appendLine("        address: '127.0.0.1',")
            appendLine("        netmask: '255.0.0.0',")
            appendLine("        family: 'IPv4',")
            appendLine("        mac: '00:00:00:00:00:00',")
            appendLine("        internal: true,")
            appendLine("        cidr: '127.0.0.1/8'")
            appendLine("      }]")
            appendLine("    };")
            appendLine("  }")
            appendLine("};")
        }

        return runCatching {
            patchFile.parentFile?.mkdirs()
            val tmpName = ".openclaw-patch.js.tmp.${System.currentTimeMillis()}.${Thread.currentThread().id}"
            val tmpFile = File(patchFile.parentFile, tmpName)
            tmpFile.writeText(script)
            if (!tmpFile.renameTo(patchFile)) {
                patchFile.writeText(script)
                tmpFile.delete()
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun executeWithResultCancellable(
        command: String,
        timeoutMs: Long,
    ): ProotManager.CommandResult? = withContext(Dispatchers.IO) {
        val cmd = prootManager.buildProotCommand(command)
        val env = prootManager.buildEnvironment(
            mapOf(
                "HOME" to "/root",
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "LANG" to "C.UTF-8",
                "UV_USE_IO_URING" to "0",
                "PLAYWRIGHT_BROWSERS_PATH" to "/root/.cache/ms-playwright",
            ),
        )

        val process = try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            pb.start()
        } catch (_: Exception) {
            return@withContext null
        }

        val outputBuffer = StringBuilder()
        val outputReader = thread(start = true, isDaemon = true, name = "gateway-cli-reader") {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val read = reader.read(chunk)
                        if (read <= 0) break
                        synchronized(outputBuffer) {
                            outputBuffer.append(chunk, 0, read)
                            if (outputBuffer.length > MAX_CLI_OUTPUT_CHARS) {
                                // Keep tail chunk so the final JSON response is preserved even on noisy logs.
                                outputBuffer.delete(0, outputBuffer.length - MAX_CLI_OUTPUT_CHARS)
                            }
                        }
                    }
                }
            }
        }

        fun readCapturedOutput(): String {
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
            while (outputReader.isAlive && System.nanoTime() < deadlineNs) {
                outputReader.join(100)
            }
            return synchronized(outputBuffer) { outputBuffer.toString().trim() }
        }

        val startedAt = System.nanoTime()
        try {
            while (true) {
                coroutineContext.ensureActive()

                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    return@withContext ProotManager.CommandResult(
                        exitCode = process.exitValue(),
                        output = readCapturedOutput(),
                        timedOut = false,
                    )
                }

                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                if (elapsedMs >= timeoutMs) {
                    process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                    return@withContext ProotManager.CommandResult(
                        exitCode = -1,
                        output = readCapturedOutput(),
                        timedOut = true,
                    )
                }
            }
        } catch (e: CancellationException) {
            process.destroyForcibly()
            process.waitFor(1000, TimeUnit.MILLISECONDS)
            readCapturedOutput()
            throw e
        } catch (_: Exception) {
            process.destroyForcibly()
            process.waitFor(1000, TimeUnit.MILLISECONDS)
            readCapturedOutput()
            return@withContext null
        }

        return@withContext null
    }

    private fun parseJsonObjectFromOutput(output: String): JSONObject? {
        if (output.isBlank()) return null
        val normalizedOutput = stripAnsiEscapes(output).trim()
        if (normalizedOutput.isBlank()) return null

        runCatching { JSONObject(normalizedOutput) }
            .onSuccess { return it }

        // CLI output may include non-JSON logs before the final JSON payload.
        val lineCandidates = normalizedOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .asReversed()
            .mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()
            }
        lineCandidates.firstOrNull { looksLikeGatewayJson(it) }?.let { return it }
        lineCandidates.firstOrNull()?.let { return it }

        val first = normalizedOutput.indexOf('{')
        val last = normalizedOutput.lastIndexOf('}')
        if (first >= 0 && last > first) {
            val candidate = normalizedOutput.substring(first, last + 1)
            runCatching { JSONObject(candidate) }
                .onSuccess { return it }
        }

        return null
    }

    private fun looksLikeGatewayJson(json: JSONObject): Boolean {
        return json.has("ok") ||
            json.has("payload") ||
            json.has("error") ||
            json.has("qrDataUrl") ||
            json.has("qr_data_url")
    }

    private fun unwrapGatewayCliResponse(raw: JSONObject): JSONObject? {
        val looksEnvelope = raw.has("ok") && (raw.has("payload") || raw.has("error") || raw.has("id"))
        if (!looksEnvelope) return raw

        val ok = raw.optBoolean("ok", false)
        if (!ok) {
            val message = raw.optJSONObject("error")?.optString("message").orEmpty()
            lastCallErrorMessage = message.ifBlank { "Gateway CLI returned error" }
            return null
        }

        val payloadObj = raw.optJSONObject("payload")
        return payloadObj ?: JSONObject()
    }

    private fun extractCliFailureReason(output: String): String? {
        if (output.isBlank()) return null

        val parsed = parseJsonObjectFromOutput(output)
        if (parsed != null) {
            val errObj = parsed.optJSONObject("error")
            val errMsg = errObj?.optString("message").orEmpty().trim()
            if (errMsg.isNotBlank()) return errMsg

            val msg = parsed.optString("message").trim()
            if (msg.isNotBlank()) return msg
        }

        return stripAnsiEscapes(output).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(200)
    }

    private fun extractQrDataUrl(result: JSONObject?): String? {
        if (result == null) return null
        return findQrDataUrl(result)
    }

    private fun extractGatewayMessage(result: JSONObject?): String? {
        if (result == null) return null
        return findGatewayMessage(result)
    }

    private fun findGatewayMessage(node: JSONObject): String? {
        val message = node.optString("message").trim()
        if (message.isNotBlank()) return message

        val errorObject = node.optJSONObject("error")
        if (errorObject != null) {
            val errorMessage = errorObject.optString("message").trim()
            if (errorMessage.isNotBlank()) return errorMessage
        }

        val wrappers = listOf("payload", "result", "data", "response")
        for (wrapper in wrappers) {
            val child = node.optJSONObject(wrapper) ?: continue
            val found = findGatewayMessage(child)
            if (found != null) return found
        }

        return null
    }

    private fun findQrDataUrl(node: JSONObject): String? {
        val qrKeys = listOf("qrDataUrl", "qr_data_url", "qrDataURL", "qr", "qrUrl", "dataUrl")
        for (key in qrKeys) {
            val value = node.optString(key).ifBlank { null }
            if (value != null) return value
        }

        val wrappers = listOf("payload", "result", "data", "response")
        for (wrapper in wrappers) {
            val child = node.optJSONObject(wrapper) ?: continue
            val found = findQrDataUrl(child)
            if (found != null) return found
        }

        return null
    }

    private fun escapeSingleQuotedShell(value: String): String {
        return value.replace("'", "'\"'\"'")
    }

    private fun escapeDoubleQuotedShell(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun stripAnsiEscapes(value: String): String {
        // Strip ANSI CSI sequences that may appear in CLI logs and break JSON parsing.
        return value.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
    }
}
