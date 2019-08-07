package no.nav.helse

data class Environment(
   val bootstrapServersUrl: String = getEnvVar("KAFKA_BOOTSTRAP_SERVERS"),
   val navTruststorePath: String? = getOptionalEnvVar("NAV_TRUSTSTORE_PATH"),
   val navTruststorePassword: String? = getOptionalEnvVar("NAV_TRUSTSTORE_PASSWORD"),
   val serviceUsername: String? = getOptionalEnvVar("SERVICEUSER_USERNAME"),
   val servicePassword: String? = getOptionalEnvVar("SERVICEUSER_PASSWORD"),

   val oidcConfigUrl: String = getEnvVar("OIDC_CONFIG_URL"),
   val clientId: String = getEnvVar("CLIENT_ID"),
   val requiredGroup: String = getEnvVar("REQUIRED_GROUP")
)

private fun getEnvVar(varName: String) = getOptionalEnvVar(varName) ?: throw Exception("mangler verdi for $varName")

private fun getOptionalEnvVar(varName: String): String? = System.getenv(varName)

