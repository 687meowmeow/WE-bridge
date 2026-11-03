package com.wepwawet.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wepwawet.log.WepWawetLogger

/**
 * Stores Steam credentials for JavaSteam workshop downloads.
 *
 * v0.7.24 (C-P4): the username + password (and the long-lived refresh token)
 * now live in [EncryptedSharedPreferences] (AES-256 keyset held by the
 * Android Keystore) instead of plaintext MODE_PRIVATE prefs. On rooted
 * devices, backup extraction, or forensic tooling, the previous plaintext
 * password + refresh token were trivially recoverable — a leaked refresh
 * token grants persistent Steam account access without re-prompting Steam
 * Guard.
 *
 * Migration: existing plaintext values are transparently moved into the
 * encrypted store on first access (read-through), then wiped from the
 * plaintext file. If Keystore-backed encryption cannot be initialized on a
 * device (rare vendor breakage), the store falls back to MODE_PRIVATE so
 * the app keeps working — the fallback is logged loudly.
 */
object SteamCredentialsStore {
    private const val PREF_NAME = "wepwawet_steam_credentials"
    private const val TOKEN_PREF_NAME = "wepwawet_steam_token"
    private const val KEY_USERNAME = "steam_username"
    private const val KEY_PASSWORD = "steam_password"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var encryptionBroken = false

