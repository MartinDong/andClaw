package com.coderred.andclaw.proot

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

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
        Log.d(TAG, "connect: token=${token.take(8)}... (blank=${token.isBlank()})")
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
        val socket = ws ?: return null
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
        val result = call("web.login.start", JSONObject(), timeoutMs = 60_000L)

        Log.d(TAG, "startWhatsAppLogin result: ${result?.toString()?.take(300)}")
        return result?.optString("qrDataUrl")?.ifBlank { null }
    }

    /**
     * WhatsApp 로그인 완료를 대기한다.
     * @return 연결 성공 여부
     */
    suspend fun waitWhatsAppLogin(): Boolean {
        val result = call("web.login.wait", JSONObject(), timeoutMs = 120_000L)

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
}
