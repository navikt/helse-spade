package no.nav.helse

data class Environment(
   val bootstrapServersUrl: String = getEnvVar("KAFKA_BOOTSTRAP_SERVERS"),
   val kafkaUsername: String? = getOptionalEnvVar("KAFKA_USERNAME"),
   val kafkaPassword: String? = getOptionalEnvVar("KAFKA_PASSWORD"),
   val navTruststorePath: String? = getOptionalEnvVar("NAV_TRUSTSTORE_PATH"),
   val navTruststorePassword: String? = getOptionalEnvVar("NAV_TRUSTSTORE_PASSWORD"),

   val oidcConfigUrl: String = getEnvVar("OIDC_CONFIG_URL"),
   val clientId: String = getEnvVar("CLIENT_ID"),

   val dbVaultMountPath: String? = getOptionalEnvVar("DB_CREDS_PATH")
)

private fun getEnvVar(varName: String) = getOptionalEnvVar(varName) ?: throw Exception("mangler verdi for $varName")

private fun getOptionalEnvVar(varName: String): String? = System.getenv(varName)

