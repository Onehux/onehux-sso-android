// onehux-sso-android/android/src/main/kotlin/com/onehux/sso/android/OneHuxSsoClient.kt
/*
 * PURPOSE: the one class most apps need — sign in, sign out, and get a currently-valid access
 * token (refreshing automatically, single-flight, when it's expired or about to be).
 * ROLE: wraps OneHuxOAuthClient (:core), OneHuxSignInLauncher, and OneHuxTokenStore into one
 * facade. See the constructor doc for a hard requirement on WHEN to construct this.
 */
package com.onehux.sso.android

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.onehux.sso.model.OneHuxClaims
import com.onehux.sso.oauth.OneHuxConfig
import com.onehux.sso.oauth.OneHuxMalformedResponseException
import com.onehux.sso.oauth.OneHuxOAuthClient
import com.onehux.sso.oauth.OneHuxOAuthException
import com.onehux.sso.pkce.PkcePair
import com.onehux.sso.pkce.generateState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Thrown by [OneHuxSsoClient.signIn] when the user closes the browser without completing
 * sign-in (backed out, or dismissed the tab) — not an error, a real, expected outcome every
 * caller must handle (e.g. by just returning to whatever screen was showing before). */
class OneHuxSignInCancelledException : Exception("The user closed the sign-in browser tab before completing sign-in")

/**
 * @param activity the host Activity this client is scoped to. **Construct this in `onCreate`
 * or as an Activity-scoped field — never lazily inside a click handler or other callback.**
 * This mirrors Android's own `ActivityResultLauncher` registration rule (androidx throws if you
 * register one after the Activity reaches `STARTED`): [OneHuxSignInLauncher] registers its
 * launcher in this constructor, so this class inherits the same timing requirement. A typical
 * Activity does `private val sso by lazy { OneHuxSsoClient(this, config) }` evaluated from
 * `onCreate`, or constructs it as a plain `val` field — not inside `onClick { ... }`.
 * @param config this app's client id, redirect URI, scope, and the platform's hosts.
 * @param httpClient shared with [com.onehux.sso.oauth.OneHuxOAuthClient] — pass your app's own
 * client if you already have one (shared connection pool), or omit for a default instance.
 */
class OneHuxSsoClient(
    private val activity: ComponentActivity,
    private val config: OneHuxConfig,
    httpClient: OkHttpClient = OkHttpClient()
) {
    private val oauthClient = OneHuxOAuthClient(config, httpClient)
    private val tokenStore = OneHuxTokenStore(activity.applicationContext)
    private val signInLauncher = OneHuxSignInLauncher(activity)
    private val refreshMutex = Mutex()

    /** `true` if a session is currently stored (does not check whether it's still refreshable —
     * use [validAccessToken] to actually confirm). */
    suspend fun isSignedIn(): Boolean = tokenStore.current() != null

    /**
     * Opens Chrome Custom Tabs at the platform's sign-in page and suspends until the user
     * completes sign-in, cancels, or it fails.
     * @return the signed-in user's claims (also available afterward via [currentClaims]).
     * @throws OneHuxSignInCancelledException if the user closed the browser without finishing.
     * @throws com.onehux.sso.oauth.OneHuxStateMismatchException if the redirect's `state`
     * didn't match (treat as a potential CSRF attempt — never silently retried).
     * @throws com.onehux.sso.oauth.OneHuxOAuthException if the platform rejected the exchange.
     * @throws com.onehux.sso.oauth.OneHuxNetworkException on a connectivity failure.
     */
    suspend fun signIn(): OneHuxClaims {
        val pkce = PkcePair.generate()
        val state = generateState()
        val authUrl = oauthClient.buildAuthorizationUrl(pkce, state)

        val redirect = signInLauncher.launch(authUrl) ?: throw OneHuxSignInCancelledException()

        val code = redirect.getQueryParameter("code")
            ?: throw OneHuxMalformedResponseException("Redirect had no 'code' parameter: $redirect")
        val returnedState = redirect.getQueryParameter("state").orEmpty()

        val tokens = oauthClient.exchangeCode(code, pkce.codeVerifier, returnedState, state)
        tokenStore.save(tokens)
        return oauthClient.fetchUserInfo(tokens.accessToken)
    }

    /**
     * Returns a currently-valid access token, refreshing first if the stored one is expired or
     * about to be. Returns `null` if there's no session, or if a needed refresh was rejected
     * (session over — the caller should route to [signIn] again).
     * Single-flight: concurrent callers during a refresh share the one in-flight attempt rather
     * than each sending their own (the platform rotates refresh tokens, so two concurrent
     * refreshes would make the second look like a replay of an already-used token).
     */
    @Suppress("ReturnCount") // early-return guard clauses are the clearest shape for this
    // "no session / already valid / needs refresh" short-circuit — forcing one exit point
    // would need nested if/else that reads worse, not better.
    suspend fun validAccessToken(): String? {
        val current = tokenStore.current() ?: return null
        if (!current.isExpiredOrExpiringSoon()) return current.accessToken

        return refreshMutex.withLock {
            // Re-check after acquiring the lock: another caller may have already refreshed
            // while this one was waiting.
            val stillCurrent = tokenStore.current() ?: return@withLock null
            if (!stillCurrent.isExpiredOrExpiringSoon()) return@withLock stillCurrent.accessToken

            try {
                val refreshed = oauthClient.refreshAccessToken(stillCurrent.refreshToken)
                tokenStore.save(refreshed)
                refreshed.accessToken
            } catch (@Suppress("SwallowedException") e: OneHuxOAuthException) {
                // Not logged further: e's type already says everything there is to say here
                // ("refresh was rejected") — the platform's own client packages document this
                // exact situation as having "nothing more specific to offer a caller than not
                // valid anymore." Expired past its 7-day idle / 14-day absolute window for a
                // public client, already rotated, or revoked — session is genuinely over.
                tokenStore.clear()
                null
            }
        }
    }

    /** The signed-in user's claims, fetched live from `/userinfo` using the current (refreshed
     * if needed) access token — `null` if there's no valid session. */
    suspend fun currentClaims(): OneHuxClaims? {
        val accessToken = validAccessToken() ?: return null
        return oauthClient.fetchUserInfo(accessToken)
    }

    /**
     * Clears the locally stored session immediately, then opens the platform's real
     * sign-out page in Chrome Custom Tabs (fire-and-forget — this call doesn't wait for it)
     * so the platform's own session ends too, not just this app's local tokens (real single
     * sign-out, per the platform's RP-initiated logout).
     * @param postLogoutRedirectUri where the platform sends the browser afterward; must be a
     * registered Redirect URI. Omit to skip the upstream sign-out entirely (local-only).
     */
    suspend fun signOut(postLogoutRedirectUri: String? = null) {
        tokenStore.clear()
        if (postLogoutRedirectUri != null) {
            val logoutUrl = oauthClient.buildLogoutUrl(postLogoutRedirectUri)
            activity.startActivity(Intent(Intent.ACTION_VIEW, logoutUrl.toUri()))
        }
    }
}
