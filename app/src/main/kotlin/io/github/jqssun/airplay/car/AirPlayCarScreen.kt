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
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

// 1. no consent: park question, video not routed to the car
// 2. consent but server not running: tell the user to open the app on the phone
// 3. consent and server running: NavigationTemplate over the mirrored video, hint until iPhone connects
class AirPlayCarScreen(carContext: CarContext, private val session: AirPlayCarSession) : Screen(carContext) {

    private var declined = false
    private var parked = false
    private var serverState = AirPlayService.ServerState.STOPPED
    private var mirroring = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            @OptIn(ExperimentalCoroutinesApi::class)
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    combine(
                        session.parkConfirmed,
                        session.service.flatMapLatest { it?.serverState ?: flowOf(AirPlayService.ServerState.STOPPED) },
                        session.service.flatMapLatest { it?.mirroringActive ?: flowOf(false) },
                    ) { p, s, m -> Triple(p, s, m) }.collect { (p, s, m) ->
                        parked = p
                        serverState = s
                        mirroring = m
                        invalidate()
                    }
                }
            }
        })
    }

    override fun onGetTemplate(): Template = when {
        !parked -> _parkQuestion()
        serverState != AirPlayService.ServerState.RUNNING -> MessageTemplate.Builder(
            carContext.getString(R.string.car_open_app)
        )
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(_stopAction())
            .build()
        else -> NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(_stopAction()).build())
            .apply {
                if (!mirroring) {
                    setNavigationInfo(
                        MessageInfo.Builder(carContext.getString(R.string.car_waiting_title))
                            .setText(carContext.getString(R.string.car_connect_hint))
                            .build()
                    )
                }
            }
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

    // resets consent, so the park question comes back and video leaves the car display
    private fun _stopAction() = Action.Builder()
        .setTitle(carContext.getString(R.string.car_stop))
        .setOnClickListener { session.stop() }
        .build()
}
