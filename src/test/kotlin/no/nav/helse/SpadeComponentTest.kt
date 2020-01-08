package no.nav.helse

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.ktor.config.MapApplicationConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.connector
import io.ktor.server.testing.*
import io.ktor.util.KtorExperimentalAPI
import no.nav.common.JAASCredential
import no.nav.common.KafkaEnvironment
import no.nav.helse.kafka.Topics.behovTopic
import no.nav.helse.serde.JsonNodeSerializer
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.spade
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

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
         topicNames = listOf(behovTopic)
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

         try {
            File("/tmp/kafka-streams/").deleteRecursively()
         } catch (e: Exception) { /* you'll have to delete the materialized kstream yourself. */
         }

         embeddedEnvironment.tearDown()
      }
   }

   @BeforeEach
   fun configure() {
      val client = WireMock.create().port(server.port()).build()
      configureFor(client)
      client.resetMappings()
   }

   @Test
   @KtorExperimentalAPI
   fun `uautentisert forespørsel skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {
         it.handleRequest(HttpMethod.Get, "/api/behov/whatever") {}.apply {
            assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten påkrevet audience skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup", "wrong_audience")

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {
         it.handleRequest(HttpMethod.Get, "/api/behov/whatever") {
            addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
            addHeader(HttpHeaders.Authorization, "Bearer $token")
            addHeader(HttpHeaders.Origin, "http://localhost")
         }.apply {
            assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten medlemskap i påkrevet gruppe skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("group not matching requiredGroup")

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {
         it.handleRequest(HttpMethod.Get, "/api/behov/whatever") {
            addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
            addHeader(HttpHeaders.Authorization, "Bearer $token")
            addHeader(HttpHeaders.Origin, "http://localhost")
         }.apply {
            assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `behov for andre enn forespurt aktør filtreres vekk`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      produceOneMessagePrevFormat("12345678910")
      produceOneMessagePrevFormat("12345678911")

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {

         await("Vent på behov")
            .atMost(20, TimeUnit.SECONDS)
            .untilAsserted {
               it.handleRequest(HttpMethod.Get, "/api/behov/12345678910") {
                  addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
                  addHeader(HttpHeaders.Authorization, "Bearer $token")
                  addHeader(HttpHeaders.Origin, "http://localhost")
               }.apply {
                  assertEquals(HttpStatusCode.OK, response.status())
                  val reader = defaultObjectMapper.readerFor(object : TypeReference<List<JsonNode>>() {})
                  val behovliste: List<JsonNode> = reader.readValue(response.content)
                  assertEquals(1, behovliste.size)
               }
            }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `aktør kan ha flere enn 1 behov`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      val aktøren = "12345678912"
      produceOneMessagePrevFormat(aktøren)
      produceOneMessagePrevFormat(aktøren)

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {
         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            it.handleRequest(HttpMethod.Get, "/api/behov/$aktøren") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable || response.status() == HttpStatusCode.NotFound) {
                  Thread.sleep(1000)
                  makeRequest(maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.OK, response.status())
                  val reader = defaultObjectMapper.readerFor(object : TypeReference<List<JsonNode>>() {})
                  val behovliste: List<JsonNode> = reader.readValue(response.content)
                  assertEquals(2, behovliste.size)
               }
            }
         }

         makeRequest(20)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `alle behov for en tidsperiode`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      produceOneMessage("12345678913", "2019-11-10T00:00:00.000000")
      produceOneMessagePrevFormat("12345678914", "2019-11-12T00:00:00.000000")
      produceOneMessagePrevFormat("12345678915", "2019-11-13T00:00:00.000000")

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withTestKtor {

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            it.handleRequest(HttpMethod.Get, "/api/behov/periode?fom=2019-10-01&tom=2019-11-12") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable || response.status() == HttpStatusCode.NotFound) {
                  Thread.sleep(1000)
                  makeRequest(maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.OK, response.status())

                  val reader = defaultObjectMapper.readerFor(object : TypeReference<List<JsonNode>>() {})
                  val behovliste: List<JsonNode> = reader.readValue(response.content)

                  assertEquals(2, behovliste.size)
               }
            }
         }

         makeRequest(20)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `godkjenning av utbetaling`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      produceOneMessage("12345678916")
      val origBehov = (defaultObjectMapper.readTree(
         File("src/test/resources/behov/behovSomListe.json").readText()) as ObjectNode).apply {
         put("aktørId", "12345678916")
      }

      withTestKtor {
         val token = jwkStub.createTokenFor("mygroup")

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }
            it.handleRequest(HttpMethod.Post, "/api/vedtak") {
               setHeaders(token)
               setBody("""{"aktørId": "${origBehov["aktørId"].asText()}", "behovId": "${origBehov["@id"].asText()}","godkjent": true, "saksbehandlerIdent": "A123123"}""")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable || response.status() == HttpStatusCode.NotFound) {
                  Thread.sleep(1000)
                  makeRequest(maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.Created, response.status())
               }
            }
         }
         makeRequest(20)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `godkjenning av utbetaling uten behovid`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      produceOneMessage("12345678916")
      val origBehov = (defaultObjectMapper.readTree(
         File("src/test/resources/behov/behovSomListe.json").readText()) as ObjectNode).apply {
         put("aktørId", "12345678916")
      }

      withTestKtor {
         val token = jwkStub.createTokenFor("mygroup")

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }
            it.handleRequest(HttpMethod.Post, "/api/vedtak") {
               setHeaders(token)
               setBody("""{"aktørId": "${origBehov["aktørId"].asText()}", "vedtaksperiodeId": "vedtaksperiode-uuid","godkjent": true, "saksbehandlerIdent": "A123123"}""")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable || response.status() == HttpStatusCode.NotFound) {
                  Thread.sleep(1000)
                  makeRequest(maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.Created, response.status())
               }
            }
         }
         makeRequest(20)
      }
   }

   private fun produceOneMessagePrevFormat(aktørId: String, timestamp: String? = null) {
      val message = defaultObjectMapper.readTree(
         File("src/test/resources/behov/behov.json").readText()) as ObjectNode
      message.put("aktørId", aktørId)
      timestamp?.let {
         message.put("@opprettet", timestamp)
      }
      val producer = KafkaProducer<String, JsonNode>(producerProperties())
      producer.send(ProducerRecord(behovTopic, message))
      producer.flush()
   }

   private fun produceOneMessage(aktørId: String, timestamp: String? = null) {
      val message = defaultObjectMapper.readTree(
         File("src/test/resources/behov/behovSomListe.json").readText()) as ObjectNode
      message.put("aktørId", aktørId)
      timestamp?.let {
         message.put("@opprettet", timestamp)
      }
      val producer = KafkaProducer<String, JsonNode>(producerProperties())
      producer.send(ProducerRecord(behovTopic, message))
      producer.flush()
   }

   private fun producerProperties(): Properties {
      return Properties().apply {
         put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, embeddedEnvironment.brokersURL)
         put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
         put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonNodeSerializer::class.java)
         put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
         // Make sure our producer waits until the message is received by Kafka before returning. This is to make sure the tests can send messages in a specific order
         put(ProducerConfig.ACKS_CONFIG, "all")
         put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
         put(ProducerConfig.LINGER_MS_CONFIG, "10")
         put(SaslConfigs.SASL_MECHANISM, "PLAIN")
         put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";")
      }
   }

   @KtorExperimentalAPI
   private fun fakeConfig(envBuilder: ApplicationEngineEnvironmentBuilder) =
      with(envBuilder.config as MapApplicationConfig) {
         put("oidcConfigUrl", server.baseUrl() + "/config")
         put("issuer", "test issuer")
         put("clientId", "el_cliento")
         put("clientSecret", "el_secreto")
         put("requiredGroup", "mygroup")

         put("kafka.app-id", "spade-v1")
         put("kafka.store-name", "sykepenger-state-store")
         put("kafka.bootstrap-servers", embeddedEnvironment.brokersURL)
         put("kafka.commit-interval-ms-config", "100")
         put("service.username", username)
         put("service.password", password)
         put("DB_CREDS_PATH_ADMIN", "adminpath")
         put("DB_CREDS_PATH_USER", "userpath")
         put("security.protocol", "SASL_PLAINTEXT")
         put(ProducerConfig.ACKS_CONFIG, "all")
         put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
         put(ProducerConfig.LINGER_MS_CONFIG, "0")
      }

   @KtorExperimentalAPI
   private fun withTestKtor(f: (TestApplicationEngine) -> Unit) {

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) { f(this) }
   }

   private fun TestApplicationRequest.setHeaders(token: String) {
      addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
      addHeader(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      addHeader(HttpHeaders.Authorization, "Bearer $token")
      addHeader(HttpHeaders.Origin, "http://localhost")
   }
}
