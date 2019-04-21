package no.nav.helse.spade

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.application.Application
import io.ktor.application.ApplicationStopping
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.features.CallId
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.callIdMdc
import io.ktor.jackson.jackson
import io.ktor.request.path
import io.ktor.routing.routing
import io.ktor.util.*
import no.nav.helse.nais.nais
import no.nav.helse.spade.behandlinger.BehandlingerService
import no.nav.helse.spade.behandlinger.BehandlingerStream
import no.nav.helse.spade.behandlinger.KafkaBehandlingerRepository
import no.nav.helse.spade.behandlinger.behandlinger
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.slf4j.event.Level
import java.io.File
import java.net.URL
import java.util.*

@KtorExperimentalAPI
fun Application.spade() {
   val jwkProvider = JwkProviderBuilder(URL(environment.config.property("jwks.url").getString())).build()
   val stream = BehandlingerStream(streamConfig(), environment.config.property("kafka.store-name").getString())

   environment.monitor.subscribe(ApplicationStopping) {
      stream.stop()
   }

   install(Authentication) {
      jwt {
         verifier(jwkProvider, environment.config.property("jwt.issuer").getString())
         realm = environment.config.propertyOrNull("ktor.application.id")?.getString() ?: "Application"
         validate { credentials ->
            JWTPrincipal(credentials.payload)
         }
      }
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

   val behandlingerService = BehandlingerService(KafkaBehandlingerRepository(stream))

   routing {
      authenticate {
         behandlinger(behandlingerService)
      }
   }
}

@KtorExperimentalAPI
private fun Application.streamConfig() = Properties().apply {
   put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, environment.config.property("kafka.bootstrap-servers").getString())
   put(StreamsConfig.APPLICATION_ID_CONFIG, environment.config.property("kafka.app-id").getString())

   put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailExceptionHandler::class.java)

   put(SaslConfigs.SASL_MECHANISM, "PLAIN")
   put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")

   environment.config.propertyOrNull("kafka.username")?.getString()?.let { username ->
      environment.config.propertyOrNull("kafka.password")?.getString()?.let { password ->
         put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";")
      }
   }

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

