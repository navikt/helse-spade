package no.nav.helse.spade.vedtak

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.godkjenning.matcherPåVedtaksperiodeId
import no.nav.helse.spade.godkjenning.opprettLøsningForBehov
import no.nav.helse.spade.godkjenning.senesteSomMatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VedtakRouteTest {
   @Test
   fun `skal kunne slå sammen et behov og requestbody fra speil`() {
      val behov = defaultObjectMapper.readTree(VedtakRouteTest::class.java.getResourceAsStream("/behov/behov.json"))
      val speilForespørsel = defaultObjectMapper.readTree("""{"behovId":"ea5d644b-ffb9-4c32-bbd9-f93744554d5e", "aktørId": "CHANGEME", "saksbehandlerIdent":"Z999999", "godkjent": true}""")

      val løsning = opprettLøsningForBehov(behov, speilForespørsel)

      assertEquals("GodkjenningFraSaksbehandler", løsning["@behov"]?.asText())
      assertEquals("Z999999", løsning["saksbehandlerIdent"]?.asText())
      val godkjent = løsning["@løsning"]?.get("godkjent")
      assertEquals(false, godkjent?.isNull)
      assertEquals(true, godkjent?.asBoolean())
   }

   @Test
   fun `matcher behov på vedtaksperiodeId og type`() {
      val behov = defaultObjectMapper.readTree(VedtakRouteTest::class.java.getResourceAsStream("/behov/behovSomListe.json")) as ObjectNode
      val speilForespørsel = defaultObjectMapper.readTree("""{"vedtaksperiodeId":"vedtaksperiode-uuid", "aktørId": "CHANGEME", "saksbehandlerIdent":"Z999999", "godkjent": true}""")
      assertTrue(matcherPåVedtaksperiodeId(behov, speilForespørsel))
      println("Behov: ${behov.toPrettyString()}")
      behov.set<JsonNode>("@behov", JsonNodeFactory.instance.arrayNode().add("Et annet behov"))
      assertFalse(matcherPåVedtaksperiodeId(behov, speilForespørsel))
   }

   @Test
   fun `besvarer siste behov`() {
      val førsteBehov = defaultObjectMapper.readTree(VedtakRouteTest::class.java.getResourceAsStream("/behov/behovSomListe.json")) as ObjectNode
      val andreBehov = førsteBehov.deepCopy().put("@opprettet", "2019-11-02T08:38:00.728127")
      val sisteBehov = førsteBehov.deepCopy().put("@opprettet", "2019-11-03T08:38:00.728127")
      val behovListe = listOf(andreBehov, sisteBehov, førsteBehov)
      val speilForespørsel = defaultObjectMapper.readTree("""{"vedtaksperiodeId":"vedtaksperiode-uuid", "aktørId": "CHANGEME", "saksbehandlerIdent":"Z999999", "godkjent": true}""")

      assertEquals(sisteBehov, behovListe.senesteSomMatcher(speilForespørsel))
   }
}
