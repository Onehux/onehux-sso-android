// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/oauth/OneHuxOAuthClient.kt
/*
 * PURPOSE: the framework-agnostic OAuth 2.0 + PKCE client against OneHux Accounts — building
 * the authorization URL, exchanging a code for tokens, refreshing, fetching userinfo, building
 * the logout URL, and listing an Organization's public applications. Every endpoint shape here
 * was verified directly against the live platform on 2026-10-07 (see each function's doc).
 *
 * ROLE: this class never launches a browser and never stores a token — that's the :android
 * module's job (Custom Tabs + Keystore-backed storage). This class is pure networking logic,
 * reusable from any Kotlin/JVM context.
 *
 * This SDK is PKCE/public-client only — it never accepts or sends a `client_secret`. A native
 * app distributes its binary to every installer; anything embedded in it is extractable, so a
 * "confidential" mobile client is a contradiction, not a stricter option. If you need a
 * confidential-client (server-side) integration, see the Django/Node.js/Laravel/Go packages
 * instead — this one is for apps that run on a device the end user controls.
 */
package com.onehux.sso.oauth

import com.onehux.sso.model.OneHuxClaims
import com.onehux.sso.model.OneHuxOAuthError
import com.onehux.sso.model.OneHuxPublicApplication
import com.onehux.sso.model.OneHuxTokenResponse
import com.onehux.sso.pkce.PkcePair
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @param config this app's client id, redirect URI, scope, and the platform's two hosts.
 * @param httpClient the OkHttp client to issue token/userinfo calls on. The caller owns its
 * lifecycle (timeouts, interceptors); this class never constructs one of its own, so a host
 * app shares one client/connection pool across this SDK and its own API calls.
 */
