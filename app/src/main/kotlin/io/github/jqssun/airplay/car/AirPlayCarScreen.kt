package io.github.jqssun.airplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.car.AirPlayCarSession.Source
import io.github.jqssun.airplay.screen.ScreenMirrorController
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

// 1. no consent: park question, nothing routed to the car
// 2. consent, no source yet: iPhone (AirPlay) or this phone's own screen
// 3. iPhone but server not running: tell the user to open the app on the phone
// 4. otherwise: NavigationTemplate over the mirrored video, hint until the source delivers frames
class AirPlayCarScreen(carContext: CarContext, private val session: AirPlayCarSession) : Screen(carContext) {

    private var declined = false
    private var parked = false
    private var source: Source? = null
    private var serverState = AirPlayService.ServerState.STOPPED
    private var mirroring = false
    private var projecting = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            @OptIn(ExperimentalCoroutinesApi::class)
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    combine(
                        session.parkConfirmed,
                        session.source,
                        session.service.flatMapLatest { it?.serverState ?: flowOf(AirPlayService.ServerState.STOPPED) },
                        session.service.flatMapLatest { it?.mirroringActive ?: flowOf(false) },
                        ScreenMirrorController.projecting,
                    ) { p, src, s, m, pr ->
                        parked = p
                        source = src
                        serverState = s
                        mirroring = m
                        projecting = pr
                    }.collect { invalidate() }
                }
            }
        })
    }

    override fun onGetTemplate(): Template = when {
        !parked -> _parkQuestion()
        source == null -> _sourceQuestion()
        source == Source.IPHONE && serverState != AirPlayService.ServerState.RUNNING -> MessageTemplate.Builder(
            carContext.getString(R.string.car_open_app)
        )
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(_sourceAction())
            .addAction(_stopAction())
            .build()
        source == Source.IPHONE -> _mirrorTemplate(
            waiting = !mirroring, R.string.car_waiting_title, R.string.car_connect_hint
        )
        else -> _mirrorTemplate(
            waiting = !projecting, R.string.car_screen_waiting_title, R.string.car_screen_hint
        )
    }

    private fun _mirrorTemplate(waiting: Boolean, titleRes: Int, hintRes: Int): Template =
        NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(_sourceAction()).addAction(_stopAction()).build())
            .apply {
                if (waiting) {
                    setNavigationInfo(
                        MessageInfo.Builder(carContext.getString(titleRes))
                            .setText(carContext.getString(hintRes))
                            .build()
                    )
                }
            }
            .build()

    private fun _sourceQuestion(): Template {
        val iphone = Action.Builder()
            .setTitle(carContext.getString(R.string.car_source_iphone))
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { session.selectSource(Source.IPHONE) }
            .build()
        val android = Action.Builder()
            .setTitle(carContext.getString(R.string.car_source_android))
            .setOnClickListener { session.selectSource(Source.ANDROID) }
            .build()
        return MessageTemplate.Builder(carContext.getString(R.string.car_source_question))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(iphone)
            .addAction(android)
            .build()
    }

    private fun _parkQuestion(): Template {
        val message = carContext.getString(if (declined) R.string.car_declined else R.string.car_park_question)
        val yes = Action.Builder()
            .setTitle(carContext.getString(R.string.car_park_yes))
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener {
                declined = false
                session.confirmParked()
            }
            .build()
        val no = Action.Builder()
            .setTitle(carContext.getString(R.string.car_park_no))
            .setOnClickListener {
                declined = true
                invalidate()
            }
            .build()
        return MessageTemplate.Builder(message)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(yes)
            .addAction(no)
            .build()
    }

    // back to the source question; consent stays
    private fun _sourceAction() = Action.Builder()
        .setTitle(carContext.getString(R.string.car_source_change))
        .setOnClickListener { session.selectSource(null) }
        .build()

    // resets consent, so the park question comes back and video leaves the car display
    private fun _stopAction() = Action.Builder()
        .setTitle(carContext.getString(R.string.car_stop))
        .setOnClickListener { session.stop() }
        .build()
}
