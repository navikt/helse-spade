package no.nav.helse.spade.behandlinger

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import no.nav.helse.Feilårsak
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.slf4j.LoggerFactory

class KafkaBehandlingerRepository(stream: BehandlingerStream) {

   private val stateStore by lazy {
      stream.store()
   }

   companion object {
      private val log = LoggerFactory.getLogger(KafkaBehandlingerRepository::class.java)
   }

   fun getBehandlingerForAktør(aktørId: String) = try {
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

   fun getBehandlingerForSøknad(søknadId: String) = try {
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
}
