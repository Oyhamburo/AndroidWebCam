package com.example.celsocam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.celsocam.signaling.SignalingClient
import com.example.celsocam.ui.ControlsSheet
import com.example.celsocam.util.NsdHelper
import com.example.celsocam.util.OrientationHelper
import com.example.celsocam.webrtc.WebRtcController
import okhttp3.OkHttpClient
import org.webrtc.SurfaceViewRenderer
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private val PREFS by lazy { getSharedPreferences("celsocam", MODE_PRIVATE) }
    private var lastUrl: String? = null

    // UI
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var btnControls: Button
    private lateinit var btnReconnect: Button

    // Permisos
    private lateinit var cameraPermsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var micPermLauncher: ActivityResultLauncher<String>

    // Infra
    private lateinit var httpClient: OkHttpClient
    private lateinit var signaling: SignalingClient
    private lateinit var webrtc: WebRtcController
    private lateinit var orientationHelper: OrientationHelper


    private var nsd: NsdHelper? = null
    private val main = Handler(Looper.getMainLooper())
    private var discoveryTimeoutPosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.localView)
        btnControls = findViewById(R.id.btnControls)
        btnReconnect = findViewById(R.id.btnReconnect)

        httpClient = OkHttpClient()

        lastUrl = PREFS.getString("ws_url", null)

        // 1) SignalingClient (no autoconectar; conectamos al descubrir o con fallback)
        signaling = SignalingClient(
            url = "", // 👈 sin placeholder
            httpClient = httpClient,
            onAnswer = { sdp -> webrtc.setRemoteAnswer(sdp) },
            onIceFromRemote = { cand -> webrtc.addRemoteIce(cand) },
            onBrowserReady = { webrtc.createOffer() },
            onConfig = { cfg -> webrtc.applyConfig(cfg) },
            onOpen = { runOnUiThread { toast("WS conectado") } },
            onClosed = { runOnUiThread { toast("WS cerrado") } },
            onReconnecting = { attempt, delayMs -> runOnUiThread { /* opcional */ } },
            onFailureCb = { t -> runOnUiThread { toast("WS error: ${t.message}") } },
            onRequestCaps = { webrtc.sendCapsNow() },
            onReconnected = { webrtc.ensureSignalingAndOffer() },
            autoConnect = false
        )

        // 2) WebRTC
        webrtc = WebRtcController(
            context = this,
            localRenderer = localView,
            signaling = signaling
        )

        // 3) Orientación
        orientationHelper = OrientationHelper(
            context = this,
            followDeviceOrientation = true,
            onOrientationLabel = { label -> signaling.sendOrientation(label) }
        )

        // 4) Permisos
        cameraPermsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val camGranted = result[Manifest.permission.CAMERA] == true
            if (camGranted) {
                webrtc.initAndStart()
                orientationHelper.enable()
                // Iniciar descubrimiento mDNS y (si existe) fallback a última URL
                startDiscoveryAndMaybeFallback()
            } else {
                val showCam = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                if (!showCam) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    toast("Habilitá Cámara en Ajustes")
                } else {
                    toast("Se requiere permiso de Cámara")
                }
            }
        }

        micPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) webrtc.setMicEnabled(true)
            else {
                webrtc.setMicEnabled(false)
                toast("Permiso de Micrófono denegado")
            }
        }

        // 5) Controles
        btnControls.setOnClickListener {
            ControlsSheet.show(
                activity = this,
                webrtc = webrtc,
                orientationHelper = orientationHelper,
                micPermissionLauncher = micPermLauncher
            )
        }

        // 6) Botón Reconectar manual
        btnReconnect.setOnClickListener {
            toast("Reconectando…")
            signaling.reconnectNow()
            webrtc.ensureSignalingAndOffer()
        }

        // 7) Arranque: pedir cámara o iniciar y luego descubrir/conectar
        val camPermGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (camPermGranted) {
            webrtc.initAndStart()
            orientationHelper.enable()
            startDiscoveryAndMaybeFallback()
        } else {
            cameraPermsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun startDiscoveryAndMaybeFallback() {
        nsd = NsdHelper(
            context = this,
            onResolved = { host, port, name ->
                val url = buildWsUrl(host, port)
                PREFS.edit().putString("ws_url", url).apply()
                runOnUiThread { toast("Descubierto: $name → $url") }
                signaling.updateUrl(url, reconnect = true)
            },
            onError = { err -> runOnUiThread { toast("NSD: $err") } }
        ).also { it.start() }

        // Fallback si tenés última URL guardada
        lastUrl?.let { url ->
            signaling.updateUrl(url, reconnect = true)
        }

        // Timeout: si en 5s no llegó nada y no hay URL guardada, avisamos
        if (!discoveryTimeoutPosted) {
            discoveryTimeoutPosted = true
            main.postDelayed({
                discoveryTimeoutPosted = false
                if (lastUrl == null && !signaling.isConnected) {
                    toast("No se descubrió el servidor por mDNS. Revisá firewall UDP/5353 o usá el botón Reconectar luego de abrir el server.")
                }
            }, 5000)
        }
    }


    private fun buildWsUrl(host: String, port: Int): String {
        val h = if (host.contains(":")) "[$host]" else host
        return "ws://$h:$port"
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.enable()
        webrtc.tryResumeCapture()
        nsd?.start()
    }

    override fun onPause() {
        super.onPause()
        orientationHelper.disable()
        webrtc.tryPauseCapture()
        nsd?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        nsd?.stop()
        orientationHelper.shutdown()
        webrtc.releaseAll()
        signaling.close()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
