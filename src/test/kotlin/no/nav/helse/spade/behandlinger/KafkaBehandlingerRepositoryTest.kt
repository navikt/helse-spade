package no.nav.helse.spade.behandlinger

import arrow.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.*
import no.nav.helse.*
import no.nav.helse.serde.defaultObjectMapper
import org.apache.kafka.streams.errors.*
import org.apache.kafka.streams.state.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class KafkaBehandlingerRepositoryTest {

   @Test
   fun `skal svare riktig data når aktørId finnes i state store med kun én behandling etter 'tidligstBehandlet'-dato`() {
      val aktørId = "1234567890123"

      val streamMock = mockk<BehandlingerStream>()
      val storeMock = mockk<ReadOnlyKeyValueStore<String, List<JsonNode>>>()
      val søknad = "/behandling_ok.json".readResource()
      val json = defaultObjectMapper.readValue(søknad, JsonNode::class.java)
      val jsonWithLaterVurderingstidpunkt = with(json) {
         val objectNode: ObjectNode = json.deepCopy()
         objectNode.with("avklarteVerdier").with("medlemsskap").put("vurderingstidspunkt", "2019-07-01")
         objectNode
      }

      every {
         streamMock.store()
      } returns storeMock

      every {
         storeMock.get(eq(aktørId))
      } returns listOf(json, jsonWithLaterVurderingstidpunkt)

      val actual = KafkaBehandlingerRepository(streamMock)
         .getBehandlingerForAktør(aktørId)

      actual.fold(
         { throw Exception("expected an Either.right") },
         { right -> assertEquals(1, right.size) }
      )
   }

   @Test
   fun `skal svare med feil når aktørId ikke finnes i state store`() {
      val aktørId = "123456789"

      val streamMock = mockk<BehandlingerStream>()
      val storeMock = mockk<ReadOnlyKeyValueStore<String, List<JsonNode>>>()

      every {
         streamMock.store()
      } returns storeMock

      every {
         storeMock.get(eq(aktørId))
      } returns null

      assertFeilårsak(Feilårsak.IkkeFunnet, KafkaBehandlingerRepository(streamMock)
         .getBehandlingerForAktør(aktørId))
   }

   @Test
   fun `skal svare med riktig feilårsak når state store er utilgjengelig`() {
      val aktørId = "123456789"

      val mock = mockk<BehandlingerStream>()

      every {
         mock.store()
      } throws InvalidStateStoreException("state store is unavailable")

      assertFeilårsak(Feilårsak.MidlertidigUtilgjengelig, KafkaBehandlingerRepository(mock)
         .getBehandlingerForAktør(aktørId))
   }

   @Test
   fun `skal svare med riktig feilårsak ved andre feil`() {
      val aktørId = "123456789"

      val mock = mockk<BehandlingerStream>()

      every {
         mock.store()
      } throws Exception("unknown error")

      assertFeilårsak(Feilårsak.UkjentFeil, KafkaBehandlingerRepository(mock)
         .getBehandlingerForAktør(aktørId))
   }

   private fun <T> assertFeilårsak(expected: Feilårsak, either: Either<Feilårsak, T>) =
      either.fold(
         { left -> assertEquals(expected, left) },
         { throw Exception("expected an Either.left") }
      )

   private fun String.readResource() = object {}.javaClass.getResource(this)?.readText(Charsets.UTF_8)
      ?: fail { "did not find <$this>" }
}
