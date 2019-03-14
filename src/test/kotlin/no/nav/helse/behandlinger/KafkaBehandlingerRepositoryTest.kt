package no.nav.helse.behandlinger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import no.nav.helse.Either
import no.nav.helse.Feilårsak
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

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

        when (actual) {
            is Either.Right -> {
                assertEquals(1, actual.right.size)
                assertEquals("Hello, World", actual.right[0].textValue())
            }
            is Either.Left -> fail { "Expected Either.Right to be returned" }
        }
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
            when (either) {
                is Either.Left -> assertEquals(expected, either.left)
                is Either.Right -> fail { "Expected Either.Left to be returned" }
            }
}
