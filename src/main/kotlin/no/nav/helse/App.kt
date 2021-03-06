package no.nav.helse

import io.ktor.config.MapApplicationConfig
import io.ktor.server.engine.ApplicationEngineEnvironmentBuilder
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
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
      put("kafka.app-id", "spade-v6")
      put("kafka.store-name", "state-store-v1")
      put("kafka.bootstrap-servers", bootstrapServersUrl)
      serviceUsername?.let { put("service.username", it) }
      servicePassword?.let { put("service.password", it) }

      navTruststorePath?.let { put("kafka.truststore-path", it) }
      navTruststorePassword?.let { put("kafka.truststore-password", it) }

      put("oidcConfigUrl", oidcConfigUrl)
      put("clientId", clientId)
      put("requiredGroup", requiredGroup)
   }
}

