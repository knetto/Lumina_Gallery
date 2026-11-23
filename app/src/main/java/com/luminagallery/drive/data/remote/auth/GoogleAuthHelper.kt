package com.luminagallery.drive.data.remote.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.luminagallery.drive.data.remote.DriveScopes

/**
 * Central helpers for Google Play Services `AuthorizationClient`:
 *  - Unwraps `Activity` from Compose `LocalContext` so Play Services binds cleanly to the host Activity.
 *  - Classifies `ApiException` codes to decide whether to automatically retry with `drive.readonly`
 *    when combined `[drive.readonly, drive.appdata]` authorization fails.
 *  - Formats actionable diagnostics instead of cryptic raw status numbers (`10:`, `28444:`, `17:`).
 */
object GoogleAuthHelper {

    private const val CODE_DEVELOPER_CONSOLE_MISCONFIGURED = 28444
    private const val EXPECTED_PACKAGE = "com.luminagallery.drive"
    private const val EXPECTED_SHA1 = "B3:74:4C:48:AB:6D:15:38:5C:EB:DA:37:6F:28:7B:24:7F:A0:0A:FB"

    fun getAuthorizationClient(context: Context): AuthorizationClient {
        val activity = context.findActivity()
        return if (activity != null) {
            Identity.getAuthorizationClient(activity)
        } else {
            Identity.getAuthorizationClient(context)
        }
    }

    fun hasGrantedAppDataScope(grantedScopes: List<String>?): Boolean {
        if (grantedScopes.isNullOrEmpty()) return false
        return DriveScopes.DRIVE_APPDATA in grantedScopes
    }

    /**
     * When `authorize()` itself fails BEFORE showing any consent screen, we can safely retry with
     * `drive.readonly` alone so a missing `drive.appdata` OAuth consent scope never blocks login.
     * When `getAuthorizationResultFromIntent()` fails AFTER the consent screen, we only retry if
     * it was NOT an explicit user cancel (`CommonStatusCodes.CANCELED`).
     */
    fun shouldFallbackToReadOnly(error: Throwable, afterConsentScreen: Boolean): Boolean {
        val statusCode = extractStatusCode(error)
        if (afterConsentScreen && statusCode == CommonStatusCodes.CANCELED) {
            return false
        }
        return true
    }

    fun isExplicitUserCancel(
        error: Throwable?,
        resultCode: Int? = null,
        hasIntentData: Boolean = true
    ): Boolean {
        if (resultCode == Activity.RESULT_CANCELED && !hasIntentData) return true
        val statusCode = error?.let(::extractStatusCode)
        if (statusCode == CommonStatusCodes.CANCELED) {
            val raw = error.localizedMessage.orEmpty().lowercase()
            if ("blocked" in raw || "credential" in raw || "developer" in raw) return false
            return true
        }
        return false
    }

    fun extractStatusCode(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            if (current is ApiException) return current.statusCode
            current = current.cause
            depth++
        }
        return null
    }

    fun formatAuthError(
        error: Throwable,
        appDataUpgradeOnly: Boolean = false
    ): String {
        val statusCode = extractStatusCode(error)
        val rawMessage = error.localizedMessage.orEmpty().trim()
        return formatAuthErrorFromParts(
            statusCode = statusCode,
            rawMessage = rawMessage,
            appDataUpgradeOnly = appDataUpgradeOnly
        )
    }

    internal fun formatAuthErrorFromParts(
        statusCode: Int?,
        rawMessage: String,
        appDataUpgradeOnly: Boolean = false
    ): String {
        val lower = rawMessage.lowercase()
        val isDeveloperConfigError = statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
            statusCode == CODE_DEVELOPER_CONSOLE_MISCONFIGURED ||
            "developer console" in lower ||
            "28444" in lower

        if (appDataUpgradeOnly) {
            val codeSuffix = statusCode?.let { " (code $it)" } ?: ""
            return "Google rejected account backup scope$codeSuffix. Add drive.appdata under Google Cloud Console → OAuth consent screen → Data Access."
        }

        if (isDeveloperConfigError) {
            val codeLabel = statusCode ?: CommonStatusCodes.DEVELOPER_ERROR
            return "Google API error ($codeLabel): Check Google Cloud Console Android OAuth Client matches package $EXPECTED_PACKAGE & SHA-1 $EXPECTED_SHA1, with Drive API enabled."
        }

        return when (statusCode) {
            CommonStatusCodes.NETWORK_ERROR ->
                "Google Sign-In network error (code 7). Check your internet connection and try again."
            CommonStatusCodes.INTERNAL_ERROR ->
                "Google Play Services internal error (code 8). Try again or restart Google Play Services."
            CommonStatusCodes.API_NOT_CONNECTED ->
                "Google Play Services API unavailable (code 17). Update Google Play Services and retry."
            CommonStatusCodes.CANCELED -> {
                if ("blocked" in lower) {
                    "Google Sign-In temporarily blocked after repeated cancels (code 16). Wait a moment and retry."
                } else if ("credential" in lower || rawMessage.length > 6 && !rawMessage.startsWith("16: ")) {
                    "Google Sign-In error (code 16): $rawMessage"
                } else {
                    "Google sign-in was cancelled."
                }
            }
            null -> rawMessage.ifBlank { "Google Sign-In failed." }
            else -> {
                val detail = rawMessage.removePrefix("$statusCode:").trim()
                if (detail.isNotBlank()) {
                    "Google API error (code $statusCode): $detail"
                } else {
                    "Google API error (code $statusCode)."
                }
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
