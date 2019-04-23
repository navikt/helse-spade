package no.nav.helse.login

import com.auth0.jwk.*
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.*
import com.nimbusds.jose.jwk.*
import com.nimbusds.jwt.*
import no.nav.helse.spade.login.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.*

class JwtVerificationTest {

   private val requiredIssuer = "da issuah"

   @Test
   fun validPublicKeyValidates() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_valid.json"))
      verify(jwt, requiredIssuer, jwkProvider).fold(
         { fail(it) }, { assertTrue(it.claims.containsKey("iss")) }
      )
   }

   @Test
   fun doesntValidateIfKeyIsTamperedWith() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_invalid.json"))
      verify(jwt, requiredIssuer, jwkProvider).fold(
         { /* all is well */ }, { fail("shouldn't have validated") }
      )
   }

   private fun signedJwt(): String {
      val jwkJson = File(Unit.javaClass.classLoader.getResource("jwt/keypair.json").toURI()).readText()
      val jwk = JWK.parse(jwkJson)
      val signer = RSASSASigner(jwk as RSAKey)
      val jwtHeader = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).customParam("iss", "da issuah").build()
      val claims = JWTClaimsSet.Builder().issuer(requiredIssuer).build()
      val signedJwt = SignedJWT(jwtHeader, claims)

      signedJwt.sign(signer)

      return signedJwt.serialize()
   }

}
