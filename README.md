# onehux-sso-android

A real, installable Android/Kotlin SDK for OneHux Accounts SSO — the mobile counterpart to this
family's Node.js/Django/Go/Laravel packages, built for apps that run on a device the end user
controls, not a server.

## Which pattern applies to you — read this before adding anything

**Is your app the thing that signs the user in** — opens the browser at the platform's hosted
login page, holds the resulting session on-device, calls your own backend as that signed-in
user? That's almost every Android app integrating OneHux Accounts, and it's what this package is
for: add both `:core` and `:android` (the single `com.onehux:sso-android` artifact below ships
both).

**Is your Android app a pure API client of your own backend, which is itself already the OneHux
Accounts client (BFF pattern)?** Then your Android app doesn't talk to OneHux Accounts at all —
it talks to your backend, your backend holds the tokens. You don't need this package; your
backend needs one of the server packages (`@onehux/sso`, `onehux_sso` for Django, the Go or
Laravel packages) instead.

**Are you verifying an OneHux access token on a Kotlin/JVM backend** (Ktor, Spring Boot, a plain
JVM service) rather than on a phone? Use `:core`'s `OneHuxResourceServerVerifier` alone — see
"Resource server (JVM backend)" below. It has no Android dependency.

This package deliberately never accepts or sends a `client_secret`. A native app's binary is
installed on every end user's device; anything embedded in it is extractable, so a
"confidential" mobile client is a contradiction, not a stricter option. Register your
Application in the OneHux Accounts dashboard as **Client type: Public**, and this SDK's
Authorization Code + PKCE flow (RFC 7636) is the entire story — no secret, anywhere, ever.

## What's actually in this repo

Two Gradle modules, one artifact:

- **`:core`** — framework-agnostic PKCE/OAuth logic (`OneHuxOAuthClient`) and independent
  resource-server JWKS verification (`OneHuxResourceServerVerifier`). Plain Kotlin/JVM, no
  Android dependency — reusable from a backend if you only need token verification.
