package io.github.jqssun.airplay.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.airplay.screen.ScreenMirrorController
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// owns the car surface, the park consent, the chosen source and the AirPlayService binding;
// the car surface is handed to the chosen source only after park consent
class AirPlayCarSession : Session() {

    enum class Source { IPHONE, ANDROID }

    private val _service = MutableStateFlow<AirPlayService?>(null)
    val service = _service.asStateFlow()

    private val _parkConfirmed = MutableStateFlow(false)
    val parkConfirmed = _parkConfirmed.asStateFlow()

    private val _source = MutableStateFlow<Source?>(null)
    val source = _source.asStateFlow()

    private var carSurface: Surface? = null
    private var carWidth = 0
    private var carHeight = 0
    private var carDpi = 0
    private var attached: Surface? = null
    private var bound = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as? AirPlayService.LocalBinder)?.service
            _updateBinding()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            attached = null
            _service.value = null
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            _detach()
            carSurface = container.surface
            carWidth = container.width
            carHeight = container.height
            carDpi = container.dpi
            _updateBinding()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            _detach()
            ScreenMirrorController.setTarget(null)
            carSurface = null
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                _startServer()
                bound = carContext.bindService(
                    Intent(carContext, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE
                )
                // pipeline loses its display when the server restarts, so re-point it on every new mirror session
                lifecycleScope.launch {
                    _service.collect { svc ->
                        svc?.mirroringActive?.filter { it }?.collect {
                            attached = null
                            _updateBinding()
                        }
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mainHandler.removeCallbacks(attachScreenMirror)
                _detach()
                ScreenMirrorController.setTarget(null)
                carSurface = null
                _parkConfirmed.value = false
                _source.value = null
                if (bound) {
                    carContext.unbindService(connection)
                    bound = false
                }
                _service.value = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // ask again on every Android Auto connection
        _parkConfirmed.value = false
        _source.value = null
        return AirPlayCarScreen(carContext, this)
    }

    fun confirmParked() {
        _parkConfirmed.value = true
        _updateBinding()
    }

    fun selectSource(source: Source?) {
        _source.value = source
        _updateBinding()
    }

    fun stop() {
        _parkConfirmed.value = false
        _source.value = null
        _updateBinding()
    }

    private fun _startServer() {
        // starting a foreground service from the background can be refused (API 31+); the screen then asks to start it on the phone
        try {
            ContextCompat.startForegroundService(
                carContext,
                Intent(carContext, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER)
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not start AirPlay server from car", e)
        }
    }

    private fun _updateBinding() {
        mainHandler.removeCallbacks(attachScreenMirror)
        val source = _source.value.takeIf { _parkConfirmed.value }
        if (source != Source.IPHONE) _detach()
        if (source != Source.ANDROID) ScreenMirrorController.setTarget(null)
        when (source) {
            Source.IPHONE -> {
                val svc = _service.value ?: return
                val surface = carSurface ?: return
                if (attached === surface) return
                svc.setVideoSurface(surface, letterbox = true)
                attached = surface
            }
            // the AirPlay GL thread lets go of the surface asynchronously; give it a moment before the VirtualDisplay connects
            Source.ANDROID -> mainHandler.postDelayed(attachScreenMirror, SURFACE_HANDOVER_MS)
            null -> Unit
        }
    }

    private val attachScreenMirror = Runnable {
        if (_parkConfirmed.value && _source.value == Source.ANDROID) {
            ScreenMirrorController.setTarget(carSurface, carWidth, carHeight, carDpi)
        }
    }

    private fun _detach() {
        val surface = attached ?: return
        _service.value?.clearVideoSurface(surface)
        attached = null
    }

    companion object {
        private const val TAG = "AirPlayCarSession"
        private const val SURFACE_HANDOVER_MS = 300L
    }
}
