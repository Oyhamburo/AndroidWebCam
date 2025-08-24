package com.example.celsocam.webrtc

import android.content.Context
import android.widget.Toast
import com.example.celsocam.signaling.CamInfo
import com.example.celsocam.signaling.ConfigState
import com.example.celsocam.signaling.FormatCaps
import com.example.celsocam.signaling.RemoteIce
import com.example.celsocam.signaling.SignalingClient
import org.webrtc.*
import kotlin.math.abs

class WebRtcController(
    private val context: Context,
    private val localRenderer: SurfaceViewRenderer,
    private val signaling: SignalingClient
) {

    companion object {
        private const val STREAM_ID = "ARDAMS"
    }

    enum class Aspect(val label: String, val ratio: Double?) {
        AUTO_MAX("Auto (máxima)", null),
        R16_9("16:9", 16.0 / 9.0),
        R4_3("4:3", 4.0 / 3.0),
        R1_1("1:1", 1.0)
    }

    data class Format(val w: Int, val h: Int, val fps: Int)

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null

    private var cameraEnumerator: CameraEnumerator? = null
    private var capturer: VideoCapturer? = null
    private var selectedCameraName: String? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null

    var isMicEnabled: Boolean = false
        private set

    var currentWidth: Int = 1280
        private set
    var currentHeight: Int = 720
        private set
    var currentFps: Int = 30
        private set

    var currentBitrateKbps: Int = 6000
        private set

    var currentAspect: Aspect = Aspect.AUTO_MAX
        private set

    /** Public: responder a "request-caps" del server */
    fun sendCapsNow() = publishCaps()

    // ---------- Publicar capacidades ----------
    private fun publishCaps() {
        val e = cameraEnumerator ?: return

        // 1) Cámaras
        val cams = mutableListOf<CamInfo>()
        var backIdx = 0
        var frontIdx = 0
        for (name in e.deviceNames) {
            val facing = when {
                runCatching { e.isBackFacing(name) }.getOrDefault(false) -> "back"
                runCatching { e.isFrontFacing(name) }.getOrDefault(false) -> "front"
                else -> "other"
            }
            val label = when (facing) {
                "back"  -> "Trasera ${backIdx++} ($name)"
                "front" -> "Frontal ${frontIdx++} ($name)"
                else    -> "Otra ($name)"
            }
            cams += CamInfo(name = name, label = label, facing = facing)
        }

        // 2) Formatos por cámara (con lista realista de FPS)
        val formatsByName = mutableMapOf<String, List<FormatCaps>>()
        for (name in e.deviceNames) {
            val fmts = getSupportedFormats(name) // List<Format> con varios fps por resolución
            val listForCam: List<FormatCaps> = fmts
                .groupBy { it.w to it.h }
                .map { (wh, list) ->
                    val fpsList = list.map { it.fps }.distinct().sorted()
                    FormatCaps(w = wh.first, h = wh.second, fps = fpsList)
                }
                .sortedWith(
                    compareByDescending<FormatCaps> { it.w * it.h }
                        .thenByDescending { it.fps.maxOrNull() ?: 0 }
                )
            formatsByName[name] = listForCam
        }

        val supportedAspects = listOf("AUTO_MAX", "R16_9", "R4_3", "R1_1")

        signaling.sendCaps(
            cameras = cams,
            formatsByCameraName = formatsByName,
            supportedAspects = supportedAspects
        )
    }

    // ---------- Init ----------
    fun initAndStart() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        cameraEnumerator = if (Camera2Enumerator.isSupported(context))
            Camera2Enumerator(context) else Camera1Enumerator(false)

        // Selección por defecto
        val e = cameraEnumerator!!
        val names = e.deviceNames
        selectedCameraName = names.firstOrNull { runCatching { e.isBackFacing(it) }.getOrDefault(false) }
            ?: names.firstOrNull()
        requireNotNull(selectedCameraName) { "No se encontraron cámaras" }

        capturer = e.createCapturer(selectedCameraName!!, null)

        val best = pickBestFormat(selectedCameraName!!)
        currentWidth = best.w
        currentHeight = best.h
        currentFps = best.fps
        currentAspect = Aspect.AUTO_MAX

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        capturer!!.initialize(helper, context, videoSource!!.capturerObserver)
        runCatching { (capturer as? CameraVideoCapturer)?.startCapture(currentWidth, currentHeight, currentFps) }

        videoTrack = factory.createVideoTrack("VID", videoSource)

        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(false)
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        videoTrack!!.addSink(localRenderer)

        val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { signaling.sendIce(c.sdp, c.sdpMid, c.sdpMLineIndex) }
            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
        })

        // Sender de video (no eliminarlo más adelante)
        videoSender = peer?.addTrack(videoTrack, listOf(STREAM_ID))

        // Audio se crea al habilitarse
        audioSender = null
        audioTrack = null
        audioSource = null
        isMicEnabled = false

        publishCaps()
    }

    private fun aspectFromString(s: String?): Aspect {
        val v = s?.trim()?.uppercase() ?: return Aspect.AUTO_MAX
        return when (v) {
            "AUTO_MAX", "AUTO", "AUTO (MÁXIMA)", "AUTO (MAXIMA)" -> Aspect.AUTO_MAX
            "16:9", "R16_9", "16_9" -> Aspect.R16_9
            "4:3",  "R4_3",  "4_3"  -> Aspect.R4_3
            "1:1",  "R1_1",  "1_1"  -> Aspect.R1_1
            else -> Aspect.AUTO_MAX
        }
    }

    data class CameraDevice(
        val name: String,
        val label: String,
        val facing: String,
        val isSelected: Boolean
    )

    fun listCameras(): List<CameraDevice> {
        val e = cameraEnumerator ?: return emptyList()
        val names = e.deviceNames
        var backIdx = 0
        var frontIdx = 0
        return names.map { n ->
            val facing = when {
                runCatching { e.isBackFacing(n) }.getOrDefault(false) -> "back"
                runCatching { e.isFrontFacing(n) }.getOrDefault(false) -> "front"
                else -> "other"
            }
            val label = when (facing) {
                "back" -> "Trasera ${backIdx++} ($n)"
                "front" -> "Frontal ${frontIdx++} ($n)"
                else -> "Otra ($n)"
            }
            CameraDevice(name = n, label = label, facing = facing, isSelected = (n == selectedCameraName))
        }
    }

    fun currentCameraName(): String? = selectedCameraName

    fun switchToDeviceName(deviceName: String) {
        if (deviceName == selectedCameraName) return
        forceRecreateCapturer(deviceName)
        setAspectLabel(currentAspect)
    }

    // ---------- Señalización ----------
    fun createOffer() {
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        val setLocalObs = object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }
        peer?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val tuned = setVideoBitrateOnSdp(sdp.description, currentBitrateKbps, "H264")
                val local = SessionDescription(SessionDescription.Type.OFFER, tuned)
                peer?.setLocalDescription(setLocalObs, local)
                signaling.sendOffer(local.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, mc)
    }

    fun ensureSignalingAndOffer() {
        signaling.ping()
        createOffer()
    }

    fun setRemoteAnswer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peer?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, desc)
    }

    fun addRemoteIce(c: RemoteIce) {
        peer?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate))
    }

    // ---------- /config ----------
    fun applyConfig(cfg: ConfigState) {
        // 1) Cámara
        if (!cfg.cameraName.isNullOrEmpty()) {
            if (cfg.cameraName != currentCameraName()) {
                switchToDeviceName(cfg.cameraName!!)
            }
        } else {
            switchTo(cfg.camera)
        }

        // 2) Video (res/fps/bitrate)
        val resChanged = (cfg.width != currentWidth || cfg.height != currentHeight || cfg.fps != currentFps)
        applyVideoQuality(cfg.width, cfg.height, cfg.fps, cfg.bitrateKbps)

        // 3) Mic
        setMicEnabled(cfg.micEnabled)

        // 4) Aspecto: si hubo cambio de resolución, solo actualizamos etiqueta
        if (resChanged) setAspectLabel(aspectFromString(cfg.aspect))
        else setCurrentAspect(aspectFromString(cfg.aspect))

        // 5) Renegociar
        ensureSignalingAndOffer()
    }

    // ---------- Audio ----------
    fun setMicEnabled(enabled: Boolean) {
        if (enabled == isMicEnabled) return
        if (enabled) {
            if (audioTrack == null) {
                audioSource = factory.createAudioSource(MediaConstraints())
                audioTrack = factory.createAudioTrack("AUD", audioSource)
                audioSender = peer?.addTrack(audioTrack, listOf(STREAM_ID))
            }
            audioTrack?.setEnabled(true)
        } else {
            audioTrack?.setEnabled(false)
        }
        isMicEnabled = enabled
    }

    // ---------- Video ----------
    fun applyVideoQuality(w: Int, h: Int, fps: Int, bitrateKbps: Int) {
        // Cambio robusto: SIEMPRE reiniciamos la captura con los nuevos parámetros
        val ok = restartCapture(w, h, fps)
        if (!ok) {
            Toast.makeText(context, "No se pudo aplicar ${w}x$h@$fps", Toast.LENGTH_LONG).show()
            return
        }
        currentWidth = w; currentHeight = h; currentFps = fps
        setVideoBitrateKbps(bitrateKbps)
    }

    private fun restartCapture(w: Int, h: Int, fps: Int): Boolean {
        val cam = capturer as? CameraVideoCapturer ?: return false
        val stopped = runCatching { cam.stopCapture() }.isSuccess
        val started = runCatching { cam.startCapture(w, h, fps) }.isSuccess
        // Si no arrancó, intentamos una segunda vez pequeña con el orden inverso (por si quedó en transición)
        return if (started) true
        else {
            runCatching { cam.stopCapture() }
            runCatching { Thread.sleep(60) }
            runCatching { cam.startCapture(w, h, fps) }.isSuccess
        }
    }

    fun setVideoBitrateKbps(kbps: Int) {
        val desired = kbps.coerceIn(300, 20000)
        currentBitrateKbps = desired
        val sender = videoSender ?: return
        val params = runCatching { sender.parameters }.getOrNull() ?: return
        if (params.encodings.isNotEmpty()) {
            params.encodings[0].maxBitrateBps = desired * 1000
            runCatching { sender.parameters = params }
        }
    }

    // ---------- Cámara ----------
    fun switchCamera() {
        val cam = capturer as? CameraVideoCapturer ?: run {
            val target = if (isFrontSelected()) "back" else "front"
            switchTo(target)
            return
        }
        cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                val e = cameraEnumerator!!
                selectedCameraName =
                    if (isFront) e.deviceNames.firstOrNull { runCatching { e.isFrontFacing(it) }.getOrDefault(false) }
                    else e.deviceNames.firstOrNull { runCatching { e.isBackFacing(it) }.getOrDefault(false) }
                        ?: e.deviceNames.firstOrNull()
                localRenderer.setMirror(isFront)
                setAspectLabel(currentAspect)
            }
            override fun onCameraSwitchError(errorDescription: String?) {
                val target = if (isFrontSelected()) "front" else "back"
                forceRecreateCapturer(pickCamera(target))
                setAspectLabel(currentAspect)
            }
        })
    }

    fun setAspectLabel(a: Aspect) { currentAspect = a }

    fun setCurrentAspect(a: Aspect) {
        currentAspect = a
        val best = pickBestForAspect(a)
        if (best.w != currentWidth || best.h != currentHeight || best.fps != currentFps) {
            applyVideoQuality(best.w, best.h, best.fps, currentBitrateKbps)
        }
    }

    fun getFormatsForAspect(aspect: Aspect): List<Format> {
        val cam = selectedCameraName ?: return emptyList()
        val all = getSupportedFormats(cam)
        val filtered = if (aspect.ratio == null) all else {
            val tol = 0.02
            val target = aspect.ratio!!
            all.filter {
                val r = it.w.toDouble() / it.h.toDouble()
                abs(r - target) < tol
            }
        }
        return filtered.sortedWith(compareByDescending<Format> { it.w * it.h }.thenByDescending { it.fps })
    }

    fun loadQualityOptionsForAspect(aspect: Aspect, onReady: (List<Format>) -> Unit) {
        onReady(getFormatsForAspect(aspect))
    }

    private fun isFrontSelected(): Boolean {
        val e = cameraEnumerator ?: return false
        val name = selectedCameraName ?: return false
        return runCatching { e.isFrontFacing(name) }.getOrDefault(false)
    }

    private fun pickCamera(which: String): String {
        val e = cameraEnumerator!!
        val names = e.deviceNames
        return if (which == "front") {
            names.firstOrNull { runCatching { e.isFrontFacing(it) }.getOrDefault(false) } ?: names.first()
        } else {
            names.firstOrNull { runCatching { e.isBackFacing(it) }.getOrDefault(false) } ?: names.first()
        }
    }

    private fun switchTo(which: String) {
        val targetName = pickCamera(which)
        if (targetName == selectedCameraName) return
        forceRecreateCapturer(targetName)
        setAspectLabel(currentAspect)
    }

    private fun forceRecreateCapturer(targetName: String) {
        // Parar capturer actual
        runCatching { (capturer as? CameraVideoCapturer)?.stopCapture() }
        videoTrack?.removeSink(localRenderer)

        // Crear capturer NUEVO con ese deviceName exacto
        val e = cameraEnumerator!!
        capturer = e.createCapturer(targetName, null)
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        // Re-crear source
        videoSource?.dispose()
        videoSource = factory.createVideoSource(false)
        capturer!!.initialize(helper, context, videoSource!!.capturerObserver)
        runCatching { (capturer as? CameraVideoCapturer)?.startCapture(currentWidth, currentHeight, currentFps) }

        // Re-crear track y REEMPLAZAR en el sender existente
        videoTrack?.dispose()
        videoTrack = factory.createVideoTrack("VID", videoSource)
        videoTrack!!.addSink(localRenderer)

        if (videoSender == null) {
            videoSender = peer?.addTrack(videoTrack, listOf(STREAM_ID))
        } else {
            runCatching { videoSender?.setTrack(videoTrack, true) }
        }

        selectedCameraName = targetName
        publishCaps()
    }

    // Devuelve múltiples FPS por resolución (Camera2)
    private fun getSupportedFormats(cameraName: String): List<Format> {
        return try {
            when (val e = cameraEnumerator) {
                is Camera2Enumerator -> {
                    val list = e.getSupportedFormats(cameraName) ?: emptyList()
                    val typical = listOf(24, 25, 30, 48, 50, 60, 120)
                    val out = ArrayList<Format>()
                    for (f in list) {
                        val minFps = f.framerate.min / 1000
                        val maxFps = f.framerate.max / 1000
                        val fpsCandidates = typical.filter { it in minFps..maxFps }.ifEmpty { listOf(maxFps) }
                        fpsCandidates.forEach { fr ->
                            out += Format(f.width, f.height, fr)
                        }
                    }
                    out
                }
                is Camera1Enumerator -> {
                    // Camera1 no expone rangos por formato; devolvemos lo clásico.
                    listOf(
                        Format(1920, 1080, 30),
                        Format(1280, 720, 30),
                        Format(640, 480, 30)
                    )
                }
                else -> listOf(Format(1280, 720, 30))
            }
        } catch (_: Exception) {
            listOf(Format(1280, 720, 30))
        }
    }

    private fun pickBestFormat(cameraName: String): Format {
        val fmts = getSupportedFormats(cameraName)
        return fmts.maxWith(compareBy<Format> { it.w * it.h }.thenBy { it.fps }) ?: Format(1280, 720, 30)
    }

    private fun pickBestForAspect(aspect: Aspect): Format {
        val cam = selectedCameraName
        val list = if (cam != null) getFormatsForAspect(aspect) else emptyList()
        return list.firstOrNull() ?: Format(currentWidth, currentHeight, currentFps)
    }

    private fun setVideoBitrateOnSdp(sdp: String, maxKbps: Int, codec: String = "H264"): String {
        fun attempt(targetCodec: String, base: String): String? {
            val lines = base.lines().toMutableList()
            val rtpmapIdx = lines.indexOfFirst { it.startsWith("a=rtpmap:") && it.contains(targetCodec, true) }
            if (rtpmapIdx == -1) return null
            val pt = lines[rtpmapIdx].substringAfter("a=rtpmap:").substringBefore(" ").trim()
            val fmtpPrefix = "a=fmtp:$pt "
            val params = "x-google-start-bitrate=$maxKbps;x-google-max-bitrate=$maxKbps;x-google-min-bitrate=300"
            val fmtpIdx = lines.indexOfFirst { it.startsWith(fmtpPrefix) }
            if (fmtpIdx >= 0) {
                val baseLine = lines[fmtpIdx]
                lines[fmtpIdx] = if (baseLine.contains("x-google-"))
                    baseLine.replace(Regex("x-google-[^;\\r\\n]*"), params)
                else baseLine + ";$params"
            } else {
                lines.add(rtpmapIdx + 1, fmtpPrefix + params)
            }
            return lines.joinToString("\r\n")
        }
        return attempt(codec, sdp) ?: attempt("VP8", sdp) ?: sdp
    }

    // ---------- Ciclo de vida ----------
    fun tryPauseCapture() { runCatching { (capturer as? CameraVideoCapturer)?.stopCapture() } }
    fun tryResumeCapture() { runCatching { (capturer as? CameraVideoCapturer)?.startCapture(currentWidth, currentHeight, currentFps) } }

    fun releaseAll() {
        runCatching { (capturer as? CameraVideoCapturer)?.stopCapture() }
        videoTrack?.dispose(); videoSource?.dispose()
        audioTrack?.dispose(); audioSource?.dispose()
        peer?.close(); peer = null
        localRenderer.release()
        factory.dispose()
        eglBase.release()
    }
}
