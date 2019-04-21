package no.nav.helse.login

import com.github.tomakehurst.wiremock.*
import com.github.tomakehurst.wiremock.client.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.*
import com.github.tomakehurst.wiremock.http.*
import no.nav.helse.spade.login.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class LoginComponentTest {

   companion object {
      val server: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

      @BeforeAll
      @JvmStatic
      fun start() {
         server.start()
      }


      @AfterAll
      @JvmStatic
      fun stop() {
         server.stop()
      }
   }

   @BeforeEach
   fun configure() {
      val client = WireMock.create().port(server.port()).build()
      configureFor(client)
      client.resetMappings()
   }

   @Test
   fun successfullGetRequestIsARight() {
      stubFor(get(urlEqualTo("/success"))
         .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"aaa": "bbb"}""")))
      val response = "${server.baseUrl()}/success".getJson()
      assertTrue(response.isRight())
   }

   @Test
   fun failedGetRequestIsALeft() {
      stubFor(get(urlEqualTo("/fault"))
         .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)))
      val response = "${server.baseUrl()}/fault".getJson()
      assertTrue(response.isLeft())
   }

   @Test
   fun successfullPostRequestIsARight() {
      stubFor(post(urlEqualTo("/success"))
         .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"aaa": "bbb"}""")))
      val response = "${server.baseUrl()}/success".post(emptyList())
      assertTrue(response.isRight())
   }

   @Test
   fun failedPostRequestIsALeft() {
      stubFor(post(urlEqualTo("/fault"))
         .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)))
      val response = "${server.baseUrl()}/fault".post(emptyList())
      assertTrue(response.isLeft())
   }

}
