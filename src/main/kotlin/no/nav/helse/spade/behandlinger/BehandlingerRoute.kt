package no.nav.helse.spade.behandlinger

import arrow.core.Either.Left
import arrow.core.Either.Right
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.application.call
import io.ktor.routing.Route
import io.ktor.routing.get
import no.nav.helse.respond
import no.nav.helse.respondFeil
import no.nav.helse.spade.isValidFødselsnummer
import no.nav.helse.toHttpFeil

fun Route.behandlinger(service: BehandlingerService, aktørregisterClient: AktørregisterClient) {
   get("api/behandlinger/{personId}") {
      val idParam = call.parameters["personId"]!!

      val aktørId = if (isValidFødselsnummer(idParam)) {
         when (val aktørId = aktørregisterClient.hentAktørId(idParam)) {
            is Right -> {
               aktørId.b
            }
            is Left -> {
               return@get call.respondFeil(aktørId.a.toHttpFeil())
            }
         }
      } else idParam

      service.getBehandlingerForAktør(aktørId)
         .map { BehandlingerResponse(it) }
         .respond(call)
   }

   get("api/soknader/{søknadId}") {
      service.getBehandlingerForSøknad(call.parameters["søknadId"]!!).map { BehandlingerResponse(it) }
         .respond(call)
   }
}

data class BehandlingerResponse(val behandlinger: List<JsonNode>)
