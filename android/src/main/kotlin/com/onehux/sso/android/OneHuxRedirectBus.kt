// onehux-sso-android/android/src/main/kotlin/com/onehux/sso/android/OneHuxRedirectBus.kt
/*
 * PURPOSE: hands a sign-in redirect from RedirectUriReceiverActivity (a separate Activity, in
 * the browser's own task-back-to-app transition) to whichever coroutine is awaiting it
 * (OneHuxSignInLauncher) — a single-process event bus is the simplest correct bridge between
 * those two components; Android has no built-in return-a-value-to-caller for an implicit
 * VIEW intent the way startActivityForResult does for an intent this app itself started.
 * ROLE: internal plumbing. A consuming app never touches this directly.
 */
package com.onehux.sso.android

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object OneHuxRedirectBus {
    private val _redirects = MutableSharedFlow<Uri>(replay = 0, extraBufferCapacity = 1)

    /** Emits once per completed sign-in redirect; [OneHuxSignInLauncher] collects this. */
    val redirects: SharedFlow<Uri> = _redirects.asSharedFlow()

    /** Called only by [RedirectUriReceiverActivity]. */
    fun emit(uri: Uri) {
        _redirects.tryEmit(uri)
    }
}
