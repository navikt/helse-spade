package no.nav.helse.behandlinger

class BehandlingerService(private val repository: KafkaBehandlingerRepository) {

    fun getBehandlingerForAktør(aktørId: String) = repository.getBehandlingerForAktør(aktørId)
}
