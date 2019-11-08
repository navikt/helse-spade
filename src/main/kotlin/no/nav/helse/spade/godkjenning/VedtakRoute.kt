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
            val behov = it.first { behov -> behov["@id"].asText() == fromSpeil["behovId"].asText() } as ObjectNode
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
