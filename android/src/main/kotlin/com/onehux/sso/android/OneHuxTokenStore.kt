// onehux-sso-android/android/src/main/kotlin/com/onehux/sso/android/OneHuxTokenStore.kt
/*
 * PURPOSE: persists the token pair encrypted with a Keystore-backed Tink AEAD key — the
 * platform's own mobile integration guides are explicit: "Use OS-level secure storage
 * exclusively... never plain SharedPreferences/UserDefaults."
 * ROLE: the only place this SDK writes tokens to disk. OneHuxSsoClient reads/writes through
 * this; a consuming app should not need to touch it directly.
 */
package com.onehux.sso.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.onehux.sso.model.OneHuxTokenResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** The token triple this SDK persists, with [expiresAtEpochMillis] computed once at save time
 * (`OneHuxTokenResponse.expiresIn` is a relative seconds-from-now value, not useful to compare
 * against later without converting it immediately). */
@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val idToken: String? = null
) {
    /** `true` once [accessToken] is expired or within [skewMillis] of expiring — the margin
     * exists so a caller refreshing "just in time" doesn't lose a race against the clock
     * mid-request. */
    fun isExpiredOrExpiringSoon(skewMillis: Long = 30_000): Boolean =
        System.currentTimeMillis() >= (expiresAtEpochMillis - skewMillis)
}

private val Context.oneHuxSsoDataStore: DataStore<Preferences> by preferencesDataStore(name = "onehux_sso_session")
private val ENCRYPTED_SESSION_KEY = stringPreferencesKey("encrypted_session")

/**
 * @param keysetName / [prefsName] the Android Keystore alias and SharedPreferences file this
 * store's Tink keyset lives under — override only if a consuming app already uses these exact
 * names for something else (unlikely; the defaults are namespaced to this SDK).
 */
class OneHuxTokenStore(
    private val context: Context,
    private val keysetName: String = "onehux_sso_keyset",
    private val prefsName: String = "onehux_sso_keyset_prefs"
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, prefsName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://onehux_sso_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Observes the current session, or `null` while signed out. */
    val session: Flow<StoredSession?> = context.oneHuxSsoDataStore.data.map { prefs ->
        prefs[ENCRYPTED_SESSION_KEY]?.let { decrypt(it) }
    }

    /** Reads the current session once, without subscribing. */
    suspend fun current(): StoredSession? = session.firstOrNull()

    /** Encrypts and persists [tokens], computing [StoredSession.expiresAtEpochMillis] from
     * [OneHuxTokenResponse.expiresIn] relative to now. */
    suspend fun save(tokens: OneHuxTokenResponse) {
        val stored = StoredSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMillis = System.currentTimeMillis() + (tokens.expiresIn * 1_000),
            idToken = tokens.idToken
        )
        val encrypted = encrypt(stored)
        context.oneHuxSsoDataStore.edit { prefs -> prefs[ENCRYPTED_SESSION_KEY] = encrypted }
    }

    /** Clears the session (sign-out, or a rejected refresh). */
    suspend fun clear() {
        context.oneHuxSsoDataStore.edit { prefs -> prefs.remove(ENCRYPTED_SESSION_KEY) }
    }

    private fun encrypt(session: StoredSession): String {
        val plaintext = json.encodeToString(StoredSession.serializer(), session).encodeToByteArray()
        val ciphertext = aead.encrypt(plaintext, ByteArray(0))
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    private fun decrypt(encoded: String): StoredSession? = runCatching {
        val ciphertext = Base64.getDecoder().decode(encoded)
        val plaintext = aead.decrypt(ciphertext, ByteArray(0))
        json.decodeFromString(StoredSession.serializer(), plaintext.decodeToString())
    }.getOrNull()
}