- **`:android`** — the facade most apps actually use (`OneHuxSsoClient`): Chrome Custom Tabs for
  the browser leg (RFC 8252 — never an embedded WebView), Activity Result APIs to catch the
  redirect, and Keystore-backed Tink AEAD encryption for on-device token storage (the platform's
  own mobile integration guidance: "use OS-level secure storage exclusively, never plain
  SharedPreferences").

## Install

Not yet on Maven Central — add [JitPack](https://jitpack.io) as a repository and depend on this
GitHub repo directly:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.Onehux:onehux-sso-android:<tag>")
}
```

Replace `<tag>` with a published release tag (or a commit SHA for an unreleased revision).
JitPack builds `:android` (which already depends on `:core` as `api(project(":core"))`, so one
coordinate pulls in both) straight from this repo's `build.gradle.kts` — no separate publishing
step on this side.

If you only need `:core` (a JVM backend verifying tokens, no Android), depend on
`com.github.Onehux.onehux-sso-android:core:<tag>` instead (JitPack's multi-module coordinate
form).

## Two hosts — don't mix them up

`accounts.onehux.com` serves the hosted login/logout pages the Custom Tab is opened at.
`api-accounts.onehux.com` serves the actual OAuth API (`/api/v1/oauth/token/`,
`/api/v1/oauth/userinfo/`, `/.well-known/jwks.json`) this SDK calls directly. `OneHuxConfig`
keeps them as two separate, independently overridable values for exactly the reason the other
packages in this repo document: collapsing them into one host was a real, confirmed integration
bug — the wrong host doesn't error loudly, it silently 404s. Never override `apiBaseUrl`: it has
no per-Organization customization. `loginBaseUrl` can point at your Organization's live custom
domain (Dashboard → Settings → Branding) if you have one.

## Setup

1. **Register a Public Application** in your OneHux Accounts Organization (Dashboard →
   Applications → New Application → Client type: **Public**). Set its Redirect URI to your
   app's own custom scheme — the platform's documented convention is
   `com.yourapp.app://callback`. Grant type: Authorization Code (PKCE is automatic for a public
   client; there's no separate toggle).

2. **Declare the redirect scheme** in your app module, matching exactly what you registered:

   ```kotlin
   // app/build.gradle.kts
   android {
       defaultConfig {
           manifestPlaceholders["onehuxRedirectScheme"] = "com.yourapp.app"
       }
   }
   ```

   This fills in the `${onehuxRedirectScheme}` placeholder in this library's own
   `AndroidManifest.xml` (merged into your app automatically by AGP) — `RedirectUriReceiverActivity`
   ends up declared with an intent-filter for `com.yourapp.app://` in your app's manifest, and
   only yours; this library's AAR never hardcodes any one app's scheme.

3. **Construct `OneHuxSsoClient`** — the constructor registers an `ActivityResultLauncher`
   internally, which androidx only allows before the host Activity reaches `STARTED`. Construct
   it in `onCreate` (a `by lazy` field or a plain `val`), never inside a click handler:

   ```kotlin
   class MainActivity : ComponentActivity() {
       private val sso by lazy {
           OneHuxSsoClient(
               activity = this,
               config = OneHuxConfig(
                   clientId = "onehux_client_xxx",
                   redirectUri = "com.yourapp.app://callback"
               )
           )
       }

       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           sso // force initialization now, from onCreate — see the constructor doc for why
           // ...
       }
   }
   ```

## Using it

```kotlin
// Sign in — opens Chrome Custom Tabs, suspends until the redirect comes back.
lifecycleScope.launch {
    try {
        val claims = sso.signIn()
        // claims.sub, claims.email, claims.roles, claims.permissions — see OneHuxClaims
    } catch (e: OneHuxSignInCancelledException) {
        // the user backed out of the browser — a real, expected outcome, not an error
    } catch (e: OneHuxOAuthException) {
        // the platform rejected the exchange — e.body.error / e.body.errorDescription
    } catch (e: OneHuxNetworkException) {
        // no connectivity
    }
}

// Calling your own API — always ask for a fresh token right before the call; this refreshes
// automatically (single-flight) if the stored one is expired or about to be.
lifecycleScope.launch {
    val accessToken = sso.validAccessToken() ?: return@launch // null = sign-in needed again
    // Authorization: Bearer $accessToken
}

// Reading the signed-in user without a separate network call site of your own
lifecycleScope.launch {
    val claims = sso.currentClaims() // null if there's no valid session
}

// Signing out — clears local storage immediately, then ends the platform session too
lifecycleScope.launch {
    sso.signOut(postLogoutRedirectUri = "com.yourapp.app://logged-out")
}
```

`isSignedIn()` is also available for a quick "do I have *any* stored session" check (it doesn't
confirm the session is still refreshable — use `validAccessToken()` for that).

### Checking roles and permissions

`OneHuxClaims` carries `roles`/`permissions` straight from `/userinfo`, with convenience checks:

```kotlin
val claims = sso.currentClaims()
if (claims?.hasPermission("pos:app.access") == true) {
    // ...
}
```

## Resource server (JVM backend)

If a Kotlin/JVM backend of yours needs to verify a Bearer token independently — no session, no
redirect, no `client_secret` — depend on `:core` alone and use
`OneHuxResourceServerVerifier` directly; it has no Android dependency:

```kotlin
val verifier = OneHuxResourceServerVerifier()

val token = extractBearerToken(request.header("Authorization"))
    ?: return respondUnauthorized()

val claims = try {
    verifier.verify(token, trustedClientIds = listOf("onehux_client_xxx"))
} catch (e: OneHuxTokenVerificationException) {
    return respondUnauthorized()
}
```

`verify()` fetches and caches the platform's real, live JWKS (`/.well-known/jwks.json`), matched
by `kid`, re-fetching automatically on an unrecognized `kid` — key rotation needs zero action
here. `trustedClientIds` is optional but recommended: it restricts acceptance to tokens issued to
specific Application(s), real tenant isolation on a shared identity platform, not just "is this
token valid at all."

## Security notes

- **No embedded WebView, ever.** Sign-in opens the system browser via Chrome Custom Tabs
  (`androidx.browser`) — RFC 8252's own recommendation, and the platform's integration guides
  require it. A WebView can't be trusted to not intercept credentials entered on the hosted
  login page.
- **PKCE, not a client secret.** Authorization Code + PKCE (RFC 7636) is the entire flow — no
  secret value exists anywhere in this SDK or your app's binary to extract.
- **Keystore-backed encryption at rest.** `OneHuxTokenStore` encrypts the token pair with a Tink
  AEAD key whose master key lives in the Android Keystore (hardware-backed where the device
  supports it) before writing to DataStore — never plain SharedPreferences.
- **Refresh tokens rotate.** Every refresh both this SDK and the platform treat as single-use;
  the old value is invalid the instant a new one is issued. A public client's refresh token has
  a 7-day idle / 14-day absolute lifetime (tighter than a confidential server client's 30/30) —
  expect to route back through `signIn()` more often than a backend-held session would.
- **`state` is checked, not optional.** A redirect whose `state` doesn't match throws
  `OneHuxStateMismatchException` rather than proceeding — treat that exception as a potential
  CSRF attempt, never retried silently.

## Requirements

- `minSdk 26`, `compileSdk 37`.
- Kotlin 2.4+, AGP 9+ (this repo uses AGP 9's built-in Kotlin compilation for `:android` — no
  separate `kotlin-android` plugin needed in a consuming app either).
- A host Activity extending `ComponentActivity` (any Jetpack Compose or View-based Activity
  already does).

## Build

```bash
./gradlew build   # both modules
./gradlew test    # unit tests — real MockWebServer-backed HTTP tests, no mocked OkHttpClient
./gradlew detekt  # static analysis
```

### What's been verified, and what hasn't

Every wire shape (`/api/v1/oauth/token/`, `/api/v1/oauth/userinfo/`, `/.well-known/jwks.json`,
the `{error, error_description}` error body) was confirmed directly against the live platform
(`api-accounts.onehux.com`) rather than taken from documentation alone. Unit tests cover
PKCE generation, authorization/logout URL construction, token exchange and refresh (including
that no `client_secret` is ever sent), and OAuth error parsing — all against a real
`MockWebServer`, not a mocked `OkHttpClient`. `detekt` passes clean on both modules.

**Not yet done:** a real end-to-end run on a device or emulator through an actual Custom Tabs
sign-in against the live platform. The HTTP-layer logic is verified; the Activity/Custom
Tabs/Keystore integration in `:android` is reviewed and compiles cleanly, but hasn't been
exercised on a running app. Treat this as a solid first integration to test against your own
registered Application before shipping.

## License

Apache License 2.0 — see `LICENSE`.
