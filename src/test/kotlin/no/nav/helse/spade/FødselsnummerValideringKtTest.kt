package no.nav.helse.spade

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FødselsnummerValideringKtTest {

   private val gyldigFødselsnummer = "02010171337"

   @Test
   fun `Gyldige fødselsnumre`() {
      assertTrue(isValidFødselsnummer(gyldigFødselsnummer))
      listOf(
         "02010171175",
         "02010170977",
         "02010170705",
         "02010170543",
         "02010170381",
         "02010169731",
         "02010169308",
         "02010169146",
         "02010168948",
         "02010168786",
         "02010168514",
         "02010168352",
         "02010168190",
         "02010167992",
         "02010167720"
      ).map { assertTrue(isValidFødselsnummer(it), "$it should be valid fødselsnummer") }
   }

   @Test
   fun `Ugyldig fødselsnummer`() {
      assertFalse(isValidFødselsnummer(gyldigFødselsnummer.take(10).plus("8")))
   }

   @Test
   fun `Annen lengde enn 11 er ugyldig`() {
      assertFalse(isValidFødselsnummer(gyldigFødselsnummer.take(10)))
      assertFalse(isValidFødselsnummer(gyldigFødselsnummer.replace(gyldigFødselsnummer.take(1), " ")))
      assertFalse(isValidFødselsnummer(gyldigFødselsnummer.plus("1")))
   }

   @Test
   fun `Tom streng er ikke gyldig` () {
      assertFalse(isValidFødselsnummer(""))
   }
}
