package no.nav.helse

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.application.Application
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.behandlinger.BehandlingerService
import no.nav.helse.behandlinger.BehandlingerStream
import no.nav.helse.behandlinger.KafkaBehandlingerRepository
import no.nav.helse.behandlinger.behandlinger
import no.nav.helse.nais.nais
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.slf4j.event.Level
import java.io.File
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

private val collectorRegistry = CollectorRegistry.defaultRegistry

fun main() {
    val appId = "spade-v1"
    val storeName = "sykepenger-state-store"

    val env = Environment()

    val app = embeddedServer(Netty, 8080) {
        val jwkProvider = JwkProviderBuilder(URL(env.jwksUrl))
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()

        DefaultExports.initialize()

        val props = Properties().apply {
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, env.bootstrapServersUrl)
            put(StreamsConfig.APPLICATION_ID_CONFIG, appId)

            put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndFailExceptionHandler::class.java)

            put(SaslConfigs.SASL_MECHANISM, "PLAIN")
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
            put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${env.kafkaUsername}\" password=\"${env.kafkaPassword}\";")

            try {
                put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, File(env.navTruststorePath).absolutePath)
                put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, env.navTruststorePassword)
                log.info("Configured '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location ")
            } catch (ex: Exception) {
                log.error("Failed to set '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location", ex)
            }
        }

        val behandlingerService = BehandlingerService(KafkaBehandlingerRepository(BehandlingerStream(props, storeName)))

        spade(jwkProvider, env.jwtIssuer, behandlingerService)
    }

    app.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread {
        app.stop(5, 60, TimeUnit.SECONDS)
    })
}

fun Application.spade(jwkProvider: JwkProvider, jwtIssuer: String, behandlingerService: BehandlingerService) {
    install(CallId) {
        header("Nav-Call-Id")

        generate { UUID.randomUUID().toString() }
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

        }
    }

    install(Authentication) {
        jwt {
            verifier(jwkProvider, jwtIssuer)
            realm = "Helse Spade"
            validate { credentials ->
                JWTPrincipal(credentials.payload)
            }
        }
    }

    routing {
        authenticate {
            behandlinger(behandlingerService)
        }

        nais(collectorRegistry)
    }
}


