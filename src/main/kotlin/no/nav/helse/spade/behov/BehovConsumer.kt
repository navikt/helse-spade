package no.nav.helse.spade.behov

import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.kafka.Topics
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
import org.apache.kafka.streams.kstream.Grouped
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.QueryableStoreTypes
import org.slf4j.LoggerFactory
import java.util.*

private val auditLog = LoggerFactory.getLogger("auditLogger")

class BehovConsumer(props: Properties, private val storeName: String) {
   private val consumer = KafkaStreams(topology(storeName), props)

   init {
      consumer.addShutdownHook()
      consumer.start()
   }

   fun stop() = consumer.close()

   fun state() = consumer.state()

   fun store() = consumer.store(storeName, QueryableStoreTypes.keyValueStore<String, List<JsonNode>>())

   companion object {
      private val log = LoggerFactory.getLogger(BehovConsumer::class.java)
      const val aktørIdKey = "aktørId"
      const val løsningKey = "@løsning"
      const val behovKey = "@behov"
      const val behovNavn = "Godkjenning"
      const val idKey = "@id"
   }

   private fun topology(storeName: String): Topology {
      val builder = StreamsBuilder()

      val keySerde = Serdes.String()
      val valueSerde = Serdes.serdeFrom(JsonNodeSerializer(), JsonNodeDeserializer())
      val listValueSerde =
         Serdes.serdeFrom(ListSerializer(JsonNodeSerializer()), ListDeserializer(JsonNodeDeserializer()))

      val behovStream = builder.stream(
         Topics.rapidTopic, Consumed.with(keySerde, valueSerde)
            .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST)
      )

      val materialized = Materialized.`as`<String, List<JsonNode>, KeyValueStore<Bytes, ByteArray>>(storeName)
         .withKeySerde(keySerde)
         .withValueSerde(listValueSerde)

      behovStream
         .filter { _, value -> value != null }
         .filter { _, value -> value.hasNonNull(behovKey) }
         .peek { _, value -> auditLog.info("Fant behov: $value") }
         .filter { _, value -> isNeedsApproval(value[behovKey]) }
         .peek { _, value -> auditLog.info("Fant godkjenningsbehov: $value") }
         .groupBy({ _, behov -> behov[idKey].asText() }, Grouped.with(keySerde, valueSerde))
         .reduce { aggregertBehov, behovet ->
            if (aggregertBehov[løsningKey] != null) aggregertBehov
            else behovet
         }
         .toStream()
         .peek { _, value -> auditLog.info("Etter group/reduce: $value") }
         .filter { _, value -> value[løsningKey] == null }
         .groupBy({ _, value -> value[aktørIdKey].asText() }, Grouped.with(keySerde, valueSerde))
         .aggregate(
            { emptyList() },
            { _, value, aggregated -> aggregated.toMutableList().apply { add(value) } },
            materialized
         )

      return builder.build()
   }

   private fun isNeedsApproval(behovFelt: JsonNode) =
      behovFelt.map { b -> b.asText() }.any { t -> t == behovNavn }

   private fun KafkaStreams.addShutdownHook() {
      setStateListener { newState, oldState ->
         log.info("From state={} to state={}", oldState, newState)
      }

      Runtime.getRuntime().addShutdownHook(Thread {
         close()
      })
   }
}
