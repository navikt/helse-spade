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
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.Serialized
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.QueryableStoreTypes
import org.slf4j.LoggerFactory
import java.util.*

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
      const val trengerGodkjenning = "GodkjenningFraSaksbehandler"
   }

   fun topology(storeName: String): Topology {
      val builder = StreamsBuilder()

      val keySerde = Serdes.String()
      val valueSerde = Serdes.serdeFrom(JsonNodeSerializer(), JsonNodeDeserializer())
      val listValueSerde = Serdes.serdeFrom(ListSerializer(JsonNodeSerializer()), ListDeserializer(JsonNodeDeserializer()))

      val behovStream = builder.stream<String, JsonNode>(Topics.behovTopic, Consumed.with(keySerde, valueSerde)
         .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST))

      val materialized = Materialized.`as`<String, List<JsonNode>, KeyValueStore<Bytes, ByteArray>>(storeName)
         .withKeySerde(keySerde)
         .withValueSerde(listValueSerde)

      behovStream
         //TODO: Filtrere ut behov som matcher et behov med løsning
         .filter { _, value -> isNeedsApproval(value[behovKey]) }
         .filter { _, value -> value[løsningKey] == null }
         .groupBy({ _, value -> value[aktørIdKey].asText() }, Serialized.with(keySerde, valueSerde))
         .aggregate({ emptyList() }, { _, value, aggregated ->
            aggregated.toMutableList().apply { add(value) }
         }, materialized)

      return builder.build()
   }

   private fun isNeedsApproval(behovFelt : JsonNode)  =
      if ( behovFelt.isArray ) {
         behovFelt.map { b -> b.asText() }.any { t -> t == trengerGodkjenning }
      } else {
         behovFelt.asText() == trengerGodkjenning
      }

   private fun KafkaStreams.addShutdownHook() {
      setStateListener { newState, oldState ->
         log.info("From state={} to state={}", oldState, newState)
      }

      Runtime.getRuntime().addShutdownHook(Thread {
         close()
      })
   }
}
