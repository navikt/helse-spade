package no.nav.helse.spade.behandlinger

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.Feilårsak
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.slf4j.LoggerFactory
import java.time.LocalDate

class KafkaBehandlingerRepository(stream: BehandlingerStream) {

   private val stateStore by lazy {
      stream.store()
   }

   companion object {
      private val log = LoggerFactory.getLogger(KafkaBehandlingerRepository::class.java)
   }

   fun getBehandlingerForAktør(aktørId: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.get(aktørId)?.let {
         Either.Right(it)
      } ?: Either.Left(Feilårsak.IkkeFunnet)
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   fun getBehandlingerForSøknad(søknadId: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.all().asSequence().filter { keyval ->
         keyval.value.any {
            it.has("originalSøknad") && it.path("originalSøknad").has("id")
               && it.path("originalSøknad").get("id").textValue() == søknadId
         }
      }.let {
         it
      }.flatMap {
         it.value.asSequence()
      }.toList().let {
         if (it.isEmpty()) {
            Feilårsak.IkkeFunnet.left()
         } else {
            it.right()
         }
      }
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   fun getBehandlingerForPeriode(fom: String, tom: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.all().asSequence().filter { keyval ->
         keyval.value.any {
            it.has("avklarteVerdier") && it.path("avklarteVerdier").has("medlemsskap")
               && it.path("avklarteVerdier").path("medlemsskap").has("vurderingstidspunkt")
               && LocalDate.parse(it.path("avklarteVerdier").path("medlemsskap").get("vurderingstidspunkt").textValue().substring(0, 10)) >= (LocalDate.parse(fom))
               && LocalDate.parse(it.path("avklarteVerdier").path("medlemsskap").get("vurderingstidspunkt").textValue().substring(0, 10)) <= (LocalDate.parse(tom))
         }
      }.let {
         it
      }.flatMap {
         it.value.asSequence()
      }.toList().let {
         if (it.isEmpty()) {
            Feilårsak.IkkeFunnet.left()
         } else {
            it.right()
         }
      }
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }
}
