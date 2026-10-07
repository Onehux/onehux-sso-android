// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/pkce/PkcePair.kt
/*
 * PURPOSE: RFC 7636 PKCE pair generation, exactly as the platform's own integration guides
 * specify: a 48-byte random verifier, base64url-encoded, SHA-256 hashed for the S256 challenge.
 * ROLE: used once per sign-in attempt by OneHuxOAuthClient.startAuthorization(); the verifier
 * must be held by the caller (e.g. in the Android ViewModel driving the sign-in flow) until
 * the token exchange, then discarded.
 */
package com.onehux.sso.pkce

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A PKCE pair for one authorization attempt. */
data class PkcePair(
    /** The secret half — never leaves this device; sent only to the token endpoint, inside
     * the code exchange request, over HTTPS. */
    val codeVerifier: String,
    /** The public half — sent in the authorization URL; derived from [codeVerifier] such that
     * only whoever holds the verifier can later prove it matches this challenge. */
    val codeChallenge: String
) {
    companion object {
        /** RFC 7636 allows 43–128 bytes of entropy for the verifier; 48 matches every
         * platform integration guide's own example exactly. */
        private const val VERIFIER_BYTE_LENGTH = 48

        private val secureRandom = SecureRandom()
        private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /**
         * Generates a fresh PKCE pair. Each call produces a new, unpredictable [codeVerifier] —
         * callers must generate one per authorization attempt, never reuse one.
         */
        fun generate(): PkcePair {
            val verifierBytes = ByteArray(VERIFIER_BYTE_LENGTH)
            secureRandom.nextBytes(verifierBytes)
            val codeVerifier = urlEncoder.encodeToString(verifierBytes)

            val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            val codeChallenge = urlEncoder.encodeToString(digest)

            return PkcePair(codeVerifier, codeChallenge)
        }
    }
}

/** Bytes of entropy for [generateState] — no RFC mandates a specific size for the OAuth
 * `state` parameter; 24 bytes (192 bits) is comfortably beyond any brute-force concern. */
private const val STATE_BYTE_LENGTH = 24

/** Generates a cryptographically random `state` value for CSRF protection on the authorization
 * request — callers must verify the value returned on redirect matches exactly what was sent. */
fun generateState(): String {
    val bytes = ByteArray(STATE_BYTE_LENGTH)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
