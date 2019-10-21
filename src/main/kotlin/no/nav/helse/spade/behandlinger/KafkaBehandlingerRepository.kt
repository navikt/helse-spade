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

   fun getBehandlingerForPeriode(fom: String, tom: String): Either<Feilårsak, List<BehandlingSummary>> = try {
      log.info("Iterate through ${stateStore.approximateNumEntries()} entries")
      val start = System.currentTimeMillis()
      stateStore.all().use { iterator ->
         log.info("Time passed before filtering: ${System.currentTimeMillis() - start} ms")
         val v = iterator.asSequence().flatMap { it.value.asSequence() }.filter { node ->
            getVurderingstidspunkt(node)?.let { isDateInPeriod(it, fom, tom) } == true
         }
         log.info("Time passed after filtering: ${System.currentTimeMillis() - start} ms")
            return v.map { mapToDto(it) }.toList().let {
            val time = System.currentTimeMillis() - start
            log.info("Fetching behandlinger took $time ms")
            if (it.isEmpty()) {
               Feilårsak.IkkeFunnet.left()
            } else {
               it.right()
            }
         }
      }
   } catch (err: InvalidStateStoreException) {
      log.info("state store is not available yet", err)
      Either.Left(Feilårsak.MidlertidigUtilgjengelig)
   } catch (err: Exception) {
      log.error("unknown error while fetching state store", err)
      Either.Left(Feilårsak.UkjentFeil)
   }

   private fun mapToDto(node: JsonNode): BehandlingSummary {
      val behandlingsId = node.get("behandlingsId")?.textValue() ?: throw Exception("Field 'behandlingsId' not found in behandling")
      val vurderingstidspunkt = node.path("avklarteVerdier").path("medlemsskap").get("vurderingstidspunkt").textValue()
      if (node.has("originalSøknad")) {
         val aktorId = node.path("originalSøknad").get("aktorId").textValue()
         val fom = node.path("originalSøknad").get("fom").textValue()
         val tom = node.path("originalSøknad").get("tom").textValue()
         return BehandlingSummary(behandlingsId, aktorId, fom, tom, vurderingstidspunkt)
      } else throw Exception("Field 'originalSøknad' not found in behandling with behandlingsId: $behandlingsId")
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
