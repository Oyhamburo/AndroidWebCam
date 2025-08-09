package com.example.celsocam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // Cambiá por la IP de tu PC
    private val WS_URL: String = "ws://192.168.0.59:8080"

    // --- WebRTC core
    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null

    // --- Media
    private var capturer: VideoCapturer? = null
    private var cameraEnumerator: CameraEnumerator? = null
    private var selectedCameraName: String? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null

    // --- UI
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var btnControls: Button

    // --- Señalización
    private var ws: WebSocket? = null
    private lateinit var httpClient: OkHttpClient

    // --- Permisos
    private lateinit var cameraPermsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var micPermLauncher: ActivityResultLauncher<String>
    private val CAMERA_PERMS: Array<String> = arrayOf(Manifest.permission.CAMERA)

    // --- Estado actual (aplicado)
    private var isMicEnabled = false
    private var currentWidth = 1280
    private var currentHeight = 720
    private var currentFps = 30
    private var currentBitrateKbps = 6000

    // relación de aspecto aplicada actualmente
    private var currentAspect: Aspect = Aspect.AUTO_MAX

    // sheet refs (para actualizar UI)
    private var controlsSheet: BottomSheetDialog? = null
    private var controlsMicSwitch: SwitchCompat? = null
    private var qualitySpinner: Spinner? = null
    private var aspectSpinner: Spinner? = null
    private var bitrateSeek: SeekBar? = null
    private var bitrateLabel: TextView? = null
    private var btnApply: Button? = null

    // relaciones de aspecto
    private enum class Aspect(val label: String, val ratio: Double?) {
        AUTO_MAX("Auto (máxima)", null),
        R16_9("16:9", 16.0/9.0),
        R4_3("4:3", 4.0/3.0),
        R1_1("1:1", 1.0)
    }

    private data class Format(val w: Int, val h: Int, val fps: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        localView = findViewById(R.id.localView)
        btnControls = findViewById(R.id.btnControls)

        httpClient = OkHttpClient()

        cameraPermsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val camGranted = result[Manifest.permission.CAMERA] == true
            if (camGranted) {
                initWebRTC()
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
                setMicEnabled(true)
                controlsMicSwitch?.isChecked = true
                ensureSignalingAndOffer()
            } else {
                val showMic = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                if (!showMic) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    Toast.makeText(this, "Habilitá Micrófono en Ajustes", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Permiso de Micrófono denegado", Toast.LENGTH_SHORT).show()
                }
                setMicEnabled(false)
                controlsMicSwitch?.isChecked = false
            }
        }

        btnControls.setOnClickListener { openControlsSheet() }

        val camPermGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (camPermGranted) initWebRTC() else cameraPermsLauncher.launch(CAMERA_PERMS)
    }

    // ---------- BottomSheet de controles con "Aplicar cambios" ----------
    private fun openControlsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_controls, null, false)

        val swMic = view.findViewById<SwitchCompat>(R.id.swMic)
        val btnSwitchCam = view.findViewById<Button>(R.id.btnSwitchCam)
        val spAspect = view.findViewById<Spinner>(R.id.spAspect)
        val spQuality = view.findViewById<Spinner>(R.id.spQuality)
        val seek = view.findViewById<SeekBar>(R.id.seekBitrate)
        val tvBitrate = view.findViewById<TextView>(R.id.tvBitrate)
        val btnApplyChanges = view.findViewById<Button>(R.id.btnApply)

        // refs
        controlsSheet = sheet
        controlsMicSwitch = swMic
        qualitySpinner = spQuality
        aspectSpinner = spAspect
        bitrateSeek = seek
        bitrateLabel = tvBitrate
        btnApply = btnApplyChanges

        // ---- Estados PENDIENTES (no aplicados aún) ----
        var pendingMic: Boolean = isMicEnabled
        var pendingFormat = Format(currentWidth, currentHeight, currentFps)
        var pendingBitrate = currentBitrateKbps
        var pendingAspect: Aspect = currentAspect
        var dirty = false

        fun markDirty() {
            dirty = true
            btnApplyChanges.isEnabled = true
        }

        // Mic (diferido)
        swMic.isChecked = pendingMic
        swMic.setOnCheckedChangeListener { _, checked ->
            if (checked != pendingMic) {
                pendingMic = checked
                markDirty()
            }
        }

        // Cambiar cámara (inmediato por practicidad)
        btnSwitchCam.setOnClickListener { switchCamera() }

        // Aspect ratios
        val aspects = Aspect.values().toList()
        spAspect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, aspects.map { it.label }
        )
        spAspect.setSelection(aspects.indexOf(currentAspect).coerceAtLeast(0))

        var initializingQuality = true

        spAspect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                pendingAspect = aspects[pos]
                // llenar calidades de este aspecto
                loadQualityOptionsForAspect(pendingAspect) { listSorted ->
                    // armar labels
                    val labels = listSorted.map { "${it.w}x${it.h} @ ${it.fps}fps" }
                    initializingQuality = true
                    spQuality.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        labels
                    )
                    // seleccionar la actual si está en la lista; si no, la mejor (0)
                    val idx = listSorted.indexOfFirst {
                        it.w == currentWidth && it.h == currentHeight && it.fps == currentFps
                    }.takeIf { it >= 0 } ?: 0
                    spQuality.setSelection(idx)
                    pendingFormat = listSorted[idx]
                    initializingQuality = false

                    if (pendingAspect != currentAspect) markDirty()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Cargar lista inicial según aspecto ACTUAL
        loadQualityOptionsForAspect(currentAspect) { listSorted ->
            val labels = listSorted.map { "${it.w}x${it.h} @ ${it.fps}fps" }
            spQuality.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, labels
            )
            val idx = listSorted.indexOfFirst {
                it.w == currentWidth && it.h == currentHeight && it.fps == currentFps
            }.takeIf { it >= 0 } ?: 0
            spQuality.setSelection(idx)
            pendingFormat = listSorted[idx]
            initializingQuality = false
        }

        spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: android.view.View?, position: Int, id: Long) {
                if (initializingQuality) return
                val cam = selectedCameraName ?: return
                val list = filterFormatsByAspect(getSupportedFormats(cam), pendingAspect)
                if (list.isEmpty()) return
                val sorted = list.sortedWith(compareByDescending<Format> { it.w * it.h }.thenByDescending { it.fps })
                val chosen = sorted[position.coerceIn(0, sorted.size - 1)]
                if (chosen != pendingFormat) {
                    pendingFormat = chosen
                    markDirty()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Bitrate (diferido)
        seek.max = 10000
        seek.min = 300
        seek.progress = pendingBitrate
        tvBitrate.text = "Bitrate: ${pendingBitrate} kbps"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                pendingBitrate = value.coerceIn(300, 10000)
                tvBitrate.text = "Bitrate: ${pendingBitrate} kbps"
                if (fromUser) markDirty()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Aplicar cambios: ejecuta pendientes y re-chequea conexión
        btnApplyChanges.isEnabled = false
        btnApplyChanges.setOnClickListener {
            if (!dirty) return@setOnClickListener

            // 1) Mic
            if (pendingMic != isMicEnabled) {
                if (pendingMic) {
                    val granted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnClickListener
                    } else {
                        setMicEnabled(true)
                    }
                } else {
                    setMicEnabled(false)
                }
            }

            // 2) Formato (w/h/fps) y/o 3) Bitrate
            if (pendingFormat.w != currentWidth || pendingFormat.h != currentHeight || pendingFormat.fps != currentFps) {
                applyVideoQuality(pendingFormat.w, pendingFormat.h, pendingFormat.fps, pendingBitrate)
            } else if (pendingBitrate != currentBitrateKbps) {
                setVideoBitrateKbps(pendingBitrate)
            }

            // 4) Persistir aspecto actual
            currentAspect = pendingAspect

            // 5) Señalización + offer
            ensureSignalingAndOffer()

            dirty = false
            btnApplyChanges.isEnabled = false
            Toast.makeText(this, "Cambios aplicados", Toast.LENGTH_SHORT).show()
            // Opcional: cerrar el sheet
            // controlsSheet?.dismiss()
        }

        sheet.setContentView(view)
        sheet.show()
    }

    /** Devuelve por callback la lista de formatos del aspecto, ordenada de mejor a peor. */
    private fun loadQualityOptionsForAspect(aspect: Aspect, onReady: (List<Format>) -> Unit) {
        val camName = selectedCameraName ?: return
        val fmts = filterFormatsByAspect(getSupportedFormats(camName), aspect)
        if (fmts.isEmpty()) {
            onReady(emptyList())
            return
        }
        val sorted = fmts.sortedWith(compareByDescending<Format> { it.w * it.h }.thenByDescending { it.fps })
        onReady(sorted)
    }

    // ---------- Audio (aplicado) ----------
    private fun setMicEnabled(enabled: Boolean) {
        if (enabled) {
            if (audioTrack == null) {
                val audioConstraints = MediaConstraints()
                audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
            }
            audioTrack?.setEnabled(true)
            if (audioSender == null) {
                val streamId = "ARDAMS"
                audioSender = peer?.addTrack(audioTrack, listOf(streamId))
            }
            isMicEnabled = true
        } else {
            audioTrack?.setEnabled(false)
            isMicEnabled = false
            // Si querés cortar tráfico completamente:
            // audioSender?.let { peer?.removeTrack(it); audioSender = null }
        }
    }

    // ---------- Video (aplicado) ----------
    private fun applyVideoQuality(w: Int, h: Int, fps: Int, bitrateKbps: Int) {
        val cam = capturer
        if (cam is CameraVideoCapturer) {
            // intento “en caliente”
            try {
                cam.changeCaptureFormat(w, h, fps)
            } catch (_: Exception) {
                // fallback a stop/start
                try { cam.stopCapture() } catch (_: Exception) {}
                try { cam.startCapture(w, h, fps) } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo aplicar ${w}x$h@$fps", Toast.LENGTH_LONG).show()
                    return
                }
            }
        } else {
            // capturer genérico
            try { capturer?.stopCapture() } catch (_: Exception) {}
            try { capturer?.startCapture(w, h, fps) } catch (e: Exception) {
                Toast.makeText(this, "No se pudo aplicar ${w}x$h@$fps", Toast.LENGTH_LONG).show()
                return
            }
        }

        // actualizar estado actual
        currentWidth = w
        currentHeight = h
        currentFps = fps

        // ajustar preview para reflejar cambios de aspecto
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        localView.requestLayout()

        // bitrate
        setVideoBitrateKbps(bitrateKbps)
    }

    private fun setVideoBitrateKbps(kbps: Int) {
        currentBitrateKbps = kbps.coerceIn(300, 10000)
        val sender = videoSender ?: return
        val params = sender.parameters
        val enc = params.encodings
        if (enc.isNotEmpty()) {
            enc[0].maxBitrateBps = (currentBitrateKbps * 1000)
            try { sender.parameters = params } catch (_: Exception) {}
        }
    }

    // Señalización: reconectar si hace falta y forzar offer
    private fun ensureSignalingAndOffer() {
        if (ws == null) {
            connectSignaling()
        } else {
            wsSend(mapOf("type" to "ping"))
            createOffer()
        }
    }

    // =========================
    // Inicialización WebRTC
    // =========================
    private fun initWebRTC() {
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(this).setEnableInternalTracer(false).createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Cámara TRASERA por defecto + mejor formato disponible
        cameraEnumerator = if (Camera2Enumerator.isSupported(this))
            Camera2Enumerator(this) else Camera1Enumerator(false)
        selectedCameraName = pickBackCamera(cameraEnumerator!!)
        capturer = cameraEnumerator!!.createCapturer(selectedCameraName!!, null)

        val best = pickBestFormat(selectedCameraName!!)
        currentWidth = best.w; currentHeight = best.h; currentFps = best.fps
        currentAspect = Aspect.AUTO_MAX

        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        capturer!!.initialize(surfaceHelper, this, videoSource!!.capturerObserver)
        try { capturer!!.startCapture(currentWidth, currentHeight, currentFps) } catch (_: Exception) {}
        videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)

        // Preview local
        localView.init(eglBase.eglBaseContext, null)
        localView.setMirror(false) // trasera
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        videoTrack!!.addSink(localView)

        // Peer
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(
            rtcConfig,
            PcObserver(onIce = { c ->
                wsSend(mapOf(
                    "type" to "ice",
                    "candidate" to c.sdp,
                    "sdpMid" to c.sdpMid,
                    "sdpMLineIndex" to c.sdpMLineIndex
                ))
            })
        )

        val streamId = "ARDAMS"
        videoSender = peer?.addTrack(videoTrack, listOf(streamId))

        connectSignaling()
    }

    // =========================
    // Señalización
    // =========================
    private fun connectSignaling() {
        val req = Request.Builder().url(WS_URL).build()
        ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsSend(mapOf("role" to "android"))
                createOffer()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "browser-ready" -> createOffer()
                    "answer" -> {
                        val sdp = SessionDescription(SessionDescription.Type.ANSWER, msg.optString("sdp"))
                        peer?.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {}
                            override fun onSetFailure(p0: String?) {}
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, sdp)
                    }
                    "ice" -> {
                        val c = IceCandidate(
                            msg.optString("sdpMid"),
                            msg.optInt("sdpMLineIndex"),
                            msg.optString("candidate")
                        )
                        peer?.addIceCandidate(c)
                    }
                }
            }
        })
    }

    private fun wsSend(map: Map<String, Any?>) {
        ws?.send(JSONObject(map).toString())
    }

    private fun createOffer() {
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        val setLocalObserver = object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }
        val createOfferObserver = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer?.setLocalDescription(setLocalObserver, sdp)
                wsSend(mapOf("type" to "offer", "sdp" to sdp.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }
        peer?.createOffer(createOfferObserver, offerConstraints)
    }

    // =========================
    // Cámara helpers
    // =========================
    private fun pickBackCamera(enumerator: CameraEnumerator): String {
        val names = enumerator.deviceNames
        val back = names.firstOrNull { enumerator.isBackFacing(it) }
        return back ?: names.first()
    }

    private fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                localView.setMirror(isFrontCamera)
                val names = cameraEnumerator?.deviceNames ?: return
                selectedCameraName = if (isFrontCamera)
                    names.firstOrNull { cameraEnumerator!!.isFrontFacing(it) }
                else
                    names.firstOrNull { cameraEnumerator!!.isBackFacing(it) }
                Toast.makeText(this@MainActivity,
                    if (isFrontCamera) "Cámara frontal" else "Cámara trasera",
                    Toast.LENGTH_SHORT).show()
            }
            override fun onCameraSwitchError(errorDescription: String) {
                Toast.makeText(this@MainActivity, "Error: $errorDescription", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun getSupportedFormats(cameraName: String): List<Format> {
        return try {
            when (val e = cameraEnumerator) {
                is Camera2Enumerator -> {
                    val list = e.getSupportedFormats(cameraName) ?: emptyList()
                    list.map {
                        val fps = it.framerate.max / 1000
                        Format(it.width, it.height, fps)
                    }
                }
                is Camera1Enumerator -> {
                    listOf(1920 to 1080, 1280 to 720, 640 to 480)
                        .map { Format(it.first, it.second, 30) }
                }
                else -> listOf(Format(1280,720,30))
            }
        } catch (_: Exception) {
            listOf(Format(1280,720,30))
        }
    }

    private fun pickBestFormat(cameraName: String): Format {
        val fmts = getSupportedFormats(cameraName)
        return fmts.maxWith(compareBy<Format> { it.w * it.h }.thenBy { it.fps }) ?: Format(1280,720,30)
    }

    private fun filterFormatsByAspect(fmts: List<Format>, aspect: Aspect): List<Format> {
        if (aspect.ratio == null) return fmts
        val target = aspect.ratio
        val tol = 0.02
        return fmts.filter {
            val r = it.w.toDouble()/it.h.toDouble()
            abs(r - target) < tol
        }
    }

    // =========================
    // Ciclo de vida
    // =========================
    override fun onPause() {
        super.onPause()
        try { capturer?.stopCapture() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted && videoSource != null && capturer != null) {
            try { capturer?.startCapture(currentWidth, currentHeight, currentFps) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        videoTrack?.dispose(); videoSource?.dispose()
        audioTrack?.dispose(); audioSource?.dispose()
        peer?.close(); peer = null
        ws?.close(1000, null); ws = null
        localView.release()
        factory.dispose()
        eglBase.release()
    }
}

/** Observer separado */
private class PcObserver(
    private val onIce: (IceCandidate) -> Unit = {},
) : PeerConnection.Observer {
    override fun onIceCandidate(c: IceCandidate) { onIce(c) }
    override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dc: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
}
