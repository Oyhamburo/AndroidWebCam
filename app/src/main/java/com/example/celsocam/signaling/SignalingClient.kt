package com.example.celsocam.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "Signaling"

data class RemoteIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class ConfigState(
    val micEnabled: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val aspect: String,
    val camera: String,           // "back" | "front"
    val cameraName: String?,      // deviceName exacto si viene
    val iceServers: List<IceServer> // <— NUEVO
)

data class CamInfo(val name: String, val label: String, val facing: String)
data class FormatCaps(val w: Int, val h: Int, val fps: List<Int>)

class SignalingClient(
    private var url: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val onAnswer: (String) -> Unit,
    private val onIceFromRemote: (RemoteIce) -> Unit,
    private val onBrowserReady: () -> Unit,
    private val onConfig: (ConfigState) -> Unit,
    private val onOpen: () -> Unit = {},
    private val onClosed: () -> Unit = {},
    private val onReconnecting: (Int, Long) -> Unit = { _, _ -> },
    private val onFailureCb: (Throwable) -> Unit = {},
    private val onRequestCaps: () -> Unit = {},
    private val onReconnected: () -> Unit = {},
    private val autoConnect: Boolean = true
) {
    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())

    private var reconnectAttempts = 0
    private val baseDelayMs = 1500L
    private val maxDelayMs = 30_000L
    private var scheduledReconnect: Runnable? = null

    var isConnected: Boolean = false
        private set

    private fun post(block: () -> Unit) = main.post(block)

    private fun parseIceServers(from: JSONObject): List<IceServer> {
        val arr = from.optJSONArray("iceServers") ?: return emptyList()
        val out = mutableListOf<IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urlsJson = o.opt("urls")
            val urls = when (urlsJson) {
                is JSONArray -> (0 until urlsJson.length()).mapNotNull { urlsJson.optString(it, null) }.filter { it?.isNotBlank() == true }
                is String -> listOf(urlsJson)
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            val user = o.optString("username", null)?.takeIf { it.isNotEmpty() }
            val cred = o.optString("credential", null)?.takeIf { it.isNotEmpty() }
            out += IceServer(urls = urls, username = user, credential = cred)
        }
        return out
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            reconnectAttempts = 0
            Log.i(TAG, "WS OPEN -> $url")
            send(JSONObject(mapOf("role" to "android")))
            post { onOpen() }
            post { onReconnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: run {
                Log.w(TAG, "WS MESSAGE no-JSON: ${text.take(200)}")
                return
            }
            when (msg.optString("type")) {
                "browser-ready" -> { Log.d(TAG, "WS <- browser-ready"); post { onBrowserReady() } }
                "answer" -> { Log.d(TAG, "WS <- answer (${text.length}b)"); post { onAnswer(msg.optString("sdp")) } }
                "ice" -> post {
                    onIceFromRemote(
                        RemoteIce(
                            candidate = msg.optString("candidate"),
                            sdpMid = msg.optString("sdpMid"),
                            sdpMLineIndex = msg.optInt("sdpMLineIndex")
                        )
                    )
                }
                "config" -> {
                    val cfg = ConfigState(
                        micEnabled   = msg.optBoolean("micEnabled", false),
                        width        = msg.optInt("width", 1280),
                        height       = msg.optInt("height", 720),
                        fps          = msg.optInt("fps", 30),
                        bitrateKbps  = msg.optInt("bitrateKbps", 6000),
                        aspect       = msg.optString("aspect", "AUTO_MAX"),
                        camera       = msg.optString("camera", "back"),
                        cameraName   = msg.optString("cameraName", null)?.takeIf { it.isNotEmpty() && it != "null" },
                        iceServers   = parseIceServers(msg)
                    )
                    Log.d(TAG, "WS <- config: width=${cfg.width} height=${cfg.height} fps=${cfg.fps} br=${cfg.bitrateKbps} ice=${cfg.iceServers.size}")
                    post { onConfig(cfg) }
                }
                "request-caps" -> { Log.d(TAG, "WS <- request-caps"); post { onRequestCaps() } }
                "ping", "pong" -> {}
                else -> Log.d(TAG, "WS <- ${msg.optString("type")} (${text.length}b)")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            Log.w(TAG, "WS CLOSED code=$code reason=${reason.take(200)}")
            post { onClosed() }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            Log.e(TAG, "WS FAILURE url=$url err=${t.message} respCode=${response?.code}", t)
            post { onFailureCb(t) }
            scheduleReconnect()
        }
    }

    init {
        if (autoConnect) connect()
    }

    @Synchronized
    fun connect() {
        cancelScheduledReconnect()
        Log.i(TAG, "WS CONNECT -> $url")
        val req = Request.Builder().url(url).build()
        ws = try {
            httpClient.newWebSocket(req, wsListener)
        } catch (t: Throwable) {
            Log.e(TAG, "WS CONNECT THROW: ${t.message}", t)
            null
        }
    }

    fun updateUrl(newUrl: String, reconnect: Boolean = true) {
        Log.i(TAG, "WS URL UPDATE $url -> $newUrl")
        url = newUrl
        if (reconnect) reconnectNow()
    }

    fun reconnectNow() {
        Log.i(TAG, "WS RECONNECT NOW")
        reconnectAttempts = 0
        close()
        connect()
    }

    private fun scheduleReconnect() {
        reconnectAttempts += 1
        val delay = kotlin.math.min(maxDelayMs, baseDelayMs * (1 shl (reconnectAttempts - 1)))
        Log.w(TAG, "WS RECONNECT in ${delay}ms (attempt #$reconnectAttempts)")
        post { onReconnecting(reconnectAttempts, delay) }
        cancelScheduledReconnect()
        val r = Runnable { connect() }
        scheduledReconnect = r
        main.postDelayed(r, delay)
    }

    private fun cancelScheduledReconnect() {
        scheduledReconnect?.let { main.removeCallbacks(it) }
        scheduledReconnect = null
    }

    // ---------- Mensajes salientes ----------
    fun sendOrientation(label: String) {
        send(JSONObject(mapOf("type" to "orientation", "orientation" to label)))
    }

    fun sendOffer(sdp: String) {
        send(JSONObject(mapOf("type" to "offer", "sdp" to sdp)))
    }

    fun sendIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        send(JSONObject(mapOf("type" to "ice", "candidate" to candidate, "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex)))
    }

    fun ping() = send(JSONObject(mapOf("type" to "ping")))

    fun sendCaps(
        cameras: List<CamInfo>,
        formatsByCameraName: Map<String, List<FormatCaps>>,
        supportedAspects: List<String>? = null
    ) {
        val camsJson = JSONArray().apply {
            cameras.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name); put("label", c.label); put("facing", c.facing)
                })
            }
        }

        val formatsJson = JSONObject().apply {
            formatsByCameraName.forEach { (camName, list) ->
                put(camName, JSONArray().apply {
                    list.forEach { f ->
                        put(JSONObject().apply {
                            put("w", f.w); put("h", f.h); put("fps", JSONArray(f.fps))
                        })
                    }
                })
            }
        }

        val obj = JSONObject().apply {
            put("type", "caps")
            put("cameras", camsJson)
            put("formatsByCameraName", formatsJson)
            supportedAspects?.let { put("supportedAspects", JSONArray(it)) }
        }
        Log.d(TAG, "WS -> caps (cameras=${cameras.size})")
        send(obj)
    }

    private fun send(obj: JSONObject) {
        val s = obj.toString()
        val socket = ws
        if (socket == null) {
            Log.w(TAG, "WS SEND skip (socket null): ${s.take(120)}")
            return
        }
        try {
            socket.send(s)
            Log.v(TAG, "WS -> ${obj.optString("type")} (${s.length}b)")
        } catch (t: Throwable) {
            Log.e(TAG, "WS SEND error: ${t.message}", t)
        }
    }

    fun close() {
        cancelScheduledReconnect()
        try { ws?.close(1000, null) } catch (_: Throwable) {}
        ws = null
        isConnected = false
    }
}
