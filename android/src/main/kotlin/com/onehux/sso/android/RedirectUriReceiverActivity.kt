// onehux-sso-android/android/src/main/kotlin/com/onehux/sso/android/RedirectUriReceiverActivity.kt
/*
 * PURPOSE: catches the browser's redirect back into the app after sign-in (or sign-out)
 * completes at OneHux Accounts, and hands it to OneHuxRedirectBus.
 * ROLE: declared in this library's AndroidManifest.xml with a manifest-placeholder scheme (see
 * that file's header). Does no UI — translucent theme, finishes immediately — so the user
 * never sees this Activity; Android simply resumes whatever was underneath in the task stack
 * (the screen that called OneHuxSignInLauncher.launch()).
 */
package com.onehux.sso.android

import android.app.Activity
import android.os.Bundle

/** No-UI Activity that exists only to receive the OAuth redirect and forward it to
 * [OneHuxRedirectBus]. Never constructed or referenced directly by a consuming app. */
class RedirectUriReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { OneHuxRedirectBus.emit(it) }
        finish()
    }
}
