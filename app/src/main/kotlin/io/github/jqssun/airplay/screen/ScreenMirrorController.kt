package io.github.jqssun.airplay.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// mirrors the phone's own screen into the car surface through a VirtualDisplay;
// the system scales the mirror itself, so there is no encode/decode step. Main thread only.
object ScreenMirrorController {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _projecting = MutableStateFlow(false)
    val projecting = _projecting.asStateFlow()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var target: Surface? = null
    private var targetW = 0
    private var targetH = 0
    private var targetDpi = 0

    fun startProjection(newProjection: MediaProjection) {
        stopProjection()
        projection = newProjection
        // API 34+ refuses createVirtualDisplay without a registered callback
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === newProjection) _release()
            }
        }, mainHandler)
        _projecting.value = true
        _update()
    }

    fun stopProjection() {
        val current = projection ?: return
        _release()
        current.stop()
    }

    // null blanks the car screen but keeps the capture alive
    fun setTarget(surface: Surface?, width: Int = 0, height: Int = 0, dpi: Int = 0) {
        target = surface
        targetW = width
        targetH = height
        targetDpi = dpi
        _update()
    }

    private fun _release() {
        virtualDisplay?.release()
        virtualDisplay = null
        projection = null
        _projecting.value = false
    }

    // one projection token may create only one VirtualDisplay (API 34+), so it is created once and then re-targeted
    private fun _update() {
        val current = projection ?: return
        val surface = target?.takeIf { it.isValid && targetW > 0 && targetH > 0 }
        val display = virtualDisplay
        if (display == null) {
            if (surface == null) return
            virtualDisplay = current.createVirtualDisplay(
                "CarScreenMirror",
                targetW,
                targetH,
                targetDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler,
            )
        } else {
            if (surface != null) display.resize(targetW, targetH, targetDpi)
            display.surface = surface
        }
    }
}
