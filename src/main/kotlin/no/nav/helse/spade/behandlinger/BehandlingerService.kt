package no.nav.helse.spade.behandlinger

class BehandlingerService(private val repository: KafkaBehandlingerRepository) {

   fun getBehandlingerForAktør(aktørId: String) = repository.getBehandlingerForAktør(aktørId)

   fun getBehandlingerForPeriode(fom: String, tom: String) = repository.getBehandlingerForPeriode(fom, tom)
}
