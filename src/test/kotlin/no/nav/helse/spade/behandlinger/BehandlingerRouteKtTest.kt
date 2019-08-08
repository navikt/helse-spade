package no.nav.helse.spade.behandlinger

import arrow.core.Left
import arrow.core.Right
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.routing.routing
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.helse.Feilårsak
import no.nav.helse.spade.StsClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@KtorExperimentalAPI
class BehandlingerRouteKtTest {

   private val behandlingerService = mockk<BehandlingerService>()
   private val stsClient = mockk<StsClient>()
   private val aktørregisterClient = mockk<AktørregisterClient>()

   @BeforeEach
   fun setup() {
      every { stsClient.token() } returns "token"
      every { behandlingerService.getBehandlingerForAktør(any()) } returns Right(listOf(mockk(relaxed = true)))
   }

   @Test
   fun `Uses input as aktørId if it is not SSN`() {
      withTestApplication(module) {
         val notSsn = "123"

         with(handleRequest(HttpMethod.Get, "api/behandlinger/$notSsn")) {
            assertThat(response.status()).isEqualTo(HttpStatusCode.OK)
            verify(exactly = 1) { behandlingerService.getBehandlingerForAktør(eq(notSsn)) }
         }
      }
   }

   @Test
   fun `Gets and uses aktørId if input parses as SSN`() {
      withTestApplication(module) {
         val aktørId = "123"
         every { aktørregisterClient.hentAktørId(any()) } returns Right(aktørId)

         val ssn = "02010168948"

         with(handleRequest(HttpMethod.Get, "api/behandlinger/$ssn")) {
            assertThat(response.status()).isEqualTo(HttpStatusCode.OK)
            verify { behandlingerService.getBehandlingerForAktør(eq(aktørId)) }
         }
      }
   }

   @Test
   fun `Returns error if aktørId is not available`() {
      withTestApplication(module) {
         every { aktørregisterClient.hentAktørId(any()) } returns Left(Feilårsak.AktørIdIkkeFunnet)

         val ssn = "02010168948"

         with(handleRequest(HttpMethod.Get, "api/behandlinger/$ssn")) {
            assertThat(response.status()).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.content).contains("Finner ikke")
            verify { behandlingerService wasNot Called }
         }
      }
   }

   private val module: Application.() -> Unit = {
      install(ContentNegotiation) {
         jackson { }
      }

      routing {
         behandlinger(behandlingerService, aktørregisterClient)
      }
   }

}
