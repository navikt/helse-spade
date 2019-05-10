package no.nav.helse.spade

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.auth.jwt.JWTPrincipal
import io.ktor.auth.jwt.jwt
import io.ktor.auth.principal
import io.ktor.features.*
import io.ktor.jackson.jackson
import io.ktor.request.path
import io.ktor.request.uri
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import no.nav.helse.http.getJson
import no.nav.helse.nais.nais
import no.nav.helse.spade.behandlinger.BehandlingerService
import no.nav.helse.spade.behandlinger.BehandlingerStream
import no.nav.helse.spade.behandlinger.KafkaBehandlingerRepository
import no.nav.helse.spade.behandlinger.behandlinger
import no.nav.helse.spade.login.OidcInfo
import no.nav.helse.spade.login.login
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.net.URL
import java.util.*

private val authorizedUsers = listOf("S150563", "T149391", "E117646", "S151395", "H131243", "T127350", "S122648", "G153965")

private val auditLog = LoggerFactory.getLogger("auditLogger")

@KtorExperimentalAPI
fun Application.spade() {
   val idProvider = ("${environment.config.property("oidcConfigUrl")?.getString()}?appid=${environment.config.property("clientId")?.getString()}")
      .getJson()
      .fold(
         { throwable -> throw throwable },
         { it }
      )

   val jwkProvider = JwkProviderBuilder(URL(idProvider["jwks_uri"].toString())).build()
   val stream = BehandlingerStream(streamConfig(), environment.config.property("kafka.store-name").getString())

   environment.monitor.subscribe(ApplicationStopping) {
      stream.stop()
   }

   install(Authentication) {
      jwt {
         verifier(jwkProvider, environment.config.property("issuer").getString())
         realm = environment.config.propertyOrNull("ktor.application.id")?.getString() ?: "Application"
         validate { credentials ->
            if (credentials.payload.getClaim("NAVident").asString() in authorizedUsers) {
               JWTPrincipal(credentials.payload)
            } else {
               log.info("${credentials.payload.subject} is not authorized to use this app, denying access")
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
      call.request.headers.forEach { name, values -> log.info("$name: -> $values") }
      log.info("Origin ${call.request.origin} -> ${call.response.status().toString()}")
      call.principal<JWTPrincipal>()?.let { principal ->
         log.info("Bruker=\"${principal.payload.getClaim("NAVident").asString()}\" gjør kall mot url=\"${call.request.uri}\"")
         auditLog.info("Bruker=\"${principal.payload.getClaim("NAVident").asString()}\" gjør kall mot url=\"${call.request.uri}\"")
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

      login(
         OidcInfo(idProvider,
            environment.config.property("clientId").getString(),
            environment.config.property("issuer").getString(),
            environment.config.property("clientSecret").getString())
      )
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

