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
import com.example.celsocam.util.OrientationHelper
import com.example.celsocam.util.NetworkBinder
import com.example.celsocam.webrtc.WebRtcController
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private val HOST_IP = "192.168.0.53" // <-- tu IP LAN
    private val WS_URL: String = "ws://$HOST_IP:8080"
    private val HTTP_URL: String = "http://$HOST_IP:8080/api/config"

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var btnControls: Button
    private lateinit var btnReconnect: Button

    private lateinit var cameraPermsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var micPermLauncher: ActivityResultLauncher<String>

    private lateinit var httpClient: OkHttpClient
    private lateinit var signaling: SignalingClient
    private lateinit var webrtc: WebRtcController
    private lateinit var orientationHelper: OrientationHelper
    private lateinit var networkBinder: NetworkBinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.localView)
        btnControls = findViewById(R.id.btnControls)
        btnReconnect = findViewById(R.id.btnReconnect)

        httpClient = OkHttpClient()
        networkBinder = NetworkBinder(this)


        // 2) Signaling (NO autoConnect) con onReconnected -> resetPeer + offer
        signaling = SignalingClient(
            url = WS_URL,
            httpClient = httpClient,
            onAnswer = { sdp -> webrtc.setRemoteAnswer(sdp) },
            onIceFromRemote = { cand -> webrtc.addRemoteIce(cand) },
            onBrowserReady = { webrtc.createOffer() },
            onConfig = { cfg -> webrtc.applyConfig(cfg) },
            onOpen = { toast("WS conectado a $WS_URL") },
            onClosed = { toast("WS cerrado") },
            onReconnecting = { _, _ -> },
            onFailureCb = { err -> toast("WS FAILURE: ${err.message}") },
            onRequestCaps = { webrtc.sendCapsNow() },
            onReconnected = {
                // 🔑 cuando el WS vuelve a abrir, reseteamos y renegociamos
                webrtc.resetPeer()
                webrtc.createOffer()
            },
            autoConnect = false
        )

        // reasignar el signaling real al controlador (el de arriba era placeholder)
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
                startAndConnect()
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
            else { webrtc.setMicEnabled(false); toast("Permiso de Micrófono denegado") }
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

        // 6) Reconectar manual
        btnReconnect.setOnClickListener {
            toast("Reconectando…")
            val bound = networkBinder.bindWifiIfPresent()
            if (!bound) toast("Wi-Fi no disponible (¿VPN/Guest?)")
            signaling.reconnectNow()
            webrtc.ensureSignalingAndOffer()
            httpPing()
        }

        // 7) Arranque
        val camPermGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (camPermGranted) startAndConnect() else {
            cameraPermsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun startAndConnect() {
        webrtc.initAndStart()
        orientationHelper.enable()
        val bound = networkBinder.bindWifiIfPresent()
        if (bound) toast("Usando red Wi-Fi para señalización") else toast("No se encontró Wi-Fi (¿VPN/Guest?)")
        signaling.reconnectNow()
        httpPing()
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.enable()
        webrtc.tryResumeCapture()
        if (!signaling.isConnected) {
            networkBinder.bindWifiIfPresent()
            signaling.connect()
            httpPing()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationHelper.disable()
        webrtc.tryPauseCapture()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationHelper.shutdown()
        webrtc.releaseAll()
        signaling.close()
        // networkBinder.unbind() opcional
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun httpPing() {
        Thread {
            try {
                val r = httpClient.newCall(Request.Builder().url(HTTP_URL).build()).execute()
                toast("HTTP OK ${r.code} /api/config"); r.close()
            } catch (t: Throwable) { toast("HTTP FAIL: ${t.message}") }
        }.start()
    }
}
