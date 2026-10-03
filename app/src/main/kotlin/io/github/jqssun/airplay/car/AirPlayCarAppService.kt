package io.github.jqssun.airplay.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class AirPlayCarAppService : CarAppService() {
    // sideloaded personal build: accept any Android Auto host
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AirPlayCarSession()
}
