package com.luminagallery.drive

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.ui.components.AppUpdateDialogHost
import com.luminagallery.drive.ui.navigation.LuminaNavGraph
import com.luminagallery.drive.ui.screens.editor.PhotoEditorScreen
import com.luminagallery.drive.ui.screens.system.IncomingShareSheetScreen
import com.luminagallery.drive.ui.screens.system.SystemMediaPickerScreen
import com.luminagallery.drive.ui.theme.LuminaTheme
import com.luminagallery.drive.widget.LuminaPhotoWidgetProvider
import kotlinx.coroutines.delay

sealed interface ExternalLaunchRequest {
    data class ViewMedia(val uri: Uri, val mime: String, val finishOnDismiss: Boolean) : ExternalLaunchRequest
    data class EditPhoto(val uri: Uri, val displayName: String, val finishOnDismiss: Boolean) : ExternalLaunchRequest
    data class PickMedia(val mimeFilter: String, val allowMultiple: Boolean) : ExternalLaunchRequest
    data class ReceiveShare(val uris: List<Uri>, val mime: String?) : ExternalLaunchRequest
}

class MainActivity : FragmentActivity() {

    private val externalRequestState = mutableStateOf<ExternalLaunchRequest?>(null)
    private val deepLinkShortcutState = mutableStateOf<String?>(null)
    private val widgetOpenItemIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent, isColdStart = true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val appContainer = (application as LuminaApplication).container

