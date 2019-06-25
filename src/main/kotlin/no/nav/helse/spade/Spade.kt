package no.nav.helse.spade

import com.auth0.jwk.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.datatype.jsr310.*
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.features.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.util.*
import no.nav.helse.http.*
import no.nav.helse.nais.*
import no.nav.helse.spade.behandlinger.*
import no.nav.helse.spade.feedback.*
import org.apache.kafka.clients.*
import org.apache.kafka.common.config.*
import org.apache.kafka.streams.*
import org.apache.kafka.streams.errors.*
import org.slf4j.*
import org.slf4j.event.*
import java.io.*
import java.net.*
import java.util.*
import javax.sql.*

private val authorizedUsers = listOf("S150563", "T149391", "E117646", "S151395", "H131243", "T127350", "S122648", "G153965")

private val auditLog = LoggerFactory.getLogger("auditLogger")

@KtorExperimentalAPI
fun Application.spade(database: DataSource) {
   log.info("Starting spade with database $database")
   val idProvider = environment.config.property("oidcConfigUrl").getString()
      .getJson()
      .fold(
         { throw it },
         { it }
      )

   val jwkProvider = JwkProviderBuilder(URL(idProvider["jwks_uri"].toString())).build()
   val stream = BehandlingerStream(streamConfig(), environment.config.property("kafka.store-name").getString())

   environment.monitor.subscribe(ApplicationStopping) {
      stream.stop()
   }

   install(Authentication) {
      jwt {
         verifier(jwkProvider, idProvider["issuer"].toString())
         realm = environment.config.propertyOrNull("ktor.application.id")?.getString() ?: "Application"
         validate { credentials ->
            if (credentials.payload.getClaim("NAVident").asString() in authorizedUsers &&
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

