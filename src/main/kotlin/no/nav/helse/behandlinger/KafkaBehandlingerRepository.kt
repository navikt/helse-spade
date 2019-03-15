package no.nav.helse.behandlinger

import no.nav.helse.Either
import no.nav.helse.Feilårsak
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.slf4j.LoggerFactory

class KafkaBehandlingerRepository(stream: BehandlingerStream) {

    private val stateStore by lazy {
        stream.store()
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaBehandlingerRepository::class.java)
    }

    fun getBehandlingerForAktør(aktørId: String) = try {
        stateStore.get(aktørId)?.let {
            Either.Right(it)
        } ?: Either.Left(Feilårsak.IkkeFunnet)
    } catch (err: InvalidStateStoreException) {
        log.info("state store is not available yet", err)
        Either.Left(Feilårsak.MidlertidigUtilgjengelig)
    } catch (err: Exception) {
        log.error("unknown error while fetching state store", err)
        Either.Left(Feilårsak.UkjentFeil)
    }

    fun getListOfKeys(): Either<Feilårsak, List<String>> = try {
        var resultsList = emptyList<String>()
        stateStore.all().forEach {
            resultsList = resultsList.plus(it.key)
        }
        Either.Right(resultsList)
    } catch (err: InvalidStateStoreException) {
        log.info("state store is not available yet", err)
        Either.Left(Feilårsak.MidlertidigUtilgjengelig)
    } catch (err: Exception) {
        log.error("unknown error while fetching state store", err)
        Either.Left(Feilårsak.UkjentFeil)
    }
}
