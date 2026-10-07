// onehux-sso-android/android/src/main/kotlin/com/onehux/sso/android/OneHuxSignInLauncher.kt
/*
 * PURPOSE: opens the real system browser (Chrome Custom Tabs — never an embedded WebView,
 * RFC 8252's own recommendation and the platform's documented requirement) at a given URL, and
 * suspends until either the redirect comes back (via OneHuxRedirectBus) or the user closes the
 * tab without completing (detected as the Custom Tabs Activity itself finishing with no prior
 * redirect — Android still delivers an ActivityResult in that case, RESULT_CANCELED, even
 * though Custom Tabs never calls setResult() itself).
 * ROLE: must be constructed while the host Activity/Fragment can still register an
 * ActivityResultLauncher (its field-initializer or onCreate — androidx's own requirement,
 * before the lifecycle reaches STARTED). OneHuxSsoClient owns one of these; most apps won't
 * construct this class directly.
 */
package com.onehux.sso.android

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * @param activity the host Activity this sign-in flow is driven from. Construct this once,
 * early (a `by lazy` field or `onCreate`), and reuse it — never construct one inside a click
 * handler or a suspend function, per androidx's ActivityResultLauncher registration rules.
 */
class OneHuxSignInLauncher(private val activity: ComponentActivity) {
    private var pendingResult: CompletableDeferred<Uri?>? = null

    private val customTabsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Fires when the Custom Tabs Activity finishes, for ANY reason. If the redirect
        // already completed pendingResult (the common, successful case), this is a no-op —
        // CompletableDeferred.complete() is only honored once. If the user backed out of the
        // browser without finishing sign-in, this is what resolves the wait, with null.
        pendingResult?.complete(null)
    }

    init {
        activity.lifecycleScope.launch {
            OneHuxRedirectBus.redirects.collect { uri -> pendingResult?.complete(uri) }
        }
    }

    /**
     * Opens [url] in a Custom Tab and suspends until a redirect arrives or the user cancels.
     * @return the redirect [Uri], or `null` if the user closed the browser without completing.
     */
    suspend fun launch(url: String): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        pendingResult = deferred
        val intent = CustomTabsIntent.Builder().build().intent.apply { data = url.toUri() }
        customTabsLauncher.launch(intent)
        return try {
            deferred.await()
        } finally {
            pendingResult = null
        }
    }
}
