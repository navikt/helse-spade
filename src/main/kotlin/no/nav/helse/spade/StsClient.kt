package no.nav.helse.spade

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.httpGet
import no.nav.helse.serde.defaultObjectMapper
import java.time.LocalDateTime

class StsClient(
   private val baseUrl: String,
   private val username: String,
   private val password: String
) {
   private var cachedOidcToken: Token = Token.emptyToken

   fun token(): String {
      if (cachedOidcToken.shouldRenew()) {
         val (_, _, result) = "$baseUrl/rest/v1/sts/token?grant_type=client_credentials&scope=openid".httpGet()
            .authentication()
            .basic(username, password)
            .header(mapOf("Accept" to "application/json"))
            .response()

         cachedOidcToken = defaultObjectMapper.readValue(result.get(), Token::class.java)
      }

      return cachedOidcToken.accessToken
   }

   @JsonIgnoreProperties(ignoreUnknown = true)
   data class Token(
      @JsonProperty("access_token")
      val accessToken: String,
      @JsonProperty("token_type")
      val type: String,
      @JsonProperty("expires_in")
      val expiresIn: Int
   ) {
      // expire 10 seconds before actual expiry. for great margins.
      private val expirationTime: LocalDateTime = LocalDateTime.now().plusSeconds(expiresIn - 10L)

      fun shouldRenew(): Boolean {
         return expirationTime.isBefore(LocalDateTime.now())
      }
      companion object {
         val emptyToken = Token("", "", -10)
      }
   }
}
