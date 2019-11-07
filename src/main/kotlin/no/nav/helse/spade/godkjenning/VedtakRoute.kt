package no.nav.helse.spade.godkjenning

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import no.nav.helse.*
import no.nav.helse.kafka.Topics.behovTopic
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.behov.*
import org.apache.kafka.clients.producer.*
import java.util.concurrent.*

fun Route.vedtak(kafkaProducer: KafkaProducer<String, JsonNode>, service: BehovService) {
   post("api/vedtak") {
      val fromSpeil = call.receive<JsonNode>()
      service.getGodkjenningsbehovForAktør(fromSpeil["aktørId"].asText()).fold(
         { err -> call.respondFeil(err.toHttpFeil()) },
         {
            val fromStore = it.first() { it["@id"].asText() == fromSpeil["behovId"].asText() } as ObjectNode
            val godkjentNode = defaultObjectMapper.createObjectNode()
            godkjentNode.set("godkjent", fromSpeil["godkjent"])
            val saksbehandlerIdent = fromSpeil.get("saksbehandlerIdent")
            fromStore.set("@løsning", godkjentNode)
            fromStore.set("saksbehandlerIdent", saksbehandlerIdent)
            kafkaProducer
               .send(ProducerRecord(behovTopic, fromStore["@id"].asText(), fromStore)).get(5, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.Created)
         }
      )
   }
}
