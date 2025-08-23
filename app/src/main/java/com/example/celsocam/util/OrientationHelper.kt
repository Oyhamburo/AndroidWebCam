package com.example.celsocam.util

import android.content.Context
import android.content.res.Configuration
import android.view.OrientationEventListener

class OrientationHelper(
    private val context: Context,
    followDeviceOrientation: Boolean,
    private val onOrientationLabel: (String) -> Unit
) {
    var follow: Boolean = followDeviceOrientation
        set(value) {
            field = value
            if (field) {
                val isLandscape = context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                val label = if (isLandscape) "landscape" else "portrait"
                onOrientationLabel(label)
                lastLabel = label
            }
        }

    private var lastLabel: String? = null

    private var listener: OrientationEventListener? =
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                if (!follow) return
                val isLandscape = context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                val label = if (isLandscape) "landscape" else "portrait"
                if (label != lastLabel) {
                    onOrientationLabel(label)
                    lastLabel = label
                }
            }
        }

    fun enable() { listener?.enable() }
    fun disable() { listener?.disable() }
    fun shutdown() { disable(); listener = null }
}
