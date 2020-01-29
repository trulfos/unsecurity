package io.unsecurity.auth.auth0.m2m

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}

import cats.effect.Sync
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.{DecodedJWT, RSAKeyProvider}
import io.circe.parser.decode
import io.unsecurity.auth.auth0.oidc.Jwt.JwtHeader
import io.unsecurity.{SecurityContext, UnsecurityOps}
import no.scalabin.http4s.directives.Directive
import okio.ByteString
import org.http4s.headers.Authorization
import org.log4s.getLogger

import scala.util.Try

class Auth0M2MOnBehalfOfSecurityContext[F[_]: Sync, U](lookup: OauthAuthenticatedApplication => F[Option[U]],
                                             issuer: String,
                                             audience: String,
                                             jwkProvider: JwkProvider)
    extends SecurityContext[F, OauthAuthenticatedApplication, U]
    with UnsecurityOps[F] {

  private[this] val log = getLogger

  override def authenticate: Directive[F, OauthAuthenticatedApplication] = {
    for {
      attemptedPath    <- request.path
      bearerRequestAuthToken <- requestAuthBearerToken
      bearerDecodedJWT       <- decodedJWT(bearerRequestAuthToken)
      bearerJwtHeader        <- jwtHeader(bearerDecodedJWT)
      bearerPublicKey        = jwkProvider.get(bearerJwtHeader.kid).getPublicKey.asInstanceOf[RSAPublicKey]
      bearerAlg              = Algorithm.RSA256(createPublicKeyProvider(bearerPublicKey))
      bearerVerifiedToken    <- verifyAccessToken(bearerAlg, bearerRequestAuthToken, attemptedPath)
      bearerJwtToken         <- jwtToken(bearerVerifiedToken)
      _                <- checkExpiration(bearerJwtToken)


      behalfOfRequestAuthToken <- requestAuthOnBehalfOfToken
      behalfOfDecodedJWT       <- decodedJWT(behalfOfRequestAuthToken)
      behalfOfJwtHeader        <- jwtHeader(behalfOfDecodedJWT)
      behalfOfPublicKey        = jwkProvider.get(behalfOfJwtHeader.kid).getPublicKey.asInstanceOf[RSAPublicKey]
      behalfOfAlg              = Algorithm.RSA256(createPublicKeyProvider(behalfOfPublicKey))
      behalfOfVerifiedToken    <- verifyAccessToken(behalfOfAlg, behalfOfRequestAuthToken, attemptedPath)
      behalfOfJwtToken         <- jwtToken(behalfOfVerifiedToken)
      _                        <- checkExpiration(behalfOfJwtToken)

      userProfile      <- extractProfile(behalfOfJwtToken, behalfOfRequestAuthToken)

    } yield {
      userProfile
    }
  }

  /**
    * For machine 2 machine communications xsrf is not really relevant since it is not a browser that
    * sends the messages. So the implementation of just returning success is sufficient
    *
    * @return success
    */
  override def xsrfCheck: Directive[F, String] = Directive.success("")

  override def transformUser(rawUser: OauthAuthenticatedApplication): F[Option[U]] = {
    lookup(rawUser)
  }

  private[unsecurity] def requestAuthBearerToken: Directive[F, String] = {
    for {
      authHeader <- request.headers.map(_.toList).map(_.find(h => h.is(Authorization) && h.value.toLowerCase.contains("bearer")))
      token <- authHeader
                .map(header => header.value.split(" ").last)
                .toSuccess(Unauthorized("Authorization header not found. Please log in"))
    } yield token
  }

  private[unsecurity] def requestAuthOnBehalfOfToken: Directive[F, String] = {
    for {
      authHeader <- request.headers.map(_.toList).map(_.find(h => h.is(Authorization) && h.value.toLowerCase.contains("on-behalf-of")))
      token <- authHeader
                .map(header => header.value.split(" ").last)
                .toSuccess(Unauthorized("Missing Authorization header with scheme On-Behalf-Of "))
    } yield token
  }

  private def decodedJWT(token: String): Directive[F, DecodedJWT] = {
    Try(JWT.decode(token)).toSuccess { throwable =>
      log.warn(throwable)("Could not extract token from request")
      Unauthorized("Could not extract token from request")
    }
  }

  private def jwtHeader(jwtToken: DecodedJWT): Directive[F, JwtHeader] = {
    for {
      decodedHeaderString <- decodeBase64(jwtToken.getHeader)
      header <- decode[JwtHeader](decodedHeaderString).toSuccess { error =>
                 log.warn(error)("Could not decode jwt header")
                 Unauthorized("Could not decode jwt header")
               }
    } yield header
  }

  private def decodeBase64(value: String): Directive[F, String] =
    Directive.success(ByteString.decodeBase64(value).utf8())

  // Private Key is stored at IdP and not in our application, hence exception throwing
  private def createPublicKeyProvider(publicKey: RSAPublicKey): RSAKeyProvider = {
    new RSAKeyProvider {

      override def getPrivateKeyId =
        throw new UnsupportedOperationException(
          "The private key is stored at the IdP and should never hit our app. Use this KeyProvider only for verification, not signing!")

      override def getPublicKeyById(keyId: String): RSAPublicKey = publicKey

      override def getPrivateKey: RSAPrivateKey =
        throw new UnsupportedOperationException(
          "The private key is stored at the IdP and should never hit our app. Use this KeyProvider only for verification, not signing!")
    }
  }

  private def verifyAccessToken(alg: Algorithm,
                                accessToken: String,
                                attemptedPath: String): Directive[F, DecodedJWT] = {
    val verifier = JWT
      .require(alg)
      .withIssuer(issuer)
      .withAudience(audience)
      .build()
    Try {
      verifier.verify(accessToken)
    }.toSuccess { throwable =>
      log.warn(throwable)(s"Could not verify token for path: $attemptedPath")
      Unauthorized("Could not verify token")
    }
  }

  private def jwtToken(verifiedToken: DecodedJWT): Directive[F, JwtToken] = {
    for {
      base64Token <- decodeBase64(verifiedToken.getPayload) // TODO: Base64 URL decode !!!
      jwtToken <- decode[JwtToken](base64Token).toSuccess { decodeError =>
                   log.warn(s"Unable to decode JWT payload: $decodeError")
                   Unauthorized("Unable to decode JWT payload")
                 }
    } yield {
      jwtToken
    }
  }

  private def checkExpiration(jwtToken: JwtToken): Directive[F, String] = {
    val expirationTime = OffsetDateTime.from(Instant.ofEpochSecond(jwtToken.exp).atOffset(ZoneOffset.UTC))
    val now            = OffsetDateTime.now(ZoneId.from(ZoneOffset.UTC))
    if (now.isAfter(expirationTime)) {
      Unauthorized(s"Token is expired! $now is after expirationTime: $expirationTime")
    } else {
      Directive.success("Valid token")
    }
  }

  private def extractProfile(jwtToken: JwtToken, rawToken:String): Directive[F, OauthAuthenticatedApplication] = {
    Directive.success(
      OauthAuthenticatedApplication(
        ApplicationId(jwtToken.sub),
        jwtToken.scopes,
        rawToken
      ))
  }
}
