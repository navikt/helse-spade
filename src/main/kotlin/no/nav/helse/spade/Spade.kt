package no.nav.helse.spade

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.authentication
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.features.*
import io.ktor.jackson.jackson
import io.ktor.request.path
import io.ktor.request.uri
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import no.nav.helse.http.getJson
import no.nav.helse.nais.nais
import no.nav.helse.serde.JsonNodeSerializer
import no.nav.helse.spade.behov.BehovConsumer
import no.nav.helse.spade.behov.BehovService
import no.nav.helse.spade.behov.KafkaBehovRepository
import no.nav.helse.spade.behov.behov
import no.nav.helse.spade.godkjenning.vedtak
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.net.URL
import java.util.*

private val auditLog = LoggerFactory.getLogger("auditLogger")

@KtorExperimentalAPI
fun Application.spade() {
   log.info("Starting spade")
   val idProvider = environment.config.property("oidcConfigUrl").getString()
      .getJson()
      .fold(
         { throw it },
         { it }
      )

   val jwkProvider = JwkProviderBuilder(URL(idProvider["jwks_uri"].toString())).build()
   val stream = BehovConsumer(streamConfig(), environment.config.property("kafka.store-name").getString())

   environment.monitor.subscribe(ApplicationStopping) {
      stream.stop()
   }

   val requiredGroup = environment.config.property("requiredGroup").getString()
   install(Authentication) {
      jwt {
         verifier(jwkProvider, idProvider["issuer"].toString())
         realm = environment.config.propertyOrNull("ktor.application.id")?.getString() ?: "Application"
         validate { credentials ->
            val groupsClaim = credentials.payload.getClaim("groups").asList(String::class.java)
            if (requiredGroup in groupsClaim &&
               environment.config.property("clientId").getString() in credentials.payload.audience) {
               JWTPrincipal(credentials.payload)
            } else {
               log.info("${credentials.payload.getClaim("NAVident").asString()} with audience ${credentials.payload.audience} " +
                  "is not authorized to use this app, denying access")
               null
            }
         }
      }
   }

   install(CORS) {
      host(host = "nais.adeo.no", schemes = listOf("https"), subDomains = listOf("speil", "spade"))
      host(host = "nais.preprod.local", schemes = listOf("https"), subDomains = listOf("speil", "spade"))
      host(host = "localhost", schemes = listOf("http", "https"))
      allowCredentials = true
   }

   nais({
      stream.state().isRunning
   })

   install(CallId) {
      header("Nav-Call-Id")

      generate {
         UUID.randomUUID().toString()
      }
   }

   intercept(ApplicationCallPipeline.Call) {
      (call.authentication.principal as JWTPrincipal).payload.let { payload ->
         log.info("Bruker=\"${payload.subject}\" gjør kall mot url=\"${call.request.uri}\"")
         auditLog.info("Bruker=\"${payload.subject}\" gjør kall mot url=\"${call.request.uri}\"")
      }
   }

   install(CallLogging) {
      level = Level.INFO
      callIdMdc("call_id")
      filter {
         it.request.path() != "/isready"
            && it.request.path() != "/isalive"
            && it.request.path() != "/metrics"
      }
   }

   install(ContentNegotiation) {
      jackson {
         registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      }
   }

   val behovService = BehovService(KafkaBehovRepository(stream))

   routing {
      authenticate {
         behov(behovService)
         vedtak(KafkaProducer(producerConfig()), behovService)
      }
   }
}

@KtorExperimentalAPI
private fun Application.streamConfig() = Properties().apply {
   put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, environment.config.property("kafka.bootstrap-servers").getString())
   put(StreamsConfig.APPLICATION_ID_CONFIG, environment.config.property("kafka.app-id").getString())

   put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailExceptionHandler::class.java)
   put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, environment.config.propertyOrNull("kafka.commit-interval-ms-config")?.getString() ?: "1000")

   put(SaslConfigs.SASL_MECHANISM, "PLAIN")

   putAll(commonProps())
}

@KtorExperimentalAPI
private fun Application.producerConfig() = Properties().apply {
   put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, environment.config.property("kafka.bootstrap-servers").getString())
   put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
   put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonNodeSerializer::class.java)
   // Make sure our producer waits until the message is received by Kafka before returning. This is to make sure the tests can send messages in a specific order
   put(ProducerConfig.ACKS_CONFIG, "all")
   put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
   put(ProducerConfig.LINGER_MS_CONFIG, "0")
   put(ProducerConfig.RETRIES_CONFIG, "0")
   put(SaslConfigs.SASL_MECHANISM, "PLAIN")

   putAll(commonProps())
}

@KtorExperimentalAPI
fun Application.commonProps() = Properties().apply {
   environment.config.propertyOrNull("service.username")?.getString()?.let { username ->
      environment.config.propertyOrNull("service.password")?.getString()?.let { password ->
         put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";")
      }
   }

   put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
   environment.config.propertyOrNull("kafka.truststore-path")?.getString()?.let { truststorePath ->
      environment.config.propertyOrNull("kafka.truststore-password")?.getString().let { truststorePassword ->
         try {
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
            put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, File(truststorePath).absolutePath)
            put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword)
            log.info("Configured '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location ")
         } catch (ex: Exception) {
            log.error("Failed to set '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location", ex)
         }
      }
   }
}


