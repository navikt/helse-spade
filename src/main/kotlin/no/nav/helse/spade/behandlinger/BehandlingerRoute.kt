package no.nav.helse.spade.behandlinger

import com.fasterxml.jackson.databind.*
import io.ktor.application.*
import io.ktor.routing.*
import no.nav.helse.*

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
