package no.nav.helse.spade.behandlinger

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.application.call
import io.ktor.routing.Route
import io.ktor.routing.get
import no.nav.helse.map
import no.nav.helse.respond

fun Route.behandlinger(service: BehandlingerService) {
    get("api/behandlinger/{aktørId}") {
        service.getBehandlingerForAktør(call.parameters["aktørId"]!!)
                .map { BehandlingerResponse(it) }
                .respond(call)
    }

    get("api/behandlinger") {
        service.getAvailableActors().map { AktørerResponse(it) }
                .respond(call)
    }
}

data class BehandlingerResponse(val behandlinger: List<JsonNode>)
data class AktørerResponse(val aktører: List<String>)
