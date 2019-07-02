package no.nav.helse.spade

val weights1 = listOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
val weights2 = listOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

fun sum(birthNumber: List<Int>, factors: List<Int>) =
   factors.zip(birthNumber) { f, b -> f * b }.reduce { accumulator, element -> accumulator + element }

fun checksum(fødselsnumberNumeric: List<Int>, weigths: List<Int>) =
   (11 - (sum(fødselsnumberNumeric, weigths) % 11)) % 11

fun isValidFødselsnummer(fødselsnummer: String): Boolean {
   if (fødselsnummer.length != 11) {
      return false
   }
   val fødselsnumberNumeric = try {
      fødselsnummer
         .split("")
         .filter(String::isNotEmpty)
         .map(String::toInt)
   } catch (e: Exception) {
      return false
   }

   if (checksum(fødselsnumberNumeric, weights1) != fødselsnumberNumeric[9]) {
      return false
   }

   return checksum(fødselsnumberNumeric, weights2) == fødselsnumberNumeric[10]
}
