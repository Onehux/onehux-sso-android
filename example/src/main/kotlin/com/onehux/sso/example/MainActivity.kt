// onehux-sso-android/example/src/main/kotlin/com/onehux/sso/example/MainActivity.kt
/*
 * PURPOSE: the smallest real exercise of the SDK — sign in, show who's signed in, fetch a
 * valid access token (refreshing if needed), sign out. Everything here is what the README
 * tells a consuming app to do; this file exists to prove that guidance actually works against
 * the live platform, not just against MockWebServer.
 */
package com.onehux.sso.example

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.onehux.sso.android.OneHuxSignInCancelledException
import com.onehux.sso.android.OneHuxSsoClient
import com.onehux.sso.oauth.OneHuxConfig
import com.onehux.sso.oauth.OneHuxNetworkException
import com.onehux.sso.oauth.OneHuxOAuthException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Constructed here, from onCreate — see OneHuxSsoClient's own constructor doc for why this
    // timing is a hard requirement, not a style choice.
    private val sso by lazy {
        OneHuxSsoClient(
            activity = this,
            config = OneHuxConfig(
                clientId = BuildConfig.ONEHUX_CLIENT_ID,
                redirectUri = "com.onehux.ssoexample://callback"
            )
        )
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // minSdk 26/targetSdk 37 draws edge-to-edge by default — without this, the content's
        // own top padding is NOT enough once a device's status bar height varies, and the
        // first row of content renders underneath the status bar.
        val root = findViewById<androidx.core.widget.NestedScrollView>(R.id.rootScroll)
        val rootInitialTopPadding = root.paddingTop
        val rootInitialBottomPadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = rootInitialTopPadding + bars.top, bottom = rootInitialBottomPadding + bars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        sso // force construction now, before any click can reach it

        findViewById<Button>(R.id.signInButton).setOnClickListener { signIn() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { fetchValidToken() }
        findViewById<Button>(R.id.signOutButton).setOnClickListener { signOut() }

        lifecycleScope.launch {
            if (sso.isSignedIn()) statusText.text = "Signed in (stored session found)."
        }
    }

    private fun signIn() {
        lifecycleScope.launch {
            statusText.text = "Opening sign-in..."
            try {
                val claims = sso.signIn()
                statusText.text = "Signed in as ${claims.email ?: claims.sub}\nroles: ${claims.roles}"
            } catch (e: OneHuxSignInCancelledException) {
                statusText.text = "Sign-in cancelled."
            } catch (e: OneHuxOAuthException) {
                statusText.text = "OAuth error: ${e.body.error} (${e.body.errorDescription})"
            } catch (e: OneHuxNetworkException) {
                statusText.text = "Network error: ${e.message}"
            }
        }
    }

    private fun fetchValidToken() {
        lifecycleScope.launch {
            val token = sso.validAccessToken()
            statusText.text = if (token != null) {
                "Valid access token: ${token.take(16)}..."
            } else {
                "No valid session — sign in again."
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            // Reuses the one Redirect URI this Application actually has registered
            // (com.onehux.ssoexample://callback) — post_logout_redirect_uri is validated
            // against that exact same redirect_uris list (see onehux-accounts-backend's
            // oauth/services.py _validate_redirect_uri()), so a second, never-registered
            // ".../logged-out" URI here would 400 on the platform's own /end-session page.
            // A real app would register its own dedicated logged-out URI; this example just
            // has one Redirect URI on file, so it reuses it for both legs.
            sso.signOut(postLogoutRedirectUri = "com.onehux.ssoexample://callback")
            statusText.text = "Signed out."
        }
    }
}
