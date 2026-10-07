// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/oauth/OneHuxConfig.kt
/*
 * PURPOSE: the handful of values that differ per consuming app — everything else about talking
 * to OneHux Accounts is fixed and lives in OneHuxOAuthClient.
 * ROLE: constructed once by the consuming app (typically from its own BuildConfig fields) and
 * passed to OneHuxOAuthClient.
 */
package com.onehux.sso.oauth

/**
 * @property clientId the `public`-type Application's client_id (Dashboard → Applications →
 * Client type: Public). This SDK never accepts a `client_secret` — see the module-level note
 * on [OneHuxOAuthClient] for why a confidential mobile client is never correct.
 * @property redirectUri must exactly match one of the Application's registered Redirect URIs.
 * For Android, a custom scheme matching the app id is the platform's own documented
 * convention, e.g. `com.yourapp.app://callback`.
 * @property scope space-delimited OAuth scopes to request. `openid` is required to receive an
 * `id_token`; `profile`/`email` each independently gate those claims on the token/userinfo.
 * @property loginBaseUrl the browser-facing host (`/login`, `/end-session`) — defaults to the
 * platform's own shared host; override only if your Organization has a live custom domain
 * (Dashboard → Settings → Branding) and you want end users to land there instead.
 * @property apiBaseUrl the server-to-server API host (`/api/v1/oauth/...`, `/.well-known/
 * jwks.json`) — **do not** override this to a custom domain; it has no per-Organization
 * customization (the two-host split exists specifically because collapsing them was a real,
 * confirmed integration bug in earlier guides).
 */
data class OneHuxConfig(
    val clientId: String,
    val redirectUri: String,
    val scope: String = "openid profile email",
    val loginBaseUrl: String = "https://accounts.onehux.com",
    val apiBaseUrl: String = "https://api-accounts.onehux.com"
)
