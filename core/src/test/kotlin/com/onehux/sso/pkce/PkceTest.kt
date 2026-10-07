// onehux-sso-android/core/src/test/kotlin/com/onehux/sso/pkce/PkceTest.kt
/** PURPOSE: proves PKCE pairs are correctly formed (S256(verifier) == challenge, both
 * base64url with no padding) and that every call produces a fresh, unpredictable pair. */
package com.onehux.sso.pkce

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkceTest {

    @Test
    fun `code challenge is the S256 hash of the verifier`() {
        val pair = PkcePair.generate()
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(pair.codeVerifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(expectedChallenge, pair.codeChallenge)
    }

    @Test
    fun `verifier and challenge are base64url with no padding`() {
        val pair = PkcePair.generate()
        assertFalse(pair.codeVerifier.contains('='), "verifier must not be padded")
        assertFalse(pair.codeChallenge.contains('='), "challenge must not be padded")
        assertFalse(pair.codeVerifier.any { it == '+' || it == '/' }, "verifier must be URL-safe alphabet")
        assertFalse(pair.codeChallenge.any { it == '+' || it == '/' }, "challenge must be URL-safe alphabet")
    }

    @Test
    fun `verifier decodes to exactly 48 bytes`() {
        val pair = PkcePair.generate()
        val decoded = Base64.getUrlDecoder().decode(pair.codeVerifier)
        assertEquals(48, decoded.size)
    }

    @Test
    fun `every call produces a different pair`() {
        val first = PkcePair.generate()
        val second = PkcePair.generate()
        assertTrue(first.codeVerifier != second.codeVerifier)
        assertTrue(first.codeChallenge != second.codeChallenge)
    }

    @Test
    fun `generateState produces a fresh, non-empty value each call`() {
        val a = generateState()
        val b = generateState()
        assertTrue(a.isNotEmpty())
        assertTrue(a != b)
    }
}
