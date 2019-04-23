package no.nav.helse.spade.login

import arrow.core.*
import com.auth0.jwk.*
import com.auth0.jwt.*
import com.auth0.jwt.algorithms.*
import com.github.kittinunf.fuel.*
import com.github.kittinunf.fuel.core.*
import org.json.simple.*
import org.json.simple.parser.*
import java.security.interfaces.*

fun String.post(params: List<Pair<String, String>>) =
   Try {
      toJson(this.httpPost(params).responseString())
   }.toEither()

fun String.getJson() =
   Try {
      toJson(this.httpGet().responseString())
   }.toEither()

fun verify(token: String, requiredIssuer: String, jwkProvider: JwkProvider) =
   Try {
      val jwk = jwkProvider[JWT.decode(token).keyId]
      val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
      JWT.require(algorithm)
         .withIssuer(requiredIssuer)
         .build()
         .verify(token)
   }.toEither()

private fun toJson(fuelResult: ResponseResultOf<String>): JSONObject {
   val (request, response, result) = fuelResult
   return when (response.statusCode) {
      200-> JSONParser().parse(result.component1()) as JSONObject
      else -> throw Exception("got status ${response.statusCode} from ${request.url.toExternalForm()}")
   }
}
