package no.nav.helse.spade.behandlinger

class BehandlingerService(private val repository: KafkaBehandlingerRepository) {

   fun getBehandlingerForAktør(aktørId: String) = repository.getBehandlingerForAktør(aktørId)

   fun getBehandlingerForSøknad(søknadId: String) = repository.getBehandlingerForSøknad(søknadId)
}