class OneHuxOAuthClient(
    private val config: OneHuxConfig,
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** How much of an unparseable error body to keep for diagnostics — enough to spot
         * what went wrong without risking an enormous unexpected body in a thrown exception. */
        private const val UNPARSEABLE_ERROR_BODY_PREVIEW_LENGTH = 200
    }
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Builds the authorization URL to open in a system browser (Chrome Custom Tabs on
     * Android, `SFSafariViewController`/`ASWebAuthenticationSession` on iOS — never an
     * embedded WebView; RFC 8252's own recommendation, and the platform's integration guides
     * are explicit about it).
     * @param pkce a fresh pair from [com.onehux.sso.pkce.PkcePair.generate] — generate one per
     * attempt, hold [PkcePair.codeVerifier] until [exchangeCode].
     * @param state a fresh value from [com.onehux.sso.pkce.generateState] — hold it to compare
     * against the value the redirect returns.
     */
    fun buildAuthorizationUrl(pkce: PkcePair, state: String): String =
        "${config.loginBaseUrl}/login".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("code_challenge", pkce.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("scope", config.scope)
            .addQueryParameter("state", state)
            .build()
            .toString()

    /**
     * Exchanges an authorization code for tokens. `POST {apiBaseUrl}/api/v1/oauth/token/`,
     * `grant_type=authorization_code` — no `client_secret` (public client).
     * @param code the `code` query parameter from the redirect.
     * @param codeVerifier the verifier from the [PkcePair] used to build the original
     * authorization URL — must be the same pair, not regenerated.
     * @throws OneHuxStateMismatchException if [returnedState] doesn't equal [expectedState].
     * @throws OneHuxOAuthException if the platform rejects the code (expired, already used, or
     * a `code_verifier` that doesn't match the original challenge — all `invalid_grant`).
     * @throws OneHuxNetworkException on a connectivity failure.
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        returnedState: String,
        expectedState: String
    ): OneHuxTokenResponse {
        if (returnedState != expectedState) throw OneHuxStateMismatchException()
        val body = buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", config.redirectUri)
            put("client_id", config.clientId)
            put("code_verifier", codeVerifier)
        }
        return postToken(body)
    }

    /**
     * Refreshes an access token. `POST {apiBaseUrl}/api/v1/oauth/token/`,
     * `grant_type=refresh_token` — no `client_secret`. The returned [OneHuxTokenResponse]'s
     * `refreshToken` is a **new, rotated** value: the token passed in is invalid after this
     * call succeeds, whether or not the caller persists the new one.
     * @throws OneHuxOAuthException if the refresh token was rejected (expired past its 7-day
     * idle / 14-day absolute window for a public client, already rotated, or revoked) — the
     * caller must treat this as "session over," not retry.
     */
    suspend fun refreshAccessToken(refreshToken: String): OneHuxTokenResponse {
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
            put("client_id", config.clientId)
        }
        return postToken(body)
    }

    /**
     * `GET {apiBaseUrl}/api/v1/oauth/userinfo/` — recomputed live on every call (e.g. a role
     * granted after sign-in shows up immediately, without a new token).
     * @throws OneHuxOAuthException (401) if [accessToken] is expired or invalid — callers
     * should refresh once and retry, not treat this as a hard sign-out on the first 401.
     */
    suspend fun fetchUserInfo(accessToken: String): OneHuxClaims {
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/oauth/userinfo/")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val responseBody = execute(request)
        return decodeOrThrow(responseBody) { json.decodeFromString<OneHuxClaims>(it) }
    }

    /**
     * The RP-initiated logout URL: `GET {loginBaseUrl}/end-session?client_id=...` — open this
     * in the same browser context used for sign-in so the platform's own session ends too
     * (real single sign-out, not just discarding this app's local tokens).
     * @param postLogoutRedirectUri where the platform sends the browser after logout
     * completes; must be a registered Redirect URI, same as the sign-in `redirect_uri`.
     */
    fun buildLogoutUrl(postLogoutRedirectUri: String? = null): String =
        "${config.loginBaseUrl}/end-session".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", config.clientId)
            .apply { postLogoutRedirectUri?.let { addQueryParameter("post_logout_redirect_uri", it) } }
            .build()
            .toString()

    /**
     * `GET /api/v1/organizations/{organizationSlug}/public-applications/` — unauthenticated;
     * usable for any Organization by slug, not just the one [config] is registered under.
     * Returns only name/logo/home URL for Applications that opted into public listing —
     * never an OAuth identifier, never a way to start a sign-in flow by itself.
     */
    suspend fun listPublicApplications(organizationSlug: String): List<OneHuxPublicApplication> {
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/organizations/$organizationSlug/public-applications/")
            .get()
            .build()
        val responseBody = execute(request)
        return decodeOrThrow(responseBody) { json.decodeFromString(it) }
    }

    /** Shared POST-to-token-endpoint logic for both grant types. */
    private suspend fun postToken(body: JsonObject): OneHuxTokenResponse {
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/oauth/token/")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType))
            .build()
        val responseBody = execute(request)
        return decodeOrThrow(responseBody) { json.decodeFromString<OneHuxTokenResponse>(it) }
    }

    /** Runs [request], returning the raw successful body or throwing a typed exception for
     * every failure mode (network, OAuth error, malformed response). */
    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(OneHuxNetworkException(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyText = it.body.string()
                    if (it.isSuccessful) {
                        continuation.resume(bodyText)
                    } else {
                        val error = runCatching { json.decodeFromString<OneHuxOAuthError>(bodyText) }
                            .getOrElse {
                                OneHuxOAuthError(
                                    error = "unknown_error",
                                    errorDescription = bodyText.take(UNPARSEABLE_ERROR_BODY_PREVIEW_LENGTH)
                                )
                            }
                        continuation.resumeWithException(OneHuxOAuthException(error, it.code))
                    }
                }
            }
        })
    }

    /** Decodes [bodyText] with [decode], wrapping any serialization failure in the SDK's own
     * exception type rather than leaking a raw kotlinx.serialization exception to callers. */
    private inline fun <T> decodeOrThrow(bodyText: String, decode: (String) -> T): T =
        try {
            decode(bodyText)
        } catch (e: SerializationException) {
            throw OneHuxMalformedResponseException("Unexpected response shape from OneHux Accounts", e)
        }

    /** Lets [buildJsonObject] call sites above read as plain string assignment instead of
     * wrapping every value in `JsonPrimitive(...)` by hand. */
    private fun JsonObjectBuilder.put(key: String, value: String) {
        put(key, JsonPrimitive(value))
    }
}
