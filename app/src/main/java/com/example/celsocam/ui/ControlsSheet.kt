package com.example.celsocam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.celsocam.R
import com.example.celsocam.util.OrientationHelper
import com.example.celsocam.webrtc.WebRtcController
import com.google.android.material.bottomsheet.BottomSheetDialog

object ControlsSheet {

    fun show(
        activity: AppCompatActivity,
        webrtc: WebRtcController,
        orientationHelper: OrientationHelper,
        micPermissionLauncher: ActivityResultLauncher<String>
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_controls, null, false)

        // UI
        val spCamera = view.findViewById<Spinner>(R.id.spCamera)
        val swMic = view.findViewById<SwitchCompat>(R.id.swMic)
        val swFollow = view.findViewById<SwitchCompat>(R.id.swFollowOrientation)
        val spAspect = view.findViewById<Spinner>(R.id.spAspect)
        val spQuality = view.findViewById<Spinner>(R.id.spQuality)
        val seek = view.findViewById<SeekBar>(R.id.seekBitrate)
        val tvBitrate = view.findViewById<TextView>(R.id.tvBitrate)
        val btnApply = view.findViewById<Button>(R.id.btnApply)

        // Estado actual -> pendientes
        var pendingMic = webrtc.isMicEnabled
        var pendingFollow = orientationHelper.follow
        var pendingAspect = webrtc.currentAspect
        var pendingFormat = WebRtcController.Format(webrtc.currentWidth, webrtc.currentHeight, webrtc.currentFps)
        var pendingBitrate = webrtc.currentBitrateKbps
        var pendingCameraName: String? = webrtc.currentCameraName()

        var initializingQuality = true
        var dirty = false
        fun markDirty() { dirty = true; btnApply.isEnabled = true }

        // ---------- Cámara: listar TODAS y no aplicar hasta "Aplicar cambios" ----------
        val cams = webrtc.listCameras() // requiere listCameras() en WebRtcController
        val camLabels = cams.map { it.label }
        spCamera.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            camLabels
        )
        val currentCamIdx = cams.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        spCamera.setSelection(currentCamIdx)
        pendingCameraName = cams.getOrNull(currentCamIdx)?.name

        spCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val chosen = cams.getOrNull(pos)?.name
                if (chosen != null && chosen != pendingCameraName) {
                    pendingCameraName = chosen
                    markDirty() // solo marcamos pendiente; no cambiamos aún
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Micrófono ----------
        swMic.isChecked = pendingMic
        swMic.setOnCheckedChangeListener { _, checked ->
            if (checked != pendingMic) { pendingMic = checked; markDirty() }
        }

        // ---------- Seguir orientación ----------
        swFollow.isChecked = pendingFollow
        swFollow.setOnCheckedChangeListener { _, checked ->
            if (checked != pendingFollow) { pendingFollow = checked; markDirty() }
        }

        // ---------- Aspecto ----------
        val aspects = WebRtcController.Aspect.values().toList()
        spAspect.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, aspects.map { it.label }
        )
        spAspect.setSelection(aspects.indexOf(webrtc.currentAspect).coerceAtLeast(0))

        spAspect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val newAspect = aspects[pos]
                if (newAspect != pendingAspect) {
                    pendingAspect = newAspect
                    // recargar lista de calidades para este aspecto (solo UI, sin aplicar aún)
                    webrtc.loadQualityOptionsForAspect(pendingAspect) { listSorted ->
                        val labels = listSorted.map { "${it.w}x${it.h} @ ${it.fps}fps" }
                        initializingQuality = true
                        spQuality.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                        val idx = listSorted.indexOfFirst {
                            it.w == pendingFormat.w && it.h == pendingFormat.h && it.fps == pendingFormat.fps
                        }.takeIf { it >= 0 } ?: 0
                        spQuality.setSelection(idx)
                        pendingFormat = listSorted.getOrElse(idx) { pendingFormat }
                        initializingQuality = false
                    }
                    markDirty()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Cargar calidades iniciales (según aspecto actual) ----------
        webrtc.loadQualityOptionsForAspect(webrtc.currentAspect) { listSorted ->
            val labels = listSorted.map { "${it.w}x${it.h} @ ${it.fps}fps" }
            spQuality.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = listSorted.indexOfFirst {
                it.w == webrtc.currentWidth && it.h == webrtc.currentHeight && it.fps == webrtc.currentFps
            }.takeIf { it >= 0 } ?: 0
            spQuality.setSelection(idx)
            pendingFormat = listSorted.getOrElse(idx) { pendingFormat }
            initializingQuality = false
        }

        spQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (initializingQuality) return
                val list = webrtc.getFormatsForAspect(pendingAspect)
                if (list.isEmpty()) return
                val sorted = list.sortedWith(
                    compareByDescending<WebRtcController.Format> { it.w * it.h }
                        .thenByDescending { it.fps }
                )
                val chosen = sorted[pos.coerceIn(0, sorted.size - 1)]
                if (chosen != pendingFormat) { pendingFormat = chosen; markDirty() }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Bitrate ----------
        seek.max = 20000
        seek.min = 300
        seek.progress = pendingBitrate.coerceIn(seek.min, seek.max)
        tvBitrate.text = "Bitrate: ${pendingBitrate} kbps"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value.coerceIn(seek.min, seek.max)
                if (v != pendingBitrate) {
                    pendingBitrate = v
                    tvBitrate.text = "Bitrate: ${pendingBitrate} kbps"
                    if (fromUser) markDirty()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // ---------- Aplicar cambios (todo junto) ----------
        btnApply.isEnabled = false
        btnApply.setOnClickListener {
            if (!dirty) return@setOnClickListener

            // 1) Cambiar cámara si es distinta
            pendingCameraName?.let { deviceName ->
                if (deviceName != webrtc.currentCameraName()) {
                    webrtc.switchToDeviceName(deviceName)
                }
            }

            // 2) Micrófono (con permiso si es necesario)
            if (pendingMic != webrtc.isMicEnabled) {
                if (pendingMic) {
                    val granted = ContextCompat.checkSelfPermission(
                        activity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnClickListener
                    } else {
                        webrtc.setMicEnabled(true)
                    }
                } else {
                    webrtc.setMicEnabled(false)
                }
            }

            // 3) Calidad y bitrate
            if (pendingFormat.w != webrtc.currentWidth ||
                pendingFormat.h != webrtc.currentHeight ||
                pendingFormat.fps != webrtc.currentFps) {
                webrtc.applyVideoQuality(pendingFormat.w, pendingFormat.h, pendingFormat.fps, pendingBitrate)
            } else if (pendingBitrate != webrtc.currentBitrateKbps) {
                webrtc.setVideoBitrateKbps(pendingBitrate)
            }

            // 4) Aspecto
            if (pendingAspect != webrtc.currentAspect) {
                webrtc.setCurrentAspect(pendingAspect)
            }

            // 5) Seguir orientación
            if (pendingFollow != orientationHelper.follow) {
                orientationHelper.follow = pendingFollow
            }

            // 6) Renegociar
            webrtc.ensureSignalingAndOffer()

            // listo
            btnApply.isEnabled = false
            dirty = false
            Toast.makeText(activity, "Cambios aplicados", Toast.LENGTH_SHORT).show()
        }

        sheet.setContentView(view)
        sheet.show()
    }
}
