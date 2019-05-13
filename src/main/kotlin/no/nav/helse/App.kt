package no.nav.helse

import io.ktor.config.MapApplicationConfig
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.*
import no.nav.helse.spade.spade
import java.util.concurrent.TimeUnit

@KtorExperimentalAPI
fun main() {
   val env = Environment()

   embeddedServer(Netty, createApplicationEnvironment(env)).let { app ->
      app.start(wait = false)

      Runtime.getRuntime().addShutdownHook(Thread {
         app.stop(5, 60, TimeUnit.SECONDS)
      })
   }
}

@KtorExperimentalAPI
fun createApplicationEnvironment(env: Environment) = applicationEngineEnvironment {
   env.configureApplicationEnvironment(this)

   connector {
      port = 8080
   }

   module {
      spade()
   }
}

@KtorExperimentalAPI
fun Environment.configureApplicationEnvironment(builder: ApplicationEngineEnvironmentBuilder) = builder.apply {
   with (config as MapApplicationConfig) {
      put("kafka.app-id", "spade-v1")
      put("kafka.store-name", "sykepenger-state-store")
      put("kafka.bootstrap-servers", bootstrapServersUrl)
      kafkaUsername?.let { put("kafka.username", it) }
      kafkaPassword?.let { put("kafka.password", it) }

      navTruststorePath?.let { put("kafka.truststore-path", it) }
      navTruststorePassword?.let { put("kafka.truststore-password", it) }

      put("oidcConfigUrl", oidcConfigUrl)
      put("clientId", appId)
   }
}

