package no.nav.helse

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.ktor.config.MapApplicationConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.connector
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.common.JAASCredential
import no.nav.common.KafkaEnvironment
import no.nav.helse.serde.JsonNodeSerializer
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.spade
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.util.*

class SpadeComponentTest {

   companion object {
      private const val username = "srvkafkaclient"
      private const val password = "kafkaclient"

      val server: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

      private val embeddedEnvironment = KafkaEnvironment(
         users = listOf(JAASCredential(username, password)),
         autoStart = false,
         withSchemaRegistry = false,
         withSecurity = true,
         topics = listOf("aapen-helse-sykepenger-vedtak", "privat-helse-sykepenger-behandlingsfeil")
      )

      @BeforeAll
      @JvmStatic
      fun start() {
         server.start()

         embeddedEnvironment.start()
      }


      @AfterAll
      @JvmStatic
      fun stop() {
         server.stop()

         try { File("/tmp/kafka-streams/").deleteRecursively() } catch(e: Exception) { /* you'll have to delete the materialized kstream yourself. */}

         embeddedEnvironment.tearDown()
      }
   }

   @BeforeEach
   fun configure() {
      val client = WireMock.create().port(server.port()).build()
      WireMock.configureFor(client)
      client.resetMappings()
   }

   @Test
   @KtorExperimentalAPI
   fun `uautentisert forespørsel skal svare med 401`() {
      val aktørId = "12345678911"
      val jwkStub = JwtStub("test issuer", server.baseUrl())

      WireMock.stubFor(jwkStub.stubbedJwkProvider())
      WireMock.stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            with (config as MapApplicationConfig) {
               put("oidcConfigUrl", server.baseUrl() + "/config")
               put("issuer", "test issuer")
               put("clientId", "el_cliento")
               put("clientSecret", "el_secreto")

               put("kafka.app-id", "spade-v1")
               put("kafka.store-name", "sykepenger-state-store")
               put("kafka.bootstrap-servers", embeddedEnvironment.brokersURL)
               put("kafka.username", username)
               put("kafka.password", password)
            }

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {
         handleRequest(HttpMethod.Get, "/api/behandlinger/$aktørId") {}.apply {
            Assertions.assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `skal svare med alle behandlinger for en søknad`() {
      val søknadId = "1111111111"
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("S150563")

      produserEnOKBehandling()

      WireMock.stubFor(jwkStub.stubbedJwkProvider())
      WireMock.stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            with (config as MapApplicationConfig) {
               put("oidcConfigUrl", server.baseUrl() + "/config")
               put("issuer", "test issuer")
               put("clientId", "el_cliento")
               put("clientSecret", "el_secreto")

               put("kafka.app-id", "spade-v1")
               put("kafka.store-name", "sykepenger-state-store")
               put("kafka.bootstrap-servers", embeddedEnvironment.brokersURL)
               put("kafka.username", username)
               put("kafka.password", password)
            }

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(søknadId: String, maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            handleRequest(HttpMethod.Get, "/api/soknader/$søknadId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable) {
                  Thread.sleep(1000)
                  makeRequest(søknadId, maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.OK, response.status())

                  val jsonNode = defaultObjectMapper.readValue(response.content, JsonNode::class.java)

                  assertTrue(jsonNode.has("behandlinger"))
                  assertTrue(jsonNode.get("behandlinger").isArray)

                  val reader = defaultObjectMapper.readerFor(object : TypeReference<List<JsonNode>>() {})
                  val behandlinger: List<JsonNode> = reader.readValue(jsonNode.get("behandlinger"))

                  assertEquals(1, behandlinger.size)
               }
            }
         }

         makeRequest(søknadId, 20)
      }
   }

   private fun produserEnOKBehandling() {
      val søknad = "/behandling_ok.json".readResource()
      val json = defaultObjectMapper.readValue(søknad, JsonNode::class.java)

      produceOneMessage(json)
   }

   private fun produceOneMessage(message: JsonNode) {
      val producer = KafkaProducer<String, JsonNode>(producerProperties())
      producer.send(ProducerRecord("aapen-helse-sykepenger-vedtak", message))
      producer.flush()
   }

   private fun producerProperties(): Properties {
      return Properties().apply {
         put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedEnvironment.brokersURL)
         put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
         put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonNodeSerializer::class.java)
         put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
         put(SaslConfigs.SASL_MECHANISM, "PLAIN")
         put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";")
      }
   }

   private fun String.readResource() = object {}.javaClass.getResource(this)?.readText(Charsets.UTF_8) ?: fail { "did not find <$this>" }
}
