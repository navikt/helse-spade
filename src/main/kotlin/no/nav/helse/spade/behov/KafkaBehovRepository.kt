package no.nav.helse.spade.behov

import arrow.core.Either
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.Feilårsak
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime

class KafkaBehovRepository(stream: BehovConsumer) {

   private val stateStore by lazy {
      stream.store()
   }

   companion object {
      private val log = LoggerFactory.getLogger(KafkaBehovRepository::class.java)
      const val opprettetKey = "@opprettet"
   }

   fun getBehovForAktør(aktørId: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.get(aktørId)?.let { list ->
         return Either.Right(list)
      } ?: Either.Left(Feilårsak.IkkeFunnet)
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   fun getBehovForPeriode(fom: String, tom: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.all().use { iterator ->
         iterator.asSequence()
            .flatMap { it.value.asSequence() }
            .filter { node -> isDateInPeriod(node, fom, tom) }
            .toList().right()
      }
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   private fun isDateInPeriod(node: JsonNode, fom: String, tom: String): Boolean {
      return node[opprettetKey]?.let {
         val behovOpprettet = LocalDateTime.parse(it.asText()).toLocalDate()
         behovOpprettet >= LocalDate.parse(fom) && behovOpprettet <= (LocalDate.parse(tom))
      } ?: false
   }

}
