package no.nav.helse.spade.behov

import com.fasterxml.jackson.databind.*
import no.nav.helse.serde.*
import org.apache.kafka.common.serialization.*
import org.apache.kafka.common.utils.*
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.state.*
import org.slf4j.*
import java.util.*

class BehovConsumer(props: Properties, private val storeName: String) {

   private val consumer = KafkaStreams(topology(storeName), props)
   private val topicName = "privat-helse-sykepenger-behov"


   init {
      consumer.addShutdownHook()
      consumer.start()
   }

   companion object {
      private val log = LoggerFactory.getLogger(BehovConsumer::class.java)
   }

   fun topology(storeName: String): Topology {
      val builder = StreamsBuilder()

      val keySerde = Serdes.String()
      val valueSerde = Serdes.serdeFrom(JsonNodeSerializer(), JsonNodeDeserializer())
      val listValueSerde = Serdes.serdeFrom(ListSerializer(JsonNodeSerializer()), ListDeserializer(JsonNodeDeserializer()))

      val behovStream = builder.stream<String, JsonNode>(topicName, Consumed.with(keySerde, valueSerde)
         .withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST))

      val materialized = Materialized.`as`<String, List<JsonNode>, KeyValueStore<Bytes, ByteArray>>(storeName)
         .withKeySerde(keySerde)
         .withValueSerde(listValueSerde)

      behovStream.filter { _, value -> value["@løsning"].isNull }
         .groupBy({ _, value -> value["id"].asText() }, Serialized.with(keySerde, valueSerde))
         .aggregate({
            emptyList()
         }, { _, value, aggregated ->
            aggregated.toMutableList().apply { add(value) }
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
