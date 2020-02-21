package no.nav.helse.spade.godkjenning

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.post
import no.nav.helse.kafka.Topics.rapidTopic
import no.nav.helse.respondFeil
import no.nav.helse.spade.behov.BehovConsumer.Companion.behovNavn
import no.nav.helse.spade.behov.BehovService
import no.nav.helse.toHttpFeil
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit


fun Route.vedtak(kafkaProducer: KafkaProducer<String, JsonNode>, service: BehovService) {
   val log = LoggerFactory.getLogger("Route.vedtak")
   post("api/vedtak") {
      val request = call.receive<JsonNode>()
      log.info("Fatter vedtak med vedtaksperiodeId: ${request.get("vedtaksperiodeId").asText()}")
      service.getGodkjenningsbehovForAktør(request["aktørId"].asText()).fold(
         { err -> call.respondFeil(err.toHttpFeil()) },
         {
            val behov = it.senesteSomMatcher(request)
            val løsning = løstBehov(behov, request)

            kafkaProducer
               .send(ProducerRecord(rapidTopic, løsning["fødselsnummer"].asText(), løsning)).get(5, TimeUnit.SECONDS)
            call.respond(HttpStatusCode.Created)
         }
      )
   }
}

internal fun løstBehov(behov: JsonNode, request: JsonNode) =
    (behov.deepCopy() as ObjectNode)
      .also { node -> node.set<JsonNode>("@løsning", JsonNodeFactory.instance.objectNode().set<JsonNode>(behovNavn, opprettLøsningForBehov(request))) }
      .also { node -> node.set<JsonNode>("saksbehandlerIdent", request["saksbehandlerIdent"]) }

internal fun List<JsonNode>.senesteSomMatcher(request: JsonNode) =
   this.sortedBy { LocalDateTime.parse(it.get("@opprettet").asText()) }
      .last { behov -> matcherPåBehovId(behov, request) || matcherPåVedtaksperiodeId(behov, request) }

internal fun matcherPåVedtaksperiodeId(behov: JsonNode, request: JsonNode) =
   behov.has("vedtaksperiodeId") && behov["vedtaksperiodeId"] == request["vedtaksperiodeId"]
      && behov["@behov"].any { it.asText() == behovNavn }

private fun matcherPåBehovId(behov: JsonNode, request: JsonNode) =
   behov.has("@id") && request.has("behovId") && behov["@id"].asText() == request["behovId"].asText()

internal fun opprettLøsningForBehov(fraSpeil: JsonNode) =
   JsonNodeFactory.instance.objectNode().set<JsonNode>("godkjent", fraSpeil["godkjent"])

