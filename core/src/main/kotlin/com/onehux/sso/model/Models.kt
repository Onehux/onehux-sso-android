// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/model/Models.kt
/*
 * PURPOSE: the wire shapes this SDK sends and receives, verified directly against the live
 * platform (https://api-accounts.onehux.com) on 2026-10-07 — not copied from documentation
 * alone. `ignoreUnknownKeys = true` is used wherever these are decoded (see Json instance in
 * OneHuxOAuthClient/OneHuxResourceServerVerifier) so an additive platform change never breaks
 * an app built against this SDK.
 * ROLE: shared by both the OAuth-client half (:core, :android) and the resource-server half.
 */
package com.onehux.sso.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The response body from `POST /api/v1/oauth/token/`, for both `authorization_code` and
 * `refresh_token` grants — identical shape either way.
 */
@Serializable
data class OneHuxTokenResponse(
    @SerialName("access_token") val accessToken: String,
    /** Present for the `authorization_code` grant's `openid` scope; absent on some refreshes. */
    @SerialName("id_token") val idToken: String? = null,
    /** Rotated on every use — the previous refresh token is invalid after this. */
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    /** Access token lifetime in seconds (15 minutes = 900, as of this writing). */
    @SerialName("expires_in") val expiresIn: Long,
    /** Space-delimited, exactly as granted — may be narrower than what was requested. */
    val scope: String
)

/**
 * The claims returned by `GET /api/v1/oauth/userinfo/`, and decoded from a verified
 * `access_token`'s JWT payload by the resource-server verifier. Only the fields confirmed
 * present across every integration guide are typed; everything else is in [raw] so a new
 * claim the platform adds is never silently dropped.
 */
@Serializable
data class OneHuxClaims(
    /** The stable, unique subject identifier — this is what a consumer app should key its own
     * local user record on, never [email] (which can change). */
    val sub: String,
    val name: String? = null,
    val email: String? = null,
    val picture: String? = null,
    /** Role names granted to this user within the Application's Organization. */
    val roles: List<String> = emptyList(),
    /** Fine-grained permission strings, scoped to the requesting Application (per the
     * platform's `<slug>:app.access`-style convention). */
    val permissions: List<String> = emptyList()
) {
    /** `true` if [permissions] contains [permission] exactly. */
    fun hasPermission(permission: String): Boolean = permission in permissions

    /** `true` if [roles] contains [role] exactly. */
    fun hasRole(role: String): Boolean = role in roles
}

/** `{error, error_description?}` — the platform's standard OAuth error body, confirmed live
 * (e.g. `{"error":"unsupported_grant_type","error_description":"..."}`). */
@Serializable
data class OneHuxOAuthError(val error: String, @SerialName("error_description") val errorDescription: String? = null)

/** One entry from the unauthenticated public-application-launcher endpoint — name/logo/home
 * URL only, never an OAuth identifier (the platform's own "Public Application Discovery" docs:
 * "no OAuth identifiers exposed"). */
@Serializable
data class OneHuxPublicApplication(
    val name: String,
    @SerialName("logo_url") val logoUrl: String,
    @SerialName("home_url") val homeUrl: String
)
