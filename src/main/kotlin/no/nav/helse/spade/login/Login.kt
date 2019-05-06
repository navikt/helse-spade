package no.nav.helse.spade.login

import arrow.core.*
import com.auth0.jwk.*
import com.auth0.jwt.*
import com.auth0.jwt.algorithms.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import no.nav.helse.http.*
import org.json.simple.*
import java.net.*
import java.security.interfaces.*

data class OidcInfo(val provider: JSONObject, val clientId: String, val requiredIssuer: String, val clientSecret: String)

fun Route.login(oidcInfo: OidcInfo) {
   val jwkProvider = UrlJwkProvider(URL(oidcInfo.provider["jwks_uri"]?.toString() ?: ""))

   get("/login") {
      val authUrl = (oidcInfo.provider["authorization_endpoint"]?.toString() ?: "") +
         "?client_id=${oidcInfo.clientId}" +
         "&response_type=id_token code" +
         "&redirect_uri=${myBaseUrl(call.request)}/callback" +
         "&scope=openid" +
         "&response_mode=form_post" +
         "&nonce=whatever"
      call.respondRedirect(authUrl)
   }

   post("/callback") {
      val params = call.receiveParameters()

      wasRequestSuccessful(params)
         .flatMap {
            verifyJWT(params["id_token"] ?: "", oidcInfo.requiredIssuer, oidcInfo.clientId, jwkProvider)
         }.flatMap {
            fireTokenRequest(
               oidcInfo.provider["token_endpoint"]?.toString() ?: "",
               oidcInfo,
               params["code"] ?: "",
               "${myBaseUrl(call.request)}/callback")
         }.fold(
            { throwable -> call.respond(HttpStatusCode.BadRequest, throwable.message ?: "unknown error") },
            { call.respond(it["id_token"] ?: "should have been an id token") }
         )
   }
}

fun verifyJWT(token: String, requiredIssuer: String, requiredAudience: String, jwkProvider: JwkProvider) =
   Try {
      val jwk = jwkProvider[JWT.decode(token).keyId]
      val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
      JWT.require(algorithm)
         .withIssuer(requiredIssuer)
         .withAudience(requiredAudience)
         .build()
         .verify(token)
         .token
   }.toEither()

private fun myBaseUrl(req: ApplicationRequest) =
   "${req.origin.scheme}://${req.host()}:${req.port()}"

private fun wasRequestSuccessful(params: Parameters) =
   Try {
      params["error"]?.let {
         throw RuntimeException(params["error_description"])
      }
   }.toEither()

private fun fireTokenRequest(url: String, oidcInfo: OidcInfo, code: String, redirectUrl: String) =
   url.post(listOf(
         "client_id" to oidcInfo.clientId,
         "scope" to "https://graph.microsoft.com/user.read",
         "code" to code,
         "redirect_uri" to redirectUrl,
         "grant_type" to "authorization_code",
         "client_secret" to oidcInfo.clientSecret
      )
   )


