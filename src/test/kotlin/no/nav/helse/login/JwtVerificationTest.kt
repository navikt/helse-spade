package no.nav.helse.login

import com.auth0.jwk.*
import com.auth0.jwt.JWT
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
   private val requiredAudience = "da audienze"

   @Test
   fun validatesIfAllIsGood() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_valid.json"))
      verifyJWT(jwt, requiredIssuer, requiredAudience, jwkProvider).fold(
         { fail(it) }, { assertNotNull(JWT.decode(it)) }
      )
   }

   @Test
   fun signatureMustBeValid() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_invalid.json"))
      verifyJWT(jwt, requiredIssuer, requiredAudience, jwkProvider).fold(
         { /* all is well */ }, { fail("shouldn't have validated") }
      )
   }

   @Test
   fun audienceMustBeValid() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_invalid.json"))
      verifyJWT(jwt, requiredIssuer, "bogus audience", jwkProvider).fold(
         { /* all is well */ }, { fail("shouldn't have validated") }
      )
   }

   @Test
   fun issuerMustBeValid() {
      val jwt = signedJwt()
      val jwkProvider = UrlJwkProvider(Unit.javaClass.classLoader.getResource("jwt/jwks_invalid.json"))
      verifyJWT(jwt, "bogus issuer", requiredAudience, jwkProvider).fold(
         { /* all is well */ }, { fail("shouldn't have validated") }
      )
   }

   private fun signedJwt(): String {
      val jwkJson = File(Unit.javaClass.classLoader.getResource("jwt/keypair.json").toURI()).readText()
      val jwk = JWK.parse(jwkJson)
      val signer = RSASSASigner(jwk as RSAKey)
      val jwtHeader = JWSHeader.Builder(JWSAlgorithm.RS256)
         .keyID(jwk.keyID)
         .build()
      val claims = JWTClaimsSet.Builder()
         .issuer(requiredIssuer)
         .audience(requiredAudience)
         .build()
      val signedJwt = SignedJWT(jwtHeader, claims)

      signedJwt.sign(signer)

      return signedJwt.serialize()
   }

}
