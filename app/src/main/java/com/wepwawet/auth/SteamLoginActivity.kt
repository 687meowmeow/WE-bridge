package com.wepwawet.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.content.edit
import com.wepwawet.log.WepWawetLogger

/**
 * SteamLoginActivity — embedded WebView for logging into Steam.
 *
 * The user navigates to `https://steamcommunity.com/login/home/?goto=workshop`
 * in the WebView. They enter their credentials, then complete Steam Guard
 * (either via the email code or the mobile authenticator code). On successful
 * login, Steam sets the `steamLoginSecure`, `sessionid`, and
 * `steamMachineAuth<steamid>` cookies for the `steamcommunity.com`,
 * `steampowered.com`, and `login.steampowered.com` domains.
 *
 * We then extract those cookies, persist them to a private SharedPreferences
 * file (the WE Android app's pairing protocol needs these cookies to download
 * workshop content for the user's account), and return the result to the
 * caller via [Activity.setResult].
 *
 * == Steam Guard / 2FA ==
 *
 * The WebView handles 2FA transparently — when Steam needs a code, the Steam
 * login page itself shows the input form. The user types the code; the
 * WebView posts it; Steam sets the cookies; we detect login completion via
 * URL change to the goto target (`steamcommunity.com/workshop/`).
 *
 * == Returning the result ==
 *
 * The caller launches this activity via [Companion.launch] and reads
 * [Companion.EXTRA_RESULT_COOKIES] from the result Intent. Example:
 *
 *   ```kotlin
 *   val launcher = registerForActivityResult(
 *       StartActivityForResult()
 *   ) { result ->
 *       if (result.resultCode == Activity.RESULT_OK) {
 *           val cookies = result.data?.getStringExtra(SteamLoginActivity.EXTRA_RESULT_COOKIES)
 *           // persist / use
 *       }
 *   }
 *   SteamLoginActivity.launch(this)
 *   ```
 *
 * == Cookie persistence ==
 *
 * Cookies are stored in SharedPreferences `wepwawet_steam_cookies` (plain
 * MODE_PRIVATE — these are scoped to the app's sandbox-protected data dir
 * and not exposed to other apps). The companion object exposes:
 *   - [getCookies]    — read the stored cookie header string
 *   - [isLoggedIn]    — quick boolean check
 *   - [logout]        — wipe the stored cookies + WebView cookie jar
 *
 * !! Security !!
 *   - WebView cookies are stored in the app's private data dir, not the system
 *     WebView store.
 *   - We use `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)`.
 *   - For production builds we should switch to EncryptedSharedPreferences
 *     (already in the dependency tree via androidx.security.crypto) — but plain
 *     MODE_PRIVATE is acceptable for the alpha per the task spec.
 *   - The extracted cookies are transferred into the Wine prefix only when
 *     Steam is launched; never persisted across a prefix reset.
 */
class SteamLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WepWawetLogger.lifecycle("SteamLoginActivity.onCreate")

        // If already logged in (cookies from previous session), skip login
        val existingCookies = CookieManager.getInstance().getCookie("https://steamcommunity.com/")
        if (existingCookies != null && existingCookies.contains("steamLoginSecure")) {
            WepWawetLogger.steam("SteamLogin: already logged in (cookies present)")
            handleLoginSuccess()
            return
        }

        // Create WebView FIRST — CookieManager.setAcceptThirdPartyCookies
        // requires a non-null WebView on Android 16
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = USER_AGENT
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = SteamLoginWebViewClient()
            loadUrl(LOGIN_URL)
        }
        WepWawetLogger.steam("SteamLoginActivity loading URL",
            "url" to LOGIN_URL)
        setContentView(webView)

        // Register an OnBackPressedCallback — the deprecated onBackPressed()
        // is bypassed on Android 14+ with predictive-back enabled.
        onBackPressedDispatcher.addCallback(this) {
            val cookies = CookieManager.getInstance().getCookie("https://steamcommunity.com/")
            if (cookies != null && cookies.contains("steamLoginSecure")) {
                WepWawetLogger.steam("SteamLogin: back pressed, detected login cookies")
                handleLoginSuccess()
            } else {
                WepWawetLogger.steam("SteamLogin: back pressed, no login cookies")
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    /**
     * Custom WebViewClient — logs every page load, detects successful Steam
     * login (URL ends up at the goto target), and triggers cookie extraction.
     */
    private inner class SteamLoginWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            // Let WebView handle all navigation — Steam login uses redirects
            // that must run inside the WebView.
            val url = request?.url?.toString() ?: ""
            WepWawetLogger.steamDebug("SteamLogin nav",
                "url" to url.take(128))
            return false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val safeUrl = url ?: ""
            WepWawetLogger.steamDebug("SteamLogin.pageFinished",
                "url" to safeUrl.take(128))

            // Detect successful login — the goto target is steamcommunity.com/workshop/
            // Steam also redirects to /profiles/<steamid>/ or /id/<custom>/ after auth
            val isWorkshopGoto = safeUrl.contains("steamcommunity.com/workshop/")
            val isProfile = safeUrl.contains("steamcommunity.com/profiles/")
            val isCustomId = safeUrl.contains("steamcommunity.com/id/")
            val isStoreAccount = safeUrl.contains("store.steampowered.com/account/")

            if (isWorkshopGoto || isProfile || isCustomId || isStoreAccount) {
                WepWawetLogger.steam("SteamLogin: detected login success",
                    "url" to safeUrl.take(128),
                    "workshop_goto" to isWorkshopGoto,
                    "profile" to isProfile)
                handleLoginSuccess()
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            super.onReceivedError(view, request, error)
            WepWawetLogger.steamDebug("SteamLogin WebView error",
                "url" to (request?.url?.toString()?.take(128) ?: "null"),
                "desc" to (error?.description?.toString()?.take(128) ?: "null"))
        }
    }

    /**
     * Extract cookies from the WebView's CookieManager, persist them to
     * SharedPreferences, set the result, and finish the activity.
     */
    private fun handleLoginSuccess() {
        WepWawetLogger.steam("SteamLogin: extracting cookies")
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val domains = listOf(
            "https://steamcommunity.com/",
            "https://store.steampowered.com/",
            "https://steampowered.com/",
            "https://login.steampowered.com/",
        )

        // Collect all cookies into a single "Cookie:" header value (semicolons + spaces)
        // v0.7.4: dedupe by cookie NAME (first occurrence wins) instead of by the
        // full "k=v" string. Steam sets `sessionid` (and a few others) with
        // DIFFERENT values on steamcommunity.com / steampowered.com /
        // login.steampowered.com — the old `.distinct()` kept ALL of them, and a
        // Cookie header with duplicate names is ambiguous (servers pick per
        // their own rules). Keeping the first occurrence (steamcommunity.com is
        // queried first and is the goto target) makes the header deterministic.
        val seenCookieNames = mutableSetOf<String>()
        val allCookies = domains
            .mapNotNull { cookieManager.getCookie(it) }
            .flatMap { it.split(";").map { c -> c.trim() } }
            .filter { it.isNotEmpty() }
            .filter { pair ->
                val eq = pair.indexOf('=')
                val name = if (eq > 0) pair.substring(0, eq).trim() else pair
                seenCookieNames.add(name)
            }
            .joinToString("; ")

        // Parse the relevant cookies individually for inspection / logging
        val parsed = parseCookiePairs(allCookies)
        val steamLoginSecure = parsed[KEY_STEAM_LOGIN_SECURE]
        val sessionId = parsed[KEY_SESSION_ID]
        val steamMachineAuth = parsed.entries
            .firstOrNull { it.key.startsWith(KEY_STEAM_MACHINE_AUTH_PREFIX) }
            ?.let { it.key to it.value }
        val steamId = steamMachineAuth?.first
            ?.removePrefix(KEY_STEAM_MACHINE_AUTH_PREFIX)
            ?: parsed[KEY_STEAM_ID_LOGIN_SECURE]?.let { extractSteamIdFromLoginSecure(it) }

        WepWawetLogger.steam("SteamLogin: cookies parsed",
            "has_steamLoginSecure" to (steamLoginSecure != null),
            "has_sessionid" to (sessionId != null),
            "has_steamMachineAuth" to (steamMachineAuth != null),
            "steam_id" to (steamId ?: "unknown"),
            "total_cookies" to parsed.size)

        // Persist to SharedPreferences
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_ALL_COOKIES, allCookies)
            steamLoginSecure?.let { putString(KEY_STEAM_LOGIN_SECURE, it) }
            sessionId?.let { putString(KEY_SESSION_ID, it) }
            steamMachineAuth?.let { (k, v) ->
                putString(k, v)
                putString(KEY_STEAM_MACHINE_AUTH_STORED, k)
            }
            steamId?.let { putString(KEY_STEAM_ID, it) }
            putLong(KEY_LOGIN_TIMESTAMP, System.currentTimeMillis())
        }
        WepWawetLogger.steam("SteamLogin: cookies persisted to SharedPreferences",
            "pref_name" to PREF_NAME)

        // Return result to caller
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_SUCCESS, true)
            putExtra(EXTRA_RESULT_COOKIES, allCookies)
            putExtra(EXTRA_RESULT_STEAM_ID, steamId)
        }
        setResult(Activity.RESULT_OK, result)
        WepWawetLogger.lifecycle("SteamLoginActivity.setResult RESULT_OK — finishing")
        finish()
    }

    /**
     * Parse a "k1=v1; k2=v2; ..." cookie header string into a Map.
     */
    private fun parseCookiePairs(cookieHeader: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        cookieHeader.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq).trim()
                val v = pair.substring(eq + 1).trim()
                if (k.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    /**
     * Extract the steamID from a `steamLoginSecure` cookie value. The cookie value
     * format is `<steamid>%7C%7C<jwt-token>` (URL-encoded `||` separator). Returns
     * null if the format doesn't match.
     */
    private fun extractSteamIdFromLoginSecure(value: String): String? {
        val sep = value.indexOf("%7C%7C")
        return if (sep > 0) value.substring(0, sep) else null
    }

    override fun onDestroy() {
        // Flush cookies synchronously so they're written to disk before the
        // caller reads the result.
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            WepWawetLogger.steamDebug("SteamLogin: flush cookies failed",
                "exception" to e.javaClass.name)
        }
        // v0.7.4: destroy the WebView. It holds strong references to the
        // Activity context (WebViewClient, the native peer) — without an
        // explicit destroy() the entire Activity leaked on every recreation.
        // Guarded with isInitialized because the early-return path in onCreate
        // (already-logged-in) never assigns the lateinit field.
        if (this::webView.isInitialized) {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
        }
        super.onDestroy()
        WepWawetLogger.lifecycle("SteamLoginActivity.onDestroy")
    }

    companion object {
        private const val LOGIN_URL =
            "https://steamcommunity.com/login/home/?goto=workshop"

        const val PREF_NAME = "wepwawet_steam_cookies"

        // SharedPreferences keys
        const val KEY_ALL_COOKIES = "all_cookies"
        const val KEY_STEAM_LOGIN_SECURE = "steamLoginSecure"
        const val KEY_SESSION_ID = "sessionid"
        const val KEY_STEAM_MACHINE_AUTH_PREFIX = "steamMachineAuth"
        const val KEY_STEAM_MACHINE_AUTH_STORED = "steamMachineAuthKey"
        const val KEY_STEAM_ID = "steamId"
        const val KEY_STEAM_ID_LOGIN_SECURE = "steamLoginSecure"  // duplicate name kept for clarity
        const val KEY_LOGIN_TIMESTAMP = "loginTimestamp"

        // Result Intent extras
        const val EXTRA_RESULT_SUCCESS = "login_success"
        const val EXTRA_RESULT_COOKIES = "cookies"
        const val EXTRA_RESULT_STEAM_ID = "steam_id"

        // User-Agent — must look like a normal desktop browser or Steam's
        // login page will refuse to render properly.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; WepWawet/0.1) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * Launch the Steam login activity.
         *
         * v0.7.24 (H-A1): this uses the DEPRECATED
         * [Activity.startActivityForResult] — a caller using
         * `registerForActivityResult(StartActivityForResult())` will NOT
         * receive a callback (the two mechanisms are mutually exclusive; the
         * old KDoc claimed the opposite). There are currently no call sites;
         * if you need the result, use
         * `startActivity(Intent(context, SteamLoginActivity::class.java))`
         * from an ActivityResultLauncher and read the result extras
         * ([EXTRA_RESULT_SUCCESS] / [EXTRA_RESULT_COOKIES] /
         * [EXTRA_RESULT_STEAM_ID]) in the launcher callback.
         *
         * @param caller the launching activity
         * @param requestCode request code for the legacy [Activity.startActivityForResult]
         */
        fun launch(caller: Activity, requestCode: Int = 1001) {
            WepWawetLogger.lifecycle("SteamLoginActivity.launch",
                "caller" to caller.javaClass.simpleName)
            val intent = Intent(caller, SteamLoginActivity::class.java)
            caller.startActivityForResult(intent, requestCode)
        }

        /**
         * Read the stored cookie header string ("k1=v1; k2=v2; ...") or `null`
         * if the user isn't logged in.
         */
        fun getCookies(context: Context): String? {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_ALL_COOKIES, null)
        }

        /**
         * Quick check whether the user has logged in to Steam (cookies are
         * stored and non-empty).
         */
        fun isLoggedIn(context: Context): Boolean {
            val cookies = getCookies(context) ?: return false
            return cookies.contains(KEY_STEAM_LOGIN_SECURE) &&
                cookies.contains(KEY_SESSION_ID)
        }

        /**
         * The SteamID of the logged-in user, or `null` if unknown.
         */
        fun getSteamId(context: Context): String? {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STEAM_ID, null)
        }

        /**
         * Wipe all stored cookies (logout). Also clears the WebView's cookie
         * jar so the next login starts fresh.
         */
        fun logout(context: Context) {
            WepWawetLogger.steam("SteamLoginActivity.logout — wiping cookies")
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            } catch (e: Exception) {
                WepWawetLogger.steamDebug("SteamLoginActivity.logout CookieManager clear failed",
                    "exception" to e.javaClass.name)
            }
        }
    }
}
