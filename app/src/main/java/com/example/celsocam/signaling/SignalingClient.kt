package com.example.celsocam.signaling

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

// ICE remoto
data class RemoteIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

// Config que llega del servidor (incluye cameraName opcional)
data class ConfigState(
    val micEnabled: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val aspect: String,
    val camera: String,           // "back" | "front" (fallback)
    val cameraName: String?       // deviceName exacto si viene (ej: "0","1","3")
)

// ----- Tipos para enviar capacidades (/api/caps) -----
data class CamInfo(
    val name: String,             // deviceName real de WebRTC (p.ej. "0","1")
    val label: String,            // texto para mostrar (ej: "Trasera 0 (0)")
    val facing: String            // "front" | "back" | "other"
)

data class FormatCaps(
    val w: Int,
    val h: Int,
    val fps: List<Int>            // lista de FPS soportados para ese WxH
)

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
    private val autoConnect: Boolean = true
) {
    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())

    // Backoff reconexión
    private var reconnectAttempts = 0
    private val baseDelayMs = 1500L
    private val maxDelayMs = 30_000L
    private var scheduledReconnect: Runnable? = null

    var isConnected: Boolean = false
        private set

    private fun post(block: () -> Unit) = main.post(block)

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            reconnectAttempts = 0
            // Identificarnos
            send(JSONObject(mapOf("role" to "android")))
            post { onOpen() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (msg.optString("type")) {
                "browser-ready" -> post { onBrowserReady() }
                "answer" -> post { onAnswer(msg.optString("sdp")) }
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
                        cameraName   = msg.optString("cameraName", null)
                            ?.takeIf { it.isNotEmpty() && it != "null" }
                    )
                    post { onConfig(cfg) }
                }
                "ping" -> { /* opcional: responder pong */ }
                "pong" -> { /* noop */ }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            post { onClosed() }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            post { onFailureCb(t) }
            scheduleReconnect()
        }
    }

    init {
        if (autoConnect) connect()
    }

    // ---------- Conexión pública ----------
    @Synchronized
    fun connect() {
        cancelScheduledReconnect()
        val req = Request.Builder().url(url).build()
        ws = httpClient.newWebSocket(req, wsListener)
    }

    fun updateUrl(newUrl: String, reconnect: Boolean = true) {
        url = newUrl
        if (reconnect) reconnectNow()
    }

    fun reconnectNow() {
        reconnectAttempts = 0
        close()
        connect()
    }

    private fun scheduleReconnect() {
        reconnectAttempts += 1
        val delay = min(maxDelayMs, baseDelayMs * (1 shl (reconnectAttempts - 1)))
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
        send(
            JSONObject(
                mapOf(
                    "type" to "ice",
                    "candidate" to candidate,
                    "sdpMid" to sdpMid,
                    "sdpMLineIndex" to sdpMLineIndex
                )
            )
        )
    }

    fun ping() = send(JSONObject(mapOf("type" to "ping")))

    /**
     * Enviar capacidades del dispositivo (para que /api/caps liste TODAS las cámaras y formatos).
     *
     * @param cameras lista de cámaras físicas
     * @param formatsByCameraName mapa: deviceName -> lista de formatos con FPS soportados
     * @param supportedAspects lista de aspectos soportados (opcional). Ej: ["AUTO_MAX","R16_9","R4_3","R1_1"]
     */
    fun sendCaps(
        cameras: List<CamInfo>,
        formatsByCameraName: Map<String, List<FormatCaps>>,
        supportedAspects: List<String>? = null
    ) {
        val camsJson = JSONArray().apply {
            cameras.forEach { c ->
                put(
                    JSONObject().apply {
                        put("name", c.name)
                        put("label", c.label)
                        put("facing", c.facing)
                    }
                )
            }
        }

        val formatsJson = JSONObject().apply {
            formatsByCameraName.forEach { (camName, list) ->
                put(
                    camName,
                    JSONArray().apply {
                        list.forEach { f ->
                            put(
                                JSONObject().apply {
                                    put("w", f.w)
                                    put("h", f.h)
                                    put("fps", JSONArray(f.fps))
                                }
                            )
                        }
                    }
                )
            }
        }

        val obj = JSONObject().apply {
            put("type", "caps")
            put("cameras", camsJson)
            put("formatsByCameraName", formatsJson)
            supportedAspects?.let { put("supportedAspects", JSONArray(it)) }
        }

        send(obj)
    }

    // ---------- Utilidades ----------
    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun close() {
        cancelScheduledReconnect()
        ws?.close(1000, null)
        ws = null
        isConnected = false
    }
}
