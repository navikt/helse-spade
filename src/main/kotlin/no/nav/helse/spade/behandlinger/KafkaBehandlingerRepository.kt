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

   private val tidligsteBehandlingsdato: LocalDate = LocalDate.of(2019, 6, 1)

   companion object {
      private val log = LoggerFactory.getLogger(KafkaBehandlingerRepository::class.java)
   }

   fun getBehandlingerForAktør(aktørId: String): Either<Feilårsak, List<JsonNode>> = try {
      stateStore.get(aktørId)?.let { list ->
         val behandlingerEtterDato = list.filter { node ->
            getVurderingstidspunkt(node)?.let { LocalDate.parse(it) >= tidligsteBehandlingsdato } ?: false
         }
         if (behandlingerEtterDato.isEmpty()) {
            Either.Left(Feilårsak.IkkeFunnet)
         } else {
            Either.Right(behandlingerEtterDato)
         }
      } ?: Either.Left(Feilårsak.IkkeFunnet)
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   fun getBehandlingerForPeriode(fom: String, tom: String): Either<Feilårsak, List<JsonNode>> = try {
      val initialList: MutableList<JsonNode> = mutableListOf()
      stateStore.all().asSequence().fold(initialList) { acc, keyval ->
         keyval.value.forEach { node ->
            if (getVurderingstidspunkt(node)?.let { isDateInPeriod(it, fom, tom) } == true) acc.add(node)
         }
         acc
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

   private fun getVurderingstidspunkt(node: JsonNode): String? {
      if (node.has("avklarteVerdier") && node.path("avklarteVerdier").has("medlemsskap")
         && node.path("avklarteVerdier").path("medlemsskap").has("vurderingstidspunkt")) {
         val vurderingstidspunkt = node.path("avklarteVerdier").path("medlemsskap").get("vurderingstidspunkt").textValue()
         val vurderingstidspunktWithoutTimestamp = vurderingstidspunkt.substring(0, 10)
         return vurderingstidspunktWithoutTimestamp
      }
      return null
   }

   private fun isDateInPeriod(dateString: String, fom: String, tom: String): Boolean =
      LocalDate.parse(dateString) >= LocalDate.parse(fom) && LocalDate.parse(dateString) <= (LocalDate.parse(tom))
}
