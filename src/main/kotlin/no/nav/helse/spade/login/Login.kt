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

fun Route.login(idpBaseUrl: String, oidcConfigUrl: String, oidcClientId: String, oidcIssuer: String) {
   val oidcConfig = oidcConfigUrl.getJson().fold(
      { throwable -> throw throwable },
      { it }
   )
   val jwkProvider = UrlJwkProvider(URL(oidcConfig["jwks_uri"]?.toString() ?: "ukjent"))

   get("/login") {
      val authUrl = "$idpBaseUrl/authorize" +
         "?client_id=$oidcClientId" +
         "&response_type=id_token code" +
         "&redirect_uri=${buildCallbackUrl(call.request)}" +
         "&scope=openid" +
         "&response_mode=form_post" +
         "&nonce=abcdef"
      call.respondRedirect(authUrl)
   }

   post("/callback") {
      val params = call.receiveParameters()
      binding<Throwable, String> {
         authSuccessful(params)
         val (verifiedIdToken) = verify(params["id_token"] ?: "", oidcIssuer, oidcClientId, jwkProvider)
         verifiedIdToken
      }.fold(
         { throwable -> call.respond(HttpStatusCode.BadRequest, throwable.message ?: "unknown error") },
         { call.respond(it) }
      )
   }
}

fun String.post(params: List<Pair<String, String>>) =
   Try {
      toJson(this.httpPost(params).responseString())
   }.toEither()

fun String.getJson() =
   Try {
      toJson(this.httpGet().responseString())
   }.toEither()

fun verify(token: String, requiredIssuer: String, requiredAudience: String, jwkProvider: JwkProvider) =
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

private fun toJson(fuelResult: ResponseResultOf<String>): JSONObject {
   val (request, response, result) = fuelResult
   return when (response.statusCode) {
      200-> JSONParser().parse(result.component1()) as JSONObject
      else -> throw Exception("got status ${response.statusCode} from ${request.url.toExternalForm()}")
   }
}

private fun buildCallbackUrl(req: ApplicationRequest) =
   "${req.origin.scheme}://${req.host()}:${req.port()}/callback"

private fun authSuccessful(params: Parameters) =
   Try {
      params["error"]?.let {
         throw RuntimeException(params["error_description"])
      }
      params["id_token"]!!
   }.toEither()

