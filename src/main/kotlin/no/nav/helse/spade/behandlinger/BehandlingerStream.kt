package no.nav.helse.spade.behandlinger

import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.serde.JsonNodeDeserializer
import no.nav.helse.serde.JsonNodeSerializer
import no.nav.helse.serde.ListDeserializer
import no.nav.helse.serde.ListSerializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.Serialized
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.QueryableStoreTypes
import org.slf4j.LoggerFactory
import java.util.*

class BehandlingerStream(props: Properties, private val storeName: String) {

   private val consumer = KafkaStreams(topology(storeName), props)

   init {
      consumer.addShutdownHook()
      consumer.start()
   }

   fun stop() = consumer.close()

   fun state() = consumer.state()

   fun store() = consumer.store(storeName, QueryableStoreTypes.keyValueStore<String, List<JsonNode>>())

   companion object {
      private val log = LoggerFactory.getLogger(BehandlingerStream::class.java)

      fun topology(storeName: String): Topology {
         val builder = StreamsBuilder()

         val keySerde = Serdes.String()
         val valueSerde = Serdes.serdeFrom(JsonNodeSerializer(), JsonNodeDeserializer())
         val listValueSerde = Serdes.serdeFrom(ListSerializer(JsonNodeSerializer()), ListDeserializer(JsonNodeDeserializer()))

         val vedtakStream = builder.stream<String, JsonNode>("aapen-helse-sykepenger-vedtak", Consumed.with(keySerde, valueSerde)
            .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST))
         val failStream = builder.stream<String, JsonNode>("privat-helse-sykepenger-behandlingsfeil", Consumed.with(keySerde, valueSerde)
            .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST))
            .filter { _, node ->
               node.has("originalSøknad") && node.path("originalSøknad").has("aktorId")
            }

         val mergedStream = vedtakStream.merge(failStream)

         val materialized = Materialized.`as`<String, List<JsonNode>, KeyValueStore<Bytes, ByteArray>>(storeName)
            .withKeySerde(keySerde)
            .withValueSerde(listValueSerde)

         mergedStream.filter { _, value ->
            value.has("originalSøknad")
         }.groupBy({ _, value ->
            value.path("originalSøknad").get("aktorId").asText()
         }, Serialized.with(keySerde, valueSerde)).aggregate({
            emptyList()
         }, { _, value, aggregated ->
            val list = aggregated.toMutableList()
            list.add(value)
            list.toList()
         }, materialized)

         return builder.build()
      }

      private fun KafkaStreams.addShutdownHook() {
         setStateListener { newState, oldState ->
            log.info("From state={} to state={}", oldState, newState)

            if (newState == KafkaStreams.State.ERROR) {
               // if the stream has died there is no reason to keep spinning
               log.warn("No reason to keep living, closing stream")
               close()
            }
         }
         setUncaughtExceptionHandler{ _, ex ->
            log.error("Caught exception in stream, exiting", ex)
            close()
         }
         Thread.currentThread().setUncaughtExceptionHandler { _, ex ->
            log.error("Caught exception, exiting", ex)
            close()
         }

         Runtime.getRuntime().addShutdownHook(Thread {
            close()
         })
      }
   }
}
