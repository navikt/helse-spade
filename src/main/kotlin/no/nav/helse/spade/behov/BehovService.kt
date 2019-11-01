package no.nav.helse.spade.behov

class BehovService(private val repository: KafkaBehovRepository) {

   fun getGodkjenningsbehovForAktør(aktørId: String) = repository.getBehovForAktør(aktørId)

   fun getGodkjenningsbehovForPeriode(fom: String, tom: String) = repository.getBehovForPeriode(fom, tom)
}
