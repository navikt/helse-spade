package no.nav.helse.spade.behov

import io.ktor.application.call
import io.ktor.routing.Route
import io.ktor.routing.get
import no.nav.helse.respond

fun Route.behov(service: BehovService) {
   get("api/behov/{aktørId}") {
      service.getGodkjenningsbehovForAktør(call.parameters["aktørId"]!!)
         .respond(call)
   }

   get("api/behov/periode") {
      service.getGodkjenningsbehovForPeriode(call.request.queryParameters["fom"]!!, call.request.queryParameters["tom"]!!)
         .respond(call)
   }
}
