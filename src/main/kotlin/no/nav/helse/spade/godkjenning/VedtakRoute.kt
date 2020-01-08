package no.nav.helse.spade.godkjenning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
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
      val request = call.receive<JsonNode>()
      service.getGodkjenningsbehovForAktør(request["aktørId"].asText()).fold(
         { err -> call.respondFeil(err.toHttpFeil()) },
         {
            val behov = it.first { behov ->
               matcherPåBehovId(behov, request) || matcherPåVedtaksperiodeId(behov, request)
            } as ObjectNode
            val løsning = opprettLøsningForBehov(behov, request)
            kafkaProducer
               .send(ProducerRecord(behovTopic, løsning["@id"].asText(), løsning)).get(5, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.Created)
         }
      )
   }
}

internal fun matcherPåVedtaksperiodeId(behov: JsonNode, request: JsonNode) =
   behov.has("vedtaksperiodeId") && request.has("vedtaksperiodeId") && behov["vedtaksperiodeId"].asText() == request["vedtaksperiodeId"].asText()
      && (behov["@behov"] as ArrayNode).map { it.asText() }.contains("GodkjenningFraSaksbehandler")

private fun matcherPåBehovId(behov: JsonNode, request: JsonNode) =
   behov.has("@id") && request.has("behovId") && behov["@id"].asText() == request["behovId"].asText()

fun opprettLøsningForBehov(behov: JsonNode, fraSpeil: JsonNode) = behov.deepCopy<ObjectNode>().apply {
   this["@løsning"] = defaultObjectMapper.createObjectNode().also { løsning ->
      løsning["godkjent"] = fraSpeil["godkjent"]
   }
   this["saksbehandlerIdent"] = fraSpeil["saksbehandlerIdent"]
}
