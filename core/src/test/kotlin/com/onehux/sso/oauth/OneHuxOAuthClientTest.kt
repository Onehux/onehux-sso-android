// onehux-sso-android/core/src/test/kotlin/com/onehux/sso/oauth/OneHuxOAuthClientTest.kt
/** PURPOSE: proves the URL-building is correct and the token-exchange/refresh/error paths send
 * and parse exactly what the live platform expects/returns — against a real MockWebServer, not
 * a mocked OkHttpClient, so the actual request serialization is what's under test. */
package com.onehux.sso.oauth

import com.onehux.sso.pkce.PkcePair
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OneHuxOAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OneHuxOAuthClient
    private lateinit var config: OneHuxConfig

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        config = OneHuxConfig(
            clientId = "onehux_client_test123",
            redirectUri = "com.example.app://callback",
            loginBaseUrl = server.url("/").toString().removeSuffix("/"),
            apiBaseUrl = server.url("/").toString().removeSuffix("/")
        )
        client = OneHuxOAuthClient(config, OkHttpClient())
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `authorization URL has every required PKCE and OAuth parameter`() {
        val pkce = PkcePair.generate()
        val url = client.buildAuthorizationUrl(pkce, state = "xyz-state").toHttpUrl()

        assertEquals("/login", url.encodedPath)
        assertEquals(config.clientId, url.queryParameter("client_id"))
        assertEquals(config.redirectUri, url.queryParameter("redirect_uri"))
        assertEquals(pkce.codeChallenge, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(config.scope, url.queryParameter("scope"))
        assertEquals("xyz-state", url.queryParameter("state"))
    }

    @Test
    fun `logout URL omits post_logout_redirect_uri when not given`() {
        val url = client.buildLogoutUrl().toHttpUrl()
        assertEquals("/end-session", url.encodedPath)
        assertEquals(config.clientId, url.queryParameter("client_id"))
        assertNull(url.queryParameter("post_logout_redirect_uri"))
    }

    @Test
    fun `logout URL includes post_logout_redirect_uri when given`() {
        val url = client.buildLogoutUrl("com.example.app://logged-out").toHttpUrl()
        assertEquals("com.example.app://logged-out", url.queryParameter("post_logout_redirect_uri"))
    }

    @Test
    fun `exchangeCode never calls the network on a state mismatch`() = runTest {
        assertFailsWith<OneHuxStateMismatchException> {
            client.exchangeCode(
                code = "abc",
                codeVerifier = "verifier",
                returnedState = "wrong",
                expectedState = "right"
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `exchangeCode sends the exact public-client body and parses a successful response`() = runTest {
        @Suppress("MaxLineLength") // a single-line JSON fixture; splitting a raw string literal changes its content
        val tokenResponseBody =
            """{"access_token":"at1","id_token":"idt1","refresh_token":"rt1","token_type":"Bearer","expires_in":900,"scope":"openid profile email"}"""
        server.enqueue(MockResponse.Builder().code(200).body(tokenResponseBody).build())

        val tokens = client.exchangeCode(
            code = "the-code",
            codeVerifier = "the-verifier",
            returnedState = "s",
            expectedState = "s"
        )

        assertEquals("at1", tokens.accessToken)
        assertEquals("rt1", tokens.refreshToken)
        assertEquals(900L, tokens.expiresIn)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/oauth/token/", recorded.target)
        val sentBody = recorded.body?.utf8().orEmpty()
        assertEquals(true, sentBody.contains("\"grant_type\":\"authorization_code\""))
        assertEquals(true, sentBody.contains("\"code\":\"the-code\""))
        assertEquals(true, sentBody.contains("\"code_verifier\":\"the-verifier\""))
        assertEquals(false, sentBody.contains("client_secret"), "a public client must never send a client_secret")
    }

    @Test
    fun `refreshAccessToken never sends a client_secret`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"access_token":"at2","refresh_token":"rt2","token_type":"Bearer","expires_in":900,"scope":"openid"}"""
                )
                .build()
        )

        client.refreshAccessToken("old-refresh-token")

        val sentBody = server.takeRequest().body?.utf8().orEmpty()
        assertEquals(true, sentBody.contains("\"grant_type\":\"refresh_token\""))
        assertEquals(true, sentBody.contains("\"refresh_token\":\"old-refresh-token\""))
        assertEquals(false, sentBody.contains("client_secret"))
    }

    @Test
    fun `a rejected code surfaces as OneHuxOAuthException with the platform's real error shape`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body("""{"error":"invalid_grant","error_description":"Code already used or expired."}""")
                .build()
        )

        val exception = assertFailsWith<OneHuxOAuthException> {
            client.exchangeCode(code = "stale", codeVerifier = "v", returnedState = "s", expectedState = "s")
        }
        assertEquals("invalid_grant", exception.body.error)
        assertEquals("Code already used or expired.", exception.body.errorDescription)
        assertEquals(400, exception.httpStatus)
    }

    @Test
    fun `userinfo sends the Bearer header and parses claims, ignoring unknown fields`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"sub":"u1","email":"a@b.com","roles":["owner"],"permissions":["pos:app.access"],"some_future_claim":"x"}"""
                )
                .build()
        )

        val claims = client.fetchUserInfo("my-access-token")

        assertEquals("u1", claims.sub)
        assertEquals("a@b.com", claims.email)
        assertEquals(true, claims.hasRole("owner"))
        assertEquals(true, claims.hasPermission("pos:app.access"))

        val recorded = server.takeRequest()
        assertEquals("Bearer my-access-token", recorded.headers["Authorization"])
    }
}
