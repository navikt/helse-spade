package no.nav.helse.spade.behandlinger

import arrow.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.node.*
import io.mockk.*
import no.nav.helse.*
import org.apache.kafka.streams.errors.*
import org.apache.kafka.streams.state.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class KafkaBehandlingerRepositoryTest {

   @Test
   fun `skal svare data når aktørId finnes i state store`() {
      val aktørId = "123456789"

      val streamMock = mockk<BehandlingerStream>()
      val storeMock = mockk<ReadOnlyKeyValueStore<String, List<JsonNode>>>()

      every {
         streamMock.store()
      } returns storeMock

      every {
         storeMock.get(eq(aktørId))
      } returns listOf(TextNode("Hello, World"))

      val actual = KafkaBehandlingerRepository(streamMock)
         .getBehandlingerForAktør(aktørId)

      actual.fold(
         { throw Exception("expected an Either.right") },
         { right -> assertEquals("Hello, World", right[0].textValue()) }

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
}
