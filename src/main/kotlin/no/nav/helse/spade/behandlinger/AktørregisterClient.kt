package no.nav.helse.spade.behandlinger

import arrow.core.Either
import com.github.kittinunf.fuel.httpGet
import no.nav.helse.Feilårsak
import no.nav.helse.spade.StsClient
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.*

private val log = LoggerFactory.getLogger(AktørregisterClient::class.java)

class AktørregisterClient(
   val baseUrl: String,
   val stsClient: StsClient
) {

   fun hentAktørId(ident: String): Either<Feilårsak, String> {
      log.info("lookup gjeldende identer with ident=$ident")

      val bearer = stsClient.token()

      val (_, _, result) = "$baseUrl/api/v1/identer?gjeldende=true".httpGet()
         .header(mapOf(
            "Authorization" to "Bearer $bearer",
            "Accept" to "application/json",
            "Nav-Call-Id" to UUID.randomUUID().toString(),
            "Nav-Consumer-Id" to "spade",
            "Nav-Personidenter" to ident
         ))
         .responseString()

      return result.fold(
         success = { responseString ->
            mapResponse(responseString, ident)
         },
         failure = { error ->
            log.info("oppslag feilet: $error")
            Either.left(Feilårsak.AktørIdIkkeFunnet)
         }
      )
   }

}

fun mapResponse(response: String, ident: String): Either<Feilårsak, String> {
   val identResponse = JSONObject(response).getJSONObject(ident)

   val identer = identResponse.getJSONArray("identer")

   return if (identer == null || identer.isEmpty) {
      log.debug("lookup failed: '${identResponse.getString("feilmelding")}'")
      Either.Left(Feilårsak.AktørIdIkkeFunnet)
   } else {
      Either.Right(
         identer.map {
            it as JSONObject
         }.filter {
            it.getString("identgruppe") == "AktoerId"
         }.map {
            it.getString("ident")
         }.first()
      )
   }
}
