package io.github.jqssun.airplay.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// owns the car surface, the park consent and the AirPlayService binding;
// mirrored video goes to the car surface only while all three are present
class AirPlayCarSession : Session() {

    private val _service = MutableStateFlow<AirPlayService?>(null)
    val service = _service.asStateFlow()

    private val _parkConfirmed = MutableStateFlow(false)
    val parkConfirmed = _parkConfirmed.asStateFlow()

    private var carSurface: Surface? = null
    private var attached: Surface? = null
    private var bound = false

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
            _updateBinding()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            _detach()
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
                        svc?.mirroringActive?.distinctUntilChanged()?.filter { it }?.collect {
                            attached = null
                            _updateBinding()
                        }
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                _detach()
                carSurface = null
                _parkConfirmed.value = false
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
        return AirPlayCarScreen(carContext, this)
    }

    fun confirmParked() {
        _parkConfirmed.value = true
        _updateBinding()
    }

    fun stop() {
        _parkConfirmed.value = false
        _detach()
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
        val svc = _service.value ?: return
        val surface = carSurface ?: return
        if (!_parkConfirmed.value || attached === surface) return
        svc.setVideoSurface(surface, letterbox = true)
        attached = surface
    }

    private fun _detach() {
        val surface = attached ?: return
        _service.value?.clearVideoSurface(surface)
        attached = null
    }

    companion object {
        private const val TAG = "AirPlayCarSession"
    }
}
