package com.luminagallery.drive.data.remote.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.auth.oauth2.GoogleCredentials
import com.luminagallery.drive.data.remote.DriveScopes
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Mints and silently refreshes OAuth2 bearer tokens for Google Drive read-only access.
 * Supports both:
 *  1. Native Android Google Sign-In via Play Services `AuthorizationClient` (zero JSON setup).
 *  2. Google Cloud Service Account JSON via `google-auth-library-oauth2-http`.
 */
class ServiceAccountTokenManager(
    private val context: Context,
    private val credentialStore: CredentialStore
) {
    private val lock = Any()

    @Volatile
    private var cachedCredentials: GoogleCredentials? = null

    @Volatile
    private var cachedOAuthToken: String? = null

    @Volatile
    private var cachedOAuthFetchedAtMs: Long = 0L

    /** Tracks whether the active Google OAuth token includes `drive.appdata` for account sync. */
    @Volatile
    var hasAppDataScope: Boolean = false
        private set

    fun buildGoogleAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(DriveScopes.DRIVE_READONLY),
                    Scope(DriveScopes.DRIVE_APPDATA)
                )
            )
            .build()

    fun buildReadOnlyAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_READONLY)))
            .build()

    fun cacheInteractiveOAuthToken(accessToken: String, grantedAppDataScope: Boolean = true) {
        synchronized(lock) {
            cachedOAuthToken = accessToken
            cachedOAuthFetchedAtMs = System.currentTimeMillis()
            hasAppDataScope = grantedAppDataScope
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedCredentials = null
            cachedOAuthToken = null
            cachedOAuthFetchedAtMs = 0L
            hasAppDataScope = false
        }
    }

    fun markAppDataScopeMissing() {
        hasAppDataScope = false
    }

    fun clearPlayServicesTokenBlocking() {
        val tokenToClear = synchronized(lock) {
            val existing = cachedOAuthToken
            cachedOAuthToken = null
            cachedOAuthFetchedAtMs = 0L
            existing
        }
        if (!tokenToClear.isNullOrBlank()) {
            runCatching {
                val client = Identity.getAuthorizationClient(context)
                val clearReq = ClearTokenRequest.builder()
                    .setToken(tokenToClear)
                    .build()
                Tasks.await(client.clearToken(clearReq), PLAY_SERVICES_TIMEOUT_SEC, TimeUnit.SECONDS)
            }
        }
    }

    @Throws(IOException::class)
    fun getAccessToken(forceRefresh: Boolean = false, failedToken: String? = null): String {
        synchronized(lock) {
            if (credentialStore.isGoogleSignInActive()) {
                return getGoogleOAuthTokenLocked(forceRefresh, failedToken)
            }
            return getServiceAccountTokenLocked(forceRefresh, failedToken)
        }
    }

    private fun getGoogleOAuthTokenLocked(forceRefresh: Boolean, failedToken: String?): String {
        val now = System.currentTimeMillis()
        val existing = cachedOAuthToken
        if (!existing.isNullOrBlank() && (now - cachedOAuthFetchedAtMs) < OAUTH_SOFT_TTL_MS) {
            // Deduplicate concurrent 401 refreshes: if another thread already rotated away from
            // `failedToken`, reuse the freshly minted token instead of clearing it!
            if (!forceRefresh || (failedToken != null && existing != failedToken)) {
                return existing
            }
        }

        val client = Identity.getAuthorizationClient(context)
        if (forceRefresh && !existing.isNullOrBlank()) {
            runCatching {
                val clearReq = ClearTokenRequest.builder()
                    .setToken(existing)
                    .build()
                Tasks.await(client.clearToken(clearReq), PLAY_SERVICES_TIMEOUT_SEC, TimeUnit.SECONDS)
            }
            cachedOAuthToken = null
        }

        val primaryAttempt = runCatching {
            Tasks.await(
                client.authorize(buildGoogleAuthorizationRequest()),
                PLAY_SERVICES_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )
        }

        val primaryResult = primaryAttempt.getOrNull()
        if (primaryResult != null && !primaryResult.hasResolution()) {
            val freshToken = primaryResult.accessToken
            if (!freshToken.isNullOrBlank()) {
                cachedOAuthToken = freshToken
                cachedOAuthFetchedAtMs = System.currentTimeMillis()
                hasAppDataScope = primaryResult.grantedScopes.isEmpty() ||
                    GoogleAuthHelper.hasGrantedAppDataScope(primaryResult.grantedScopes)
                return freshToken
            }
        }

        // Fallback to DRIVE_READONLY whenever DRIVE_APPDATA either needs consent OR fails with an
        // ApiException (e.g., drive.appdata not yet added to Google Cloud OAuth Consent Screen),
        // so browsing & streaming Drive photos continues working uninterrupted.
        val fallbackAttempt = runCatching {
            Tasks.await(
                client.authorize(buildReadOnlyAuthorizationRequest()),
                PLAY_SERVICES_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )
        }
        val fallbackResult = fallbackAttempt.getOrNull()
        val fallbackToken = fallbackResult?.takeIf { !it.hasResolution() }?.accessToken
        if (!fallbackToken.isNullOrBlank()) {
            cachedOAuthToken = fallbackToken
            cachedOAuthFetchedAtMs = System.currentTimeMillis()
            hasAppDataScope = false
            return fallbackToken
        }

        if (primaryResult?.hasResolution() == true || fallbackResult?.hasResolution() == true) {
            throw DriveAuthException(
                "Google Drive access needs one-time re-consent. Open Settings and tap Sign in with Google."
            )
        }

        val rootError = fallbackAttempt.exceptionOrNull() ?: primaryAttempt.exceptionOrNull()
        if (rootError != null) {
            throw DriveAuthException(
                "Unable to refresh Google Drive authorization: ${GoogleAuthHelper.formatAuthError(rootError)}",
                rootError
            )
        }

        throw DriveAuthException("Google Play Services returned an empty Drive access token.")
    }

    private fun getServiceAccountTokenLocked(forceRefresh: Boolean, failedToken: String?): String {
        val credentials = cachedCredentials ?: loadCredentialsLocked().also {
            cachedCredentials = it
        }
        val currentToken = credentials.accessToken?.tokenValue
        if (forceRefresh && failedToken != null && !currentToken.isNullOrBlank() && currentToken != failedToken) {
            return currentToken
        }
        try {
            if (forceRefresh || credentials.accessToken == null) {
                credentials.refresh()
            } else {
                credentials.refreshIfExpired()
            }
        } catch (e: IOException) {
            throw DriveAuthException(
                "Failed to mint Google Drive OAuth2 access token: ${e.message}",
                e
            )
        }
        return credentials.accessToken?.tokenValue
            ?: throw DriveAuthException("Service Account returned an empty access token.")
    }

    private fun loadCredentialsLocked(): GoogleCredentials {
        val rawJson = credentialStore.readRawCredentialsJson()
            ?: throw DriveAuthException(
                "Not signed in to Google Drive. Tap Sign in with Google or import credentials.json."
            )
        return try {
            GoogleCredentials
                .fromStream(rawJson.byteInputStream(Charsets.UTF_8))
                .createScoped(listOf(DriveScopes.DRIVE_READONLY))
        } catch (e: Exception) {
            throw DriveAuthException("Failed to parse Service Account credentials.json.", e)
        }
    }

    private companion object {
        const val OAUTH_SOFT_TTL_MS = 45L * 60L * 1000L
        const val PLAY_SERVICES_TIMEOUT_SEC = 15L
    }
}
