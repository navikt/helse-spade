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
import no.nav.helse.spade.behov.*
import org.apache.kafka.clients.producer.*
import java.util.concurrent.*

fun Route.godkjenning(kafkaProducer: KafkaProducer<String, JsonNode>, service: BehovService) {
   post("api/godkjenning") {
      val fromSpeil = call.receive<JsonNode>()
      service.getGodkjenningsbehovForAktør(fromSpeil["aktørId"].asText()).fold(
         { err -> call.respondFeil(err.toHttpFeil()) },
         {
            val fromStore = it.first() { it["@id"].asText() == fromSpeil["@id"].asText() } as ObjectNode
            fromStore.put("godkjent", fromSpeil["godkjent"].asBoolean())
            kafkaProducer
               .send(ProducerRecord(behovTopic, fromStore["sakskompleksId"].asText(), fromStore)).get(5, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.Created)
         }
      )
   }
}
