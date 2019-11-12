package no.nav.helse.spade.godkjenning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.post
import no.nav.helse.kafka.Topics.behovTopic
import no.nav.helse.respondFeil
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.behov.BehovService
import no.nav.helse.toHttpFeil
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.TimeUnit

fun Route.vedtak(kafkaProducer: KafkaProducer<String, JsonNode>, service: BehovService) {
   post("api/vedtak") {
      val fromSpeil = call.receive<JsonNode>()
      service.getGodkjenningsbehovForAktør(fromSpeil["aktørId"].asText()).fold(
         { err -> call.respondFeil(err.toHttpFeil()) },
         {
            val behov = it.filter { behov ->
               behov.has("@id")
            }.filter { behov ->
               behov["@id"].asText() == fromSpeil["behovId"].asText()
            }.first() as ObjectNode
            val løsning = opprettLøsningForBehov(behov, fromSpeil)
            kafkaProducer
               .send(ProducerRecord(behovTopic, løsning["@id"].asText(), løsning)).get(5, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.Created)
         }
      )
   }
}

fun opprettLøsningForBehov(behov: JsonNode, fraSpeil: JsonNode) = behov.deepCopy<ObjectNode>().apply {
   this["@løsning"] = defaultObjectMapper.createObjectNode().also { løsning ->
      løsning["godkjent"] = fraSpeil["godkjent"]
   }
   this["saksbehandlerIdent"] = fraSpeil["saksbehandlerIdent"]
}
