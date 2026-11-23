package com.luminagallery.drive.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.WeakHashMap

/**
 * Biometric & Device Credential (fingerprint / face / PIN / pattern) helper for locking
 * Private / Hidden albums, plus a `FLAG_SECURE` composable blocking screenshots & recents thumbnails.
 */
object BiometricLockHelper {

    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        context: Context,
        title: String = "Unlock hidden albums",
        subtitle: String = "Verify your identity to access private albums",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            onFailure("Biometric authentication unavailable in this window")
            return
        }
        if (!canAuthenticate(context)) {
            onFailure("Set up fingerprint, face unlock, or a screen lock PIN in Android Settings first")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) {
                        onFailure(errString.toString().ifBlank { "Authentication failed" })
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        runCatching {
            prompt.authenticate(promptInfo)
        }.onFailure { err ->
            onFailure(err.localizedMessage ?: "Unable to launch biometric prompt")
        }
    }

    private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}

/**
 * Reference-counted tracker for `FLAG_SECURE` per [Window] so overlapping protected screens
 * (e.g. a locked `FolderScreen` beneath `ViewerScreen` or `PhotoEditorScreen`) never strip
 * screenshot protection from the parent screen when a child screen closes.
 */
object SecureWindowCounter {
    private val lock = Any()
    private val counts = WeakHashMap<Any, Int>()

    fun acquire(token: Any): Int = synchronized(lock) {
        val next = (counts[token] ?: 0) + 1
        counts[token] = next
        next
    }

    fun release(token: Any): Int = synchronized(lock) {
        val prev = counts[token] ?: 0
        val next = (prev - 1).coerceAtLeast(0)
        if (next == 0) {
            counts.remove(token)
        } else {
            counts[token] = next
        }
        next
    }

    fun currentCount(token: Any): Int = synchronized(lock) { counts[token] ?: 0 }
}

/**
 * Applies `WindowManager.LayoutParams.FLAG_SECURE` while [enabled] is true so private/hidden
 * albums cannot be captured via screenshots or Android's Recents app switcher preview.
 * Uses [SecureWindowCounter] so closing a child screen never strips protection from a parent.
 */
@Composable
fun SecureWindowEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window: Window? = context.findActivity()?.window
        if (enabled && window != null) {
            if (SecureWindowCounter.acquire(window) == 1) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            if (enabled && window != null) {
                if (SecureWindowCounter.release(window) == 0) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
