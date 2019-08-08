package no.nav.helse.spade.behandlinger

import arrow.core.Either.Left
import arrow.core.Either.Right
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

internal class AktørregisterClientKtTest {

   @Test
   fun `Test mapping of response to ident`() {
      val ssn = "140819"
      val aktørId = "1892"

      val response = """
         {
            $ssn: {
               "identer": [
                  {
                     "identgruppe": "AktoerId",
                     "ident": "$aktørId"
                  }
               ]
            }
         }
      """

      when (val outcome = mapResponse(response, ssn)) {
         is Right -> {
            assertThat(outcome.b).isEqualTo(aktørId)
         }
         is Left -> {
            fail("well, this failed")
         }
      }
   }
}