    /**
     * Lazily opens (and migrates) the encrypted preferences for credentials.
     * Falls back to plain MODE_PRIVATE if the Keystore/master-key can't be
     * created on this device.
     */
    private fun prefs(context: Context): SharedPreferences {
        encryptedPrefs?.let { return it }
        if (encryptionBroken) {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secured = EncryptedSharedPreferences.create(
                context,
                "${PREF_NAME}_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migratePlaintextIfNeeded(context, secured)
            encryptedPrefs = secured
            secured
        } catch (e: Exception) {
            // Some devices (broken Keystore providers, corrupted keysets after
            // backup restores) cannot initialize EncryptedSharedPreferences.
            // Keep the app functional with plain prefs rather than locking the
            // user out of their saved login.
            encryptionBroken = true
            WepWawetLogger.proxyWarn("SteamCredentialsStore: EncryptedSharedPreferences unavailable — falling back to plain prefs",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * v0.7.24 (C-P4): one-time migration of any plaintext credentials into
     * the encrypted store, then wipe the plaintext copy.
     */
    private fun migratePlaintextIfNeeded(context: Context, secured: SharedPreferences) {
        try {
            val plain = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val plainUser = plain.getString(KEY_USERNAME, null)
            val plainPass = plain.getString(KEY_PASSWORD, null)
            if (plainUser != null || plainPass != null) {
                if (secured.getString(KEY_USERNAME, null) == null) {
                    secured.edit()
                        .putString(KEY_USERNAME, plainUser ?: "")
                        .putString(KEY_PASSWORD, plainPass ?: "")
                        .commit()
                }
                plain.edit().clear().commit()
                WepWawetLogger.proxy("SteamCredentialsStore: migrated plaintext credentials to encrypted store")
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("SteamCredentialsStore: plaintext migration failed (non-fatal)",
                "exception" to e.javaClass.name)
        }
    }

    fun saveCredentials(context: Context, username: String, password: String) {
        val prefs = prefs(context)
        val oldUsername = prefs.getString(KEY_USERNAME, null)
        val usernameChanged = oldUsername != null && oldUsername != username
        // v0.7.24 (M-P2): commit() instead of apply() — credentials must be
        // durably on disk before we return; a crash right after apply()
        // silently lost the user's login.
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .commit()
        // Only clear the saved refresh token if the USERNAME CHANGED.
        // Previously: we cleared the token on EVERY save, which meant re-saving
        // the same credentials (e.g., user pressed Save again by accident, or
        // the Settings screen was re-composed) would wipe the token and force
        // a full Steam Guard push-notification re-auth on the next download.
        // The user reported "the steam login only lasts one wallpaper then it
        // access denies me" — this was caused by the token being cleared
        // between downloads.
        //
        // Now: only clear the token if the user changed their username (which
        // genuinely invalidates the old token). Re-saving the same username
        // just updates the password without touching the token.
        val tokenPrefs = tokenPrefs(context)
        val hadToken = tokenPrefs.getString("refresh_token", null) != null
        val hadAccountName = tokenPrefs.getString("account_name", null) != null
        if (usernameChanged && (hadToken || hadAccountName)) {
            tokenPrefs.edit().clear().apply()
            WepWawetLogger.proxy("SteamCredentialsStore: cleared refresh token (username changed)",
                "had_token" to hadToken,
                "had_account_name" to hadAccountName)
        } else if (hadToken) {
            WepWawetLogger.proxy("SteamCredentialsStore: refresh token PRESERVED (username unchanged — only password updated)",
                "had_token" to hadToken)
        }
        WepWawetLogger.proxy("SteamCredentialsStore: credentials saved",
            "username" to username,
            "username_changed" to usernameChanged)
    }

    // v0.7.4: Steam Guard CODE storage removed. The app never collected a
    // Steam Guard code from the user — saveGuardCode() had ZERO call sites,
    // so getGuardCode() always returned null and the GuardAuthenticator's
    // getDeviceCode() path could only ever fail with "code required but not
    // provided". Authentication goes through Steam's device-confirmation
    // flow instead (the user approves the "is this you?" prompt on their
    // Steam mobile app) — see WorkshopDownloader.GuardAuthenticator.
    // acceptDeviceConfirmation().

    fun getUsername(context: Context): String? {
        return prefs(context).getString(KEY_USERNAME, null)
    }

    fun getPassword(context: Context): String? {
        return prefs(context).getString(KEY_PASSWORD, null)
    }

    fun hasCredentials(context: Context): Boolean {
        val username = getUsername(context)
        val password = getPassword(context)
        return !username.isNullOrBlank() && !password.isNullOrBlank()
    }

    fun clearCredentials(context: Context) {
        prefs(context).edit().clear().commit()
        // Also clear the saved refresh token + account name (they're stored
        // in a separate SharedPreferences file by WorkshopDownloader).
        tokenPrefs(context).edit().clear().apply()
        WepWawetLogger.proxy("SteamCredentialsStore: credentials cleared (incl. refresh token)")
    }

    /**
     * v0.7.24 (C-P4): encrypted storage for the long-lived refresh token +
     * account name. Transparently migrates the plaintext
     * `wepwawet_steam_token` prefs on first use. Falls back to the plain
     * file when Keystore encryption is unavailable (same policy as the
     * credentials store).
     */
    fun tokenPrefs(context: Context): SharedPreferences {
        encryptedTokenPrefs?.let { return it }
        if (tokenEncryptionBroken) {
            return context.getSharedPreferences(TOKEN_PREF_NAME, Context.MODE_PRIVATE)
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secured = EncryptedSharedPreferences.create(
                context,
                "${TOKEN_PREF_NAME}_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            // one-time migration from the plaintext token file
            try {
                val plain = context.getSharedPreferences(TOKEN_PREF_NAME, Context.MODE_PRIVATE)
                val plainToken = plain.getString("refresh_token", null)
                val plainAccount = plain.getString("account_name", null)
                if (plainToken != null || plainAccount != null) {
                    if (secured.getString("refresh_token", null) == null) {
                        secured.edit()
                            .putString("refresh_token", plainToken ?: "")
                            .putString("account_name", plainAccount ?: "")
                            .commit()
                    }
                    plain.edit().clear().commit()
                    WepWawetLogger.proxy("SteamCredentialsStore: migrated plaintext refresh token to encrypted store")
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("SteamCredentialsStore: token migration failed (non-fatal)",
                    "exception" to e.javaClass.name)
            }
            encryptedTokenPrefs = secured
            secured
        } catch (e: Exception) {
            tokenEncryptionBroken = true
            WepWawetLogger.proxyWarn("SteamCredentialsStore: token EncryptedSharedPreferences unavailable — falling back to plain prefs",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            context.getSharedPreferences(TOKEN_PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    @Volatile
    private var encryptedTokenPrefs: SharedPreferences? = null

    @Volatile
    private var tokenEncryptionBroken = false

    /**
     * Check if a Steam refresh token is saved (for password-less re-login
     * without Steam Guard push notification).
     *
     * Used by [SettingsScreen] to show the user whether their login is
     * "persistent" (will work on app restart) or "ephemeral" (needs Steam
     * Guard approval again). The token is saved by [WorkshopDownloader.onConnected]
     * after a successful modern-auth flow (with Steam Guard approval).
     *
     * v0.4.7: The token is kept valid across logins by setting
     * `details.shouldRememberPassword = true` on LogOnDetails — without that
     * flag, Steam invalidated the token after one use, causing the user's
     * "steam login only lasts one wallpaper then access denies me" bug.
     */
    fun hasRefreshToken(context: Context): Boolean {
        return tokenPrefs(context)
            .getString("refresh_token", null)?.isNotBlank() == true
    }

    /**
     * Get the saved Steam account name (canonical case from Steam backend).
     * Returns null if no refresh token has been saved yet.
     */
    fun getSavedAccountName(context: Context): String? {
        return tokenPrefs(context)
            .getString("account_name", null)?.takeIf { it.isNotBlank() }
    }
}
