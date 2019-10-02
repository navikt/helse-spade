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
import no.nav.helse.serde.*
import no.nav.helse.spade.*
import no.nav.helse.spade.behandlinger.BehandlingDto
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
      configureFor(client)
      client.resetMappings()
   }

   @Test
   @KtorExperimentalAPI
   fun `uautentisert forespørsel skal svare med 401`() {
      val aktørId = "12345678911"
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
         handleRequest(HttpMethod.Get, "/api/behandlinger/$aktørId") {}.apply {
            assertEquals(HttpStatusCode.Unauthorized, response.status())
         }
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten påkrevet audience skal svare med 401`() {
      val aktørId = "12345678911"
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup", "wrong_audience")

      produserEnOKBehandling()

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
            handleRequest(HttpMethod.Get, "/api/behandlinger/$aktørId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
         }

         makeRequest(aktørId)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `forespørsel uten medlemskap i påkrevet gruppe skal svare med 401`() {
      val aktørId = "12345678911"
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("group not matching requiredGroup")

      produserEnOKBehandling()

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
            handleRequest(HttpMethod.Get, "/api/behandlinger/$aktørId") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               assertEquals(HttpStatusCode.Unauthorized, response.status())
            }
         }

         makeRequest(aktørId)
      }
   }

   @Test
   @KtorExperimentalAPI
   fun `skal svare med alle behandlinger i en periode`() {
      val fom = "2019-03-13"
      val tom = "2019-03-15"
      val jwkStub = JwtStub("test issuer", server.baseUrl())
      val token = jwkStub.createTokenFor("mygroup")

      produserEnOKBehandling()

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

         fun makeRequest(fom: String, tom: String, maxRetryCount: Int, retryCount: Int = 0) {
            if (maxRetryCount == retryCount) {
               fail { "After $maxRetryCount tries the endpoint is still not available" }
            }

            handleRequest(HttpMethod.Get, "/api/behandlinger/periode/$fom/$tom") {
               addHeader(HttpHeaders.Accept, ContentType.Application.Json.toString())
               addHeader(HttpHeaders.Authorization, "Bearer $token")
               addHeader(HttpHeaders.Origin, "http://localhost")
            }.apply {
               if (response.status() == HttpStatusCode.ServiceUnavailable || response.status() == HttpStatusCode.NotFound) {
                  Thread.sleep(1000)
                  makeRequest(fom, tom, maxRetryCount, retryCount + 1)
               } else {
                  assertEquals(HttpStatusCode.OK, response.status())

                  val jsonNode = defaultObjectMapper.readValue(response.content, JsonNode::class.java)

                  assertTrue(jsonNode.has("behandlinger"))
                  assertTrue(jsonNode.get("behandlinger").isArray)

                  val reader = defaultObjectMapper.readerFor(object : TypeReference<List<BehandlingDto>>() {})
                  val behandlinger: List<BehandlingDto> = reader.readValue(jsonNode.get("behandlinger"))

                  assertTrue(behandlinger.isNotEmpty())
               }
            }
         }

         makeRequest(fom, tom, 20)
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
         put("service.username", username)
         put("service.password", password)
         put("DB_CREDS_PATH_ADMIN", "adminpath")
         put("DB_CREDS_PATH_USER", "userpath")
      }

}