        setContent {
            val preferences by appContainer.preferencesManager.preferencesFlow
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            val activeImageLoader by appContainer.coilCacheManager.imageLoaderFlow
                .collectAsStateWithLifecycle()
            val externalRequest = externalRequestState.value
            val shortcutTarget = deepLinkShortcutState.value
            val widgetItemId = widgetOpenItemIdState.value

            val systemDark = isSystemInDarkTheme()
            val useDarkBars = when (preferences.themeMode) {
                ThemeMode.AMOLED_BLACK, ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            LaunchedEffect(useDarkBars) {
                val style = if (useDarkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            CompositionLocalProvider(
                LocalAppContainer provides appContainer,
                LocalGalleryImageLoader provides activeImageLoader
            ) {
                LuminaTheme(
                    themeMode = preferences.themeMode,
                    dynamicColor = preferences.dynamicColor
                ) {
                    LaunchedEffect(Unit) {
                        if (externalRequest !is ExternalLaunchRequest.PickMedia) {
                            // Start Google Account profile sync (settings, Drive links, nested albums)
                            appContainer.cloudProfileSyncManager.startAutoSyncIfSignedIn()
                            // Wait 2 seconds after launch so cold start stays instant, then silently check GitHub
                            delay(2_000L)
                            appContainer.appUpdateManager.checkForUpdate(manual = false)
                        }
                    }

                    when (val req = externalRequest) {
                        is ExternalLaunchRequest.PickMedia -> {
                            SystemMediaPickerScreen(
                                mimeFilter = req.mimeFilter,
                                allowMultiple = req.allowMultiple,
                                onPicked = { uris ->
                                    returnPickedMedia(uris)
                                },
                                onCancel = {
                                    setResult(Activity.RESULT_CANCELED)
                                    finish()
                                }
                            )
                        }

                        is ExternalLaunchRequest.EditPhoto -> {
                            PhotoEditorScreen(
                                itemId = "ext_edit_${req.uri.toString().hashCode()}",
                                sourceUri = req.uri.toString(),
                                displayName = req.displayName,
                                isLocal = true,
                                onSavedAndClose = { msg ->
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    externalRequestState.value = null
                                    if (req.finishOnDismiss) finish()
                                },
                                onDismiss = {
                                    externalRequestState.value = null
                                    if (req.finishOnDismiss) finish()
                                }
                            )
                        }

                        is ExternalLaunchRequest.ReceiveShare -> {
                            IncomingShareSheetScreen(
                                uris = req.uris,
                                mimeType = req.mime,
                                onOpenEditor = { uriStr, name ->
                                    externalRequestState.value = ExternalLaunchRequest.EditPhoto(
                                        uri = Uri.parse(uriStr),
                                        displayName = name,
                                        finishOnDismiss = true
                                    )
                                },
                                onCompleted = { msg ->
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    externalRequestState.value = null
                                    finish()
                                },
                                onDismiss = {
                                    externalRequestState.value = null
                                    finish()
                                }
                            )
                        }

                        else -> {
                            val viewReq = req as? ExternalLaunchRequest.ViewMedia
                            LuminaNavGraph(
                                externalViewUri = viewReq?.uri?.toString(),
                                externalViewMime = viewReq?.mime,
                                onConsumedExternalView = {
                                    val shouldFinish = viewReq?.finishOnDismiss == true
                                    externalRequestState.value = null
                                    if (shouldFinish) finish()
                                },
                                shortcutTarget = shortcutTarget,
                                onConsumedShortcut = { deepLinkShortcutState.value = null },
                                widgetOpenItemId = widgetItemId,
                                onConsumedWidgetItem = { widgetOpenItemIdState.value = null }
                            )
                            AppUpdateDialogHost(appUpdateManager = appContainer.appUpdateManager)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isColdStart = false)
    }

    private fun returnPickedMedia(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val resultIntent = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) {
                data = uris.first()
            } else {
                val clip = ClipData.newUri(contentResolver, "Selected media", uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
                data = uris.first()
            }
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun handleIncomingIntent(intent: Intent?, isColdStart: Boolean) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val mime = intent.type
                    ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                    ?: "image/*"
                externalRequestState.value = ExternalLaunchRequest.ViewMedia(
                    uri = uri,
                    mime = mime,
                    finishOnDismiss = isColdStart
                )
            }

            Intent.ACTION_EDIT -> {
                val uri = intent.data ?: return
                val name = resolveDisplayName(uri)
                externalRequestState.value = ExternalLaunchRequest.EditPhoto(
                    uri = uri,
                    displayName = name,
                    finishOnDismiss = isColdStart
                )
            }

            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> {
                val mime = intent.type ?: "*/*"
                val allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                externalRequestState.value = ExternalLaunchRequest.PickMedia(
                    mimeFilter = mime,
                    allowMultiple = allowMultiple
                )
            }

            Intent.ACTION_SEND -> {
                val uri = getParcelableUri(intent, Intent.EXTRA_STREAM) ?: return
                externalRequestState.value = ExternalLaunchRequest.ReceiveShare(
                    uris = listOf(uri),
                    mime = intent.type
                )
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableUriList(intent, Intent.EXTRA_STREAM).orEmpty()
                if (uris.isEmpty()) return
                externalRequestState.value = ExternalLaunchRequest.ReceiveShare(
                    uris = uris,
                    mime = intent.type
                )
            }

            else -> {
                val shortcut = intent.getStringExtra(EXTRA_SHORTCUT_TARGET)
                if (!shortcut.isNullOrBlank()) {
                    if (shortcut == "camera") {
                        runCatching {
                            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                        }.onFailure {
                            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        deepLinkShortcutState.value = shortcut
                    }
                    intent.removeExtra(EXTRA_SHORTCUT_TARGET)
                }
                val widgetItemId = intent.getStringExtra(LuminaPhotoWidgetProvider.EXTRA_WIDGET_OPEN_ITEM_ID)
                if (!widgetItemId.isNullOrBlank()) {
                    widgetOpenItemIdState.value = widgetItemId
                    intent.removeExtra(LuminaPhotoWidgetProvider.EXTRA_WIDGET_OPEN_ITEM_ID)
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "photo.jpg"
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun getParcelableUriList(intent: Intent, key: String): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }

    companion object {
        const val EXTRA_SHORTCUT_TARGET = "com.luminagallery.drive.EXTRA_SHORTCUT_TARGET"
    }
}
