package no.nav.helse.serde

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.*

val defaultObjectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)


class JsonNodeDeserializer: Deserializer<JsonNode> {
    private val log = LoggerFactory.getLogger(JsonNodeDeserializer::class.java)

    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) { }

    override fun deserialize(topic: String?, data: ByteArray?): JsonNode? {
        return data?.let {
            try {
                defaultObjectMapper.readTree(it)
            } catch (e: Exception) {
                log.warn("Not a valid json",e)
                null
            }
        }
    }

    override fun close() { }
}

class JsonNodeSerializer: Serializer<JsonNode> {
    private val log = LoggerFactory.getLogger("JsonSerializer")
    override fun serialize(topic: String?, data: JsonNode?): ByteArray? {
        return data?.let {
            try {
                defaultObjectMapper.writeValueAsBytes(it)
            }
            catch(e: Exception) {
                log.warn("Could not serialize JsonNode",e)
                null
            }
        }
    }

    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) { }
    override fun close() { }

}

class ListSerializer<T>(private val inner: Serializer<T>)  : Serializer<List<T>> {

    override fun configure(configs: Map<String, *>, isKey: Boolean) {
        // do nothing
    }

    override fun serialize(topic: String, queue: List<T>): ByteArray {
        val size = queue.size
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val iterator = queue.iterator()
        try {
            dos.writeInt(size)
            while (iterator.hasNext()) {
                val bytes = inner.serialize(topic, iterator.next())
                dos.writeInt(bytes.size)
                dos.write(bytes)
            }
        } catch (e: IOException) {
            throw RuntimeException("Unable to serialize ArrayList", e)
        }

        return baos.toByteArray()
    }

    override fun close() {
        inner.close()
    }
}

class ListDeserializer<T>(private val valueDeserializer: Deserializer<T>) : Deserializer<List<T>> {

    override fun configure(configs: Map<String, *>, isKey: Boolean) {
        // do nothing
    }

    override fun deserialize(topic: String, bytes: ByteArray?): ArrayList<T>? {
        if (bytes == null || bytes.size == 0) {
            return null
        }

        val arrayList = ArrayList<T>()
        val dataInputStream = DataInputStream(ByteArrayInputStream(bytes))

        try {
            val records = dataInputStream.readInt()
            for (i in 0 until records) {
                val valueBytes = ByteArray(dataInputStream.readInt())
                dataInputStream.read(valueBytes)
                arrayList.add(valueDeserializer.deserialize(topic, valueBytes))
            }
        } catch (e: IOException) {
            throw RuntimeException("Unable to deserialize ArrayList", e)
        }

        return arrayList
    }

    override fun close() {
        // do nothing
    }
}
