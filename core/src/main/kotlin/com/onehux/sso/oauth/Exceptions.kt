// onehux-sso-android/core/src/main/kotlin/com/onehux/sso/oauth/Exceptions.kt
/*
 * PURPOSE: every way a sign-in/refresh/userinfo call can fail, as distinct catchable types —
 * callers (e.g. the :android module's AuthViewModel) need to tell "token just expired, refresh"
 * apart from "refresh itself was rejected, sign out" apart from "no network."
 */
package com.onehux.sso.oauth

import com.onehux.sso.model.OneHuxOAuthError

/** Base type for every exception this SDK throws from a network call. */
sealed class OneHuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The platform answered with a parsed `{error, error_description?}` body — e.g.
 * `invalid_grant` (expired/replayed code, rejected refresh token), `access_denied`. */
class OneHuxOAuthException(val body: OneHuxOAuthError, val httpStatus: Int) :
    OneHuxException("OneHux OAuth error: ${body.error}${body.errorDescription?.let { " ($it)" } ?: ""}")

/** The HTTP call itself failed (no connectivity, DNS, TLS, timeout) — distinct from a
 * successful HTTP response carrying an OAuth error, so callers can show "check your
 * connection" instead of "sign-in failed." */
class OneHuxNetworkException(cause: Throwable) : OneHuxException("Network error contacting OneHux Accounts", cause)

/** The platform answered 2xx but the body wasn't the expected shape — a contract change this
 * SDK hasn't caught up with yet, not a user-facing condition. */
class OneHuxMalformedResponseException(message: String, cause: Throwable? = null) : OneHuxException(message, cause)

/** The `state` value on a redirect didn't match the one sent with the authorization request —
 * treat as a potential CSRF attempt, never proceed with the exchange. */
class OneHuxStateMismatchException : OneHuxException(
    "Redirect state did not match the value sent with the authorization request"
)
