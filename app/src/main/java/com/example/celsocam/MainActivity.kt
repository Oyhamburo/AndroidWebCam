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
import com.example.celsocam.webrtc.WebRtcController
import okhttp3.OkHttpClient
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    private val WS_URL: String = "ws://192.168.0.53:8080"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.localView)
        btnControls = findViewById(R.id.btnControls)
        btnReconnect = findViewById(R.id.btnReconnect)

        httpClient = OkHttpClient()

        // 1) Crear SignalingClient SIN autoconectar
        signaling = SignalingClient(
            url = WS_URL,
            httpClient = httpClient,
            onAnswer = { sdp -> webrtc.setRemoteAnswer(sdp) },
            onIceFromRemote = { cand -> webrtc.addRemoteIce(cand) },
            onBrowserReady = { webrtc.createOffer() },
            onConfig = { cfg -> webrtc.applyConfig(cfg) },
            onOpen = { /* toasts/status */ },
            onClosed = { /* status */ },
            onReconnecting = { _, _ -> },
            onFailureCb = { /* log */ },
            autoConnect = false
        )



        // 2) WebRTC ya puede depender de signaling con seguridad
        webrtc = WebRtcController(
            context = this,
            localRenderer = localView,
            signaling = signaling
        )

        // 3) Orientación → receptor
        orientationHelper = OrientationHelper(
            context = this,
            followDeviceOrientation = true,
            onOrientationLabel = { label ->
                signaling.sendOrientation(label)
            }
        )

        // 4) Permisos
        cameraPermsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val camGranted = result[Manifest.permission.CAMERA] == true
            if (camGranted) {
                webrtc.initAndStart()
                orientationHelper.enable()
                // Conecta/renegocia ahora
                signaling.reconnectNow()
            } else {
                val showCam = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                if (!showCam) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    Toast.makeText(this, "Habilitá Cámara en Ajustes", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Se requiere permiso de Cámara", Toast.LENGTH_SHORT).show()
                }
            }
        }


        micPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                // Habilitar micrófono si se dio el permiso
                webrtc.setMicEnabled(true)
            } else {
                // Deshabilitarlo si se negó
                webrtc.setMicEnabled(false)
                Toast.makeText(this, "Permiso de Micrófono denegado", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Reconectando…", Toast.LENGTH_SHORT).show()
            signaling.reconnectNow()
            webrtc.ensureSignalingAndOffer()
        }

        // 7) Arranque: pedir cámara o iniciar y conectar
        val camPermGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (camPermGranted) {
            webrtc.initAndStart()
            orientationHelper.enable()
//            signaling.connect() // conectamos sólo cuando todo está listo
        } else {
            cameraPermsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.enable()
        webrtc.tryResumeCapture()
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
    }
}
