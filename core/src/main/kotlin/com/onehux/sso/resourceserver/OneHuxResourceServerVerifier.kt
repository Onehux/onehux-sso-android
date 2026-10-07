// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/resourceserver/OneHuxResourceServerVerifier.kt
/*
 * PURPOSE: verifies an access token independently — for when a separate app (this one, or any
 * Kotlin/JVM backend using just this module) sits behind a BFF or a mobile app that already
 * holds the tokens, and only needs to check "is this Bearer token genuinely from OneHux
 * Accounts" without being the OAuth client itself. Mirrors @onehux/sso/resource-server
 * (Node), onehux_sso.resource_server (Django), and the Go/Laravel packages' equivalents.
 * ROLE: fetches and caches the platform's real, live JWKS (RS256; `/.well-known/jwks.json`,
 * confirmed live at https://api-accounts.onehux.com on 2026-10-07), verifies the token's
 * signature against it, and decodes the claims — no session, no redirect, no client_secret.
 * DEPENDS ON: nimbus-jose-jwt for RFC 7517/7519-compliant JWS verification; this module does
 * not hand-roll JWT parsing.
 */
package com.onehux.sso.resourceserver

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.onehux.sso.model.OneHuxClaims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URL
import java.text.ParseException

/** Thrown when [OneHuxResourceServerVerifier.verify] rejects a token — expired, badly signed,
 * signed by a `kid` not in the platform's JWKS, or (when `trustedClientIds` is set) issued to
 * an Application this verifier wasn't told to trust. The message is deliberately generic
 * ("not valid anymore" territory) — this mirrors the platform's own client packages, which
 * have nothing more specific to offer a caller than "invalid or expired." */
class OneHuxTokenVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * @param apiBaseUrl the server-to-server API host — defaults to the platform's own shared
 * host. Do not point this at a custom domain; JWKS has no per-Organization customization.
 * @param connectTimeoutMs / [readTimeoutMs] bound the JWKS fetch itself (only hit on an
 * unrecognized `kid`, i.e. key rotation — steady-state verification is purely local/offline).
 */
class OneHuxResourceServerVerifier(
    apiBaseUrl: String = "https://api-accounts.onehux.com",
    connectTimeoutMs: Int = 5_000,
    readTimeoutMs: Int = 5_000
) {
    private val json = Json { ignoreUnknownKeys = true }

    // cache(true): keep the JWKS cached between calls (steady-state verification needs no
    // network at all). retrying(true): one transient-failure retry on the rare fetch that
    // does happen (new kid, or cache expiry) — nimbus's own recommended resilience default.
    private val jwkSource: JWKSource<SecurityContext> = JWKSourceBuilder
        .create<SecurityContext>(
            URL("$apiBaseUrl/.well-known/jwks.json"),
            DefaultResourceRetriever(connectTimeoutMs, readTimeoutMs)
        )
        .cache(true)
        .retrying(true)
        .build()

    private val jwtProcessor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    }

    /**
     * Verifies [accessToken]'s RS256 signature against the platform's live JWKS (re-fetching
     * automatically on a `kid` this process hasn't cached yet — key rotation on the platform
     * side needs zero action here) and returns its claims.
     * @param trustedClientIds when non-null, the token's `aud`/`client_id` claim must be one of
     * these — real tenant isolation on a shared identity platform ("is this token valid at
     * all" vs. "is this token valid *for my Application*"). Strongly recommended whenever this
     * verifier's caller is itself a specific Application, not a platform-wide service.
     * @throws OneHuxTokenVerificationException for every rejection reason (expired, bad
     * signature, unrecognized `kid`, or — when checked — an untrusted `client_id`).
     */
    suspend fun verify(accessToken: String, trustedClientIds: List<String>? = null): OneHuxClaims =
        withContext(Dispatchers.IO) {
            val claimsSet = try {
                jwtProcessor.process(accessToken, null)
            } catch (e: ParseException) {
                throw OneHuxTokenVerificationException("Access token is not a well-formed JWT", e)
            } catch (e: BadJOSEException) {
                throw OneHuxTokenVerificationException("Access token failed signature/claims verification", e)
            } catch (e: JOSEException) {
                throw OneHuxTokenVerificationException("Access token verification error", e)
            }

            if (trustedClientIds != null) {
                val clientId = claimsSet.getStringClaim("client_id") ?: claimsSet.audience?.firstOrNull()
                if (clientId == null || clientId !in trustedClientIds) {
                    throw OneHuxTokenVerificationException(
                        "Access token was not issued to a trusted client (got: ${clientId ?: "none"})"
                    )
                }
            }

            try {
                json.decodeFromString<OneHuxClaims>(claimsSet.toPayload().toString())
            } catch (e: SerializationException) {
                throw OneHuxTokenVerificationException("Verified token's claims did not match the expected shape", e)
            }
        }
}

/** Extracts the bearer token from an `Authorization` header value, or `null` if it isn't one. */
fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader == null) return null
    val prefix = "Bearer "
    return if (authorizationHeader.startsWith(prefix)) authorizationHeader.removePrefix(prefix) else null
}
