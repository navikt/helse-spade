package no.nav.helse.spade.vedtak

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import no.nav.helse.serde.defaultObjectMapper
import no.nav.helse.spade.godkjenning.matcherPåSakskompleksId
import no.nav.helse.spade.godkjenning.opprettLøsningForBehov
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
   fun `matcher behov på sakskompleksId og type`() {
      val behov = defaultObjectMapper.readTree(VedtakRouteTest::class.java.getResourceAsStream("/behov/behovSomListe.json")) as ObjectNode
      val speilForespørsel = defaultObjectMapper.readTree("""{"sakskompleksId":"sakskompleks-uuid", "aktørId": "CHANGEME", "saksbehandlerIdent":"Z999999", "godkjent": true}""")
      assertTrue(matcherPåSakskompleksId(behov, speilForespørsel))

      behov.set("@behov", JsonNodeFactory.instance.arrayNode().add("Et annet behov"))
      assertFalse(matcherPåSakskompleksId(behov, speilForespørsel))
   }
}
