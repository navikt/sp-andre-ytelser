package no.nav.helse.sykepenger.andreytelser.rammeverk.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

private val ENGANGSSTIER_UTEN_LOGGING = setOf("/metrics", "/isalive", "/isready")

internal fun Application.configureCallId() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { callId -> callId.isNotEmpty() }
    }
}

// tjenestekall går bare til Team Logs, så stien med personPseudoId havner ikke i nav-logs
internal fun Application.configureCallLogging() {
    install(CallLogging) {
        disableDefaultColors()
        logger = LoggerFactory.getLogger("tjenestekall")
        level = Level.INFO
        callIdMdc("callId")
        filter { call -> call.request.path() !in ENGANGSSTIER_UTEN_LOGGING }
    }
}
