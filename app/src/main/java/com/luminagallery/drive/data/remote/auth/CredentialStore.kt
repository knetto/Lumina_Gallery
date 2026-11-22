package com.luminagallery.drive.data.remote.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.luminagallery.drive.domain.model.CredentialSource
import com.luminagallery.drive.domain.model.ServiceAccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ServiceAccountKeyPayload(
    val type: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("private_key") val privateKey: String = "",
    @SerialName("client_email") val clientEmail: String = "",
    @SerialName("token_uri") val tokenUri: String = ""
)

/**
 * Manages authentication state securely via AES-256-GCM EncryptedSharedPreferences.
 * Priority:
 *  1. Native Android Google Sign-In (Play Services AuthorizationClient — read-only Drive scope).
 *  2. User-imported Service Account credentials.json stored in EncryptedSharedPreferences.
 *  3. Bundled `app/src/main/assets/credentials.json` if present.
 */
@Suppress("DEPRECATION")
class CredentialStore(
    private val context: Context,
    private val json: Json
) {
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _state = MutableStateFlow(resolveInitialState())
    val state: StateFlow<ServiceAccountState> = _state.asStateFlow()

    fun isGoogleSignInActive(): Boolean =
        encryptedPrefs.getBoolean(KEY_GOOGLE_SIGN_IN_ACTIVE, false)

    fun readRawCredentialsJson(): String? {
        val stored = encryptedPrefs.getString(KEY_CREDENTIALS_JSON, null)
        if (!stored.isNullOrBlank()) return stored
        return readBundledAssetsJson()
    }

    suspend fun saveGoogleSignInSession(email: String, displayName: String?) =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.ifBlank { "Google Account" }
            val label = displayName?.takeIf { it.isNotBlank() } ?: "Google Drive"
            encryptedPrefs.edit()
                .putBoolean(KEY_GOOGLE_SIGN_IN_ACTIVE, true)
                .putString(KEY_GOOGLE_EMAIL, cleanEmail)
                .putString(KEY_GOOGLE_DISPLAY_NAME, label)
                .apply()

            _state.value = ServiceAccountState.Configured(
                clientEmail = cleanEmail,
                projectId = label,
                source = CredentialSource.GOOGLE_SIGN_IN
            )
        }

    suspend fun clearGoogleSignInSession() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(KEY_GOOGLE_SIGN_IN_ACTIVE)
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_DISPLAY_NAME)
            .apply()
        _state.value = resolveInitialState()
    }

    suspend fun importFromUri(uri: Uri): Result<ServiceAccountState.Configured> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawJson = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Unable to open the selected file.")

                val parsed = validatePayload(rawJson)
                encryptedPrefs.edit()
                    .putBoolean(KEY_GOOGLE_SIGN_IN_ACTIVE, false)
                    .putString(KEY_CREDENTIALS_JSON, rawJson)
                    .apply()

                val configured = ServiceAccountState.Configured(
                    clientEmail = parsed.clientEmail,
                    projectId = parsed.projectId,
                    source = CredentialSource.ENCRYPTED_PREFS
                )
                _state.value = configured
                configured
            }
        }

    suspend fun clearImportedCredentials() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(KEY_CREDENTIALS_JSON)
            .remove(KEY_GOOGLE_SIGN_IN_ACTIVE)
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_DISPLAY_NAME)
            .apply()
        _state.value = resolveInitialState()
    }

    private fun resolveInitialState(): ServiceAccountState {
        if (encryptedPrefs.getBoolean(KEY_GOOGLE_SIGN_IN_ACTIVE, false)) {
            val email = encryptedPrefs.getString(KEY_GOOGLE_EMAIL, null) ?: "Google Account"
            val name = encryptedPrefs.getString(KEY_GOOGLE_DISPLAY_NAME, null) ?: "Google Drive"
            return ServiceAccountState.Configured(
                clientEmail = email,
                projectId = name,
                source = CredentialSource.GOOGLE_SIGN_IN
            )
        }

        val stored = encryptedPrefs.getString(KEY_CREDENTIALS_JSON, null)
        if (!stored.isNullOrBlank()) {
            val parsed = runCatching { validatePayload(stored) }.getOrNull()
            if (parsed != null) {
                return ServiceAccountState.Configured(
                    clientEmail = parsed.clientEmail,
                    projectId = parsed.projectId,
                    source = CredentialSource.ENCRYPTED_PREFS
                )
            }
        }

        val bundled = readBundledAssetsJson()
        if (!bundled.isNullOrBlank()) {
            val parsed = runCatching { validatePayload(bundled) }.getOrNull()
            if (parsed != null) {
                return ServiceAccountState.Configured(
                    clientEmail = parsed.clientEmail,
                    projectId = parsed.projectId,
                    source = CredentialSource.BUNDLED_ASSETS
                )
            }
        }

        return ServiceAccountState.NotConfigured
    }

    private fun readBundledAssetsJson(): String? = runCatching {
        context.assets.open(BUNDLED_ASSET_NAME).use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }.getOrNull()

    private fun validatePayload(rawJson: String): ServiceAccountKeyPayload {
        val payload = json.decodeFromString<ServiceAccountKeyPayload>(rawJson)
        require(payload.type == "service_account") {
            "Invalid credentials file: expected type 'service_account'."
        }
        require(payload.clientEmail.isNotBlank() && payload.clientEmail.contains("@")) {
            "Invalid credentials file: missing service account client_email."
        }
        require(payload.privateKey.contains("BEGIN PRIVATE KEY")) {
            "Invalid credentials file: missing RSA private_key."
        }
        return payload
    }

    private companion object {
        const val PREFS_FILE_NAME = "lumina_drive_secure_credentials"
        const val KEY_CREDENTIALS_JSON = "service_account_credentials_json"
        const val KEY_GOOGLE_SIGN_IN_ACTIVE = "google_sign_in_active"
        const val KEY_GOOGLE_EMAIL = "google_sign_in_email"
        const val KEY_GOOGLE_DISPLAY_NAME = "google_sign_in_display_name"
        const val BUNDLED_ASSET_NAME = "credentials.json"
    }
}
