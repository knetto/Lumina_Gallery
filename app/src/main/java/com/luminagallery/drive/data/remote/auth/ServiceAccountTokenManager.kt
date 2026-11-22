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

    fun buildGoogleAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_READONLY)))
            .build()

    fun cacheInteractiveOAuthToken(accessToken: String) {
        synchronized(lock) {
            cachedOAuthToken = accessToken
            cachedOAuthFetchedAtMs = System.currentTimeMillis()
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedCredentials = null
            cachedOAuthToken = null
            cachedOAuthFetchedAtMs = 0L
        }
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
    fun getAccessToken(forceRefresh: Boolean = false): String {
        synchronized(lock) {
            if (credentialStore.isGoogleSignInActive()) {
                return getGoogleOAuthTokenLocked(forceRefresh)
            }
            return getServiceAccountTokenLocked(forceRefresh)
        }
    }

    private fun getGoogleOAuthTokenLocked(forceRefresh: Boolean): String {
        val now = System.currentTimeMillis()
        val existing = cachedOAuthToken
        if (!forceRefresh && !existing.isNullOrBlank() && (now - cachedOAuthFetchedAtMs) < OAUTH_SOFT_TTL_MS) {
            return existing
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

        val result = try {
            Tasks.await(
                client.authorize(buildGoogleAuthorizationRequest()),
                PLAY_SERVICES_TIMEOUT_SEC,
                TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            throw DriveAuthException(
                "Unable to refresh Google Drive authorization: ${e.message}",
                e
            )
        }

        if (result.hasResolution()) {
            throw DriveAuthException(
                "Google Drive access needs one-time re-consent. Open Settings and tap Sign in with Google."
            )
        }

        val freshToken = result.accessToken
            ?: throw DriveAuthException("Google Play Services returned an empty Drive access token.")

        cachedOAuthToken = freshToken
        cachedOAuthFetchedAtMs = System.currentTimeMillis()
        return freshToken
    }

    private fun getServiceAccountTokenLocked(forceRefresh: Boolean): String {
        val credentials = cachedCredentials ?: loadCredentialsLocked().also {
            cachedCredentials = it
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
