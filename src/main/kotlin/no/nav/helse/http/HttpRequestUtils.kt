package no.nav.helse.http

import arrow.core.*
import com.github.kittinunf.fuel.*
import com.github.kittinunf.fuel.core.*
import org.json.simple.*
import org.json.simple.parser.*

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
