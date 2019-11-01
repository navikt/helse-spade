package no.nav.helse.spade.behov

import arrow.core.*
import com.fasterxml.jackson.databind.*
import io.mockk.*
import no.nav.helse.*
import org.apache.kafka.streams.errors.*
import org.apache.kafka.streams.state.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class KafkaBehovRepositoryTest {

   @Test
   fun `skal svare med feil når aktørId ikke finnes i state store`() {
      val aktørId = "123456789"

      val streamMock = mockk<BehovConsumer>()
      val storeMock = mockk<ReadOnlyKeyValueStore<String, List<JsonNode>>>()

      every {
         streamMock.store()
      } returns storeMock

      every {
         storeMock.get(eq(aktørId))
      } returns null

      assertFeilårsak(Feilårsak.IkkeFunnet, KafkaBehovRepository(streamMock)
         .getBehovForAktør(aktørId))
   }

   @Test
   fun `skal svare med riktig feilårsak når state store er utilgjengelig`() {
      val aktørId = "123456789"

      val mock = mockk<BehovConsumer>()

      every {
         mock.store()
      } throws InvalidStateStoreException("state store is unavailable")

      assertFeilårsak(Feilårsak.MidlertidigUtilgjengelig, KafkaBehovRepository(mock)
         .getBehovForAktør(aktørId))
   }

   @Test
   fun `skal svare med riktig feilårsak ved andre feil`() {
      val aktørId = "123456789"

      val mock = mockk<BehovConsumer>()

      every {
         mock.store()
      } throws Exception("unknown error")

      assertFeilårsak(Feilårsak.UkjentFeil, KafkaBehovRepository(mock)
         .getBehovForAktør(aktørId))
   }

   private fun <T> assertFeilårsak(expected: Feilårsak, either: Either<Feilårsak, T>) =
      either.fold(
         { left -> assertEquals(expected, left) },
         { throw Exception("expected an Either.left") }
      )

   private fun String.readResource() = object {}.javaClass.getResource(this)?.readText(Charsets.UTF_8)
      ?: fail { "did not find <$this>" }
}
