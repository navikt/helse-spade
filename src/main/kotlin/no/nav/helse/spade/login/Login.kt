package no.nav.helse.spade.login

import arrow.core.*
import arrow.core.extensions.either.monad.*
import com.auth0.jwk.*
import com.auth0.jwt.*
import com.auth0.jwt.algorithms.*
import com.github.kittinunf.fuel.*
import com.github.kittinunf.fuel.core.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.Parameters
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import org.json.simple.*
import org.json.simple.parser.*
import java.net.*
import java.security.interfaces.*

fun Route.login(oidcInfo: OidcInfo) {
   val oidcConfig = oidcInfo.configUrl.getJson().fold(
      { throwable -> throw throwable },
      { it }
   )
   val jwkProvider = UrlJwkProvider(URL(oidcConfig["jwks_uri"]?.toString() ?: ""))

   get("/login") {
      val authUrl = (oidcConfig["authorization_endpoint"]?.toString() ?: "") +
         "?client_id=${oidcInfo.clientId}" +
         "&response_type=id_token code" +
         "&redirect_uri=${myBaseUrl(call.request)}/callback" +
         "&scope=openid" +
         "&response_mode=form_post" +
         "&nonce=abcdef"
      call.respondRedirect(authUrl)
   }

   post("/callback") {
      val params = call.receiveParameters()
      binding<Throwable, String> {
         wasRequestSuccessful(params)
         verifyJWT(params["id_token"] ?: "", oidcInfo.requiredIssuer, oidcInfo.clientId, jwkProvider)
         val (tokens) = fireTokenRequest(
            oidcConfig["token_endpoint"]?.toString() ?: "",
            oidcInfo.clientId,
            oidcInfo.clientSecret,
            params["code"] ?: "",
            "${myBaseUrl(call.request)}/callback")
         tokens["id_token"]?.toString() ?: "missing_token"
      }.fold(
         { throwable -> call.respond(HttpStatusCode.BadRequest, throwable.message ?: "unknown error") },
         { call.respond(it) }
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

fun String.post(params: List<Pair<String, String>>) =
   Try {
      toJson(this.httpPost(params).responseString())
   }.toEither()

fun String.getJson() =
   Try {
      toJson(this.httpGet().responseString())
   }.toEither()

private fun toJson(fuelResult: ResponseResultOf<String>): JSONObject {
   val (request, response, result) = fuelResult
   return when (response.statusCode) {
      200  -> JSONParser().parse(result.component1()) as JSONObject
      else -> throw Exception("got status ${response.statusCode} from ${request.url.toExternalForm()}.")
   }
}

private fun myBaseUrl(req: ApplicationRequest) =
   "${req.origin.scheme}://${req.host()}:${req.port()}"

private fun wasRequestSuccessful(params: Parameters) =
   Try {
      params["error"]?.let {
         throw RuntimeException(params["error_description"])
      }
   }.toEither()

private fun fireTokenRequest(url: String, clientId: String, clientSecret: String, code: String, redirectUrl: String) =
   url.post(listOf(
         "client_id" to clientId,
         "scope" to "https://graph.microsoft.com/user.read",
         "code" to code,
         "redirect_uri" to redirectUrl,
         "grant_type" to "authorization_code",
         "client_secret" to clientSecret
      )
   )

data class OidcInfo(val configUrl: String, val clientId: String, val requiredIssuer: String, val clientSecret: String)

