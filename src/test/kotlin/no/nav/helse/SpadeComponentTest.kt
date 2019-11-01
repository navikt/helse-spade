package no.nav.helse

import com.fasterxml.jackson.core.type.*
import com.fasterxml.jackson.databind.*
import com.github.tomakehurst.wiremock.*
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.*
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import io.ktor.util.*
import no.nav.common.*
import no.nav.helse.kafka.Topics.behovTopic
import no.nav.helse.serde.*
import no.nav.helse.spade.*
import org.apache.kafka.clients.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.config.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.*
import java.util.*

class SpadeComponentTest {

   companion object {
      private const val username = "srvkafkaclient"
      private const val password = "kafkaclient"

      val enAktørId = "12345678910"
      val etBehov = mapOf(
         "@behov" to "GodkjenningFraSaksbehandler",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-01T08:38:00.728127",
         "aktørId" to enAktørId,
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      val server: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

      private val embeddedEnvironment = KafkaEnvironment(
         users = listOf(JAASCredential(username, password)),
         autoStart = false,
         withSchemaRegistry = false,
         withSecurity = true,
         topics = listOf(behovTopic)
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
      configureFor(client)
      client.resetMappings()
   }

   @Test
   @KtorExperimentalAPI
   fun `uautentisert forespørsel skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {
         handleRequest(HttpMethod.Get, "/api/behov/$enAktørId") {}.apply {
            assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten påkrevet audience skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup", "wrong_audience")

      produceOneMessage()

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(aktørId: String) {
            handleRequest(HttpMethod.Get, "/api/behov/$aktørId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
         }

         makeRequest(enAktørId)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten medlemskap i påkrevet gruppe skal svare med 401`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("group not matching requiredGroup")

      produceOneMessage()

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(aktørId: String) {
            handleRequest(HttpMethod.Get, "/api/behov/$aktørId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
         }

         makeRequest(enAktørId)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `andre behov filtreres vekk`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      val endaEnAktørId = "11109876543"
      val behov = mapOf(
         "@behov" to "Sykepengehistorikk",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-01T08:38:00.728127",
         "aktørId" to endaEnAktørId,
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      produceOneMessage(behovTopic, behov)

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            handleRequest(HttpMethod.Get, "/api/behov/$endaEnAktørId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable) {
                  Thread.sleep(1000)
                  makeRequest(maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.NotFound, response.status())
               }
            }
         }

         makeRequest(20)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `alle behov for en person`() {
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      val enAnnenAktørId = "10987654321"
      val behov = mapOf(
         "@behov" to "GodkjenningFraSaksbehandler",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-01T08:38:00.728127",
         "aktørId" to enAnnenAktørId,
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      produceOneMessage(behovTopic, behov)

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            handleRequest(HttpMethod.Get, "/api/behov/$enAnnenAktørId") {
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

                  assertEquals(1, behovliste.size)
                  assertEquals(defaultObjectMapper.valueToTree(behov), behovliste[0])
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

      val behov = mapOf(
         "@behov" to "GodkjenningFraSaksbehandler",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-01T00:00:00.000000",
         "aktørId" to "12345678910",
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      val behov2 = mapOf(
         "@behov" to "GodkjenningFraSaksbehandler",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-02T00:00:00.000000",
         "aktørId" to "23456789101",
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      val behovUtenforPeriode = mapOf(
         "@behov" to "GodkjenningFraSaksbehandler",
         "@id" to "ea5d644b-ffb9-4c32-bbd9-f93744554d5e",
         "@opprettet" to "2019-11-03T00:00:00.000000",
         "aktørId" to "34567891011",
         "organisasjonsnummer" to "123456789",
         "sakskompleksId" to "sakskompleks-uuid"
      )

      produceOneMessage(behovTopic, behov)
      produceOneMessage(behovTopic, behov2)
      produceOneMessage(behovTopic, behovUtenforPeriode)

      stubFor(jwkStub.stubbedJwkProvider())
      stubFor(jwkStub.stubbedConfigProvider())

      withApplication(
         environment = createTestEnvironment {
            fakeConfig(this)

            connector {
               port = 8080
            }

            module {
               spade()
            }
         }) {

         fun makeRequest(maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            handleRequest(HttpMethod.Get, "/api/behov/periode?fom=2019-11-01&tom=2019-11-02") {
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
                  assertTrue(behovliste.contains(defaultObjectMapper.valueToTree(behov)))
                  assertTrue(behovliste.contains(defaultObjectMapper.valueToTree(behov2)))
               }
            }
         }

         makeRequest(20)
      }
   }

   private fun produceOneMessage(topic: String = behovTopic, message: Map<String, String> = etBehov) {
      val jsonMessage: JsonNode = defaultObjectMapper.valueToTree<JsonNode>(message)
      val producer = KafkaProducer<String, JsonNode>(producerProperties())
      producer.send(ProducerRecord(topic, jsonMessage))
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
         put(ProducerConfig.LINGER_MS_CONFIG, "0")
         put(ProducerConfig.RETRIES_CONFIG, "0")
         put(SaslConfigs.SASL_MECHANISM, "PLAIN")
         put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";")
      }
   }

   private fun String.readResource() = object {}.javaClass.getResource(this)?.readText(Charsets.UTF_8) ?: fail { "did not find <$this>" }

   @KtorExperimentalAPI
   private fun fakeConfig(envBuilder: ApplicationEngineEnvironmentBuilder) =
      with (envBuilder.config as MapApplicationConfig) {
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
      }

}
