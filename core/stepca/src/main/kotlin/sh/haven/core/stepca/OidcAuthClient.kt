package sh.haven.core.stepca

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.haven.core.data.db.entities.StepCaConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives an OIDC Authorization Code + PKCE flow against the IdP
 * configured in a [StepCaConfig], returning the final ID token. That
 * token is then handed to step-ca to mint a signed SSH cert.
 *
 * No persistent state — the access/refresh tokens are not retained;
 * we only ever need the one ID token and only for one HTTP round-trip.
 */
@Singleton
class OidcAuthClient @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /**
     * Launch a Custom Tab pointing at the IdP's authorize URL, await the
     * redirect, exchange the auth code for an ID token. Throws on any
     * step that fails (browser launch, redirect error, token exchange).
     *
     * The caller is responsible for cancellation timeouts — wrap with
     * [kotlinx.coroutines.withTimeout] if needed.
     *
     * @param launcher abstracts CustomTabsIntent.launchUrl so unit tests
     *   can substitute a fake. The real call site passes [DefaultLauncher].
     */
    suspend fun authorize(
        caConfig: StepCaConfig,
        launcher: CustomTabLauncher = DefaultLauncher(appContext),
    ): String {
        val (verifier, challenge) = Pkce.generate()
        val state = UUID.randomUUID().toString()

        val authUri = Uri.parse(caConfig.oidcAuthUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", caConfig.oidcClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid email profile")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        val deferred = OidcRedirectBus.register(state)
        try {
            launcher.launch(authUri)
        } catch (e: Throwable) {
            OidcRedirectBus.forget(state)
            throw IllegalStateException("Failed to launch browser for OIDC: ${e.message}", e)
        }

        val result = deferred.await()
        when (result) {
            is OidcRedirectBus.Result.Error -> error("OIDC error: ${result.message}")
            is OidcRedirectBus.Result.Code -> return exchangeCode(caConfig, result.code, verifier)
        }
    }

    private suspend fun exchangeCode(
        caConfig: StepCaConfig,
        code: String,
        verifier: String,
    ): String = withContext(Dispatchers.IO) {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
            append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(caConfig.oidcClientId, "UTF-8"))
        }
        val conn = (URL(caConfig.oidcTokenUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                error("OIDC token endpoint returned $responseCode: $err")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val idToken = JSONObject(resp).optString("id_token", "")
            if (idToken.isEmpty()) error("OIDC token response missing id_token")
            idToken
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Abstraction around `CustomTabsIntent.launchUrl(Context, Uri)` so the
     * authorize flow is testable without an Android view system. The
     * production binding is [DefaultLauncher].
     */
    fun interface CustomTabLauncher {
        fun launch(uri: Uri)
    }

    /** Real Custom Tab launcher backed by AndroidX Browser. */
    class DefaultLauncher(private val context: Context) : CustomTabLauncher {
        override fun launch(uri: Uri) {
            val intent = CustomTabsIntent.Builder().build().intent.apply {
                data = uri
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    companion object {
        const val REDIRECT_URI = "haven://stepca-callback"
    }
}
