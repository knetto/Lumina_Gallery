package com.luminagallery.drive.data.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.luminagallery.drive.BuildConfig
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@Immutable
data class GitHubAppUpdate(
    val commitSha: String,
    val shortSha: String,
    val commitMessage: String,
    val commitDateLabel: String,
    val commitEpochMs: Long,
    val apkBlobSha: String,
    val apkSizeBytes: Long,
    val downloadUrl: String
)

@Immutable
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val checkedAtEpochMs: Long) : AppUpdateState
    data class Available(
        val update: GitHubAppUpdate,
        val showPopup: Boolean
    ) : AppUpdateState
    data class Downloading(
        val update: GitHubAppUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateState {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }
    data class ReadyToInstall(
        val update: GitHubAppUpdate,
        val apkFilePath: String,
        val needsInstallPermission: Boolean
    ) : AppUpdateState
    data class Error(
        val message: String,
        val update: GitHubAppUpdate? = null,
        val showPopup: Boolean = false
    ) : AppUpdateState
}

/**
 * Checks the public GitHub repository (`knetto/Lumina_Gallery`, branch `main`) for newer builds
 * of `apk/Lumina-Gallery.apk`.
 *
 * Uses the exact Git Blob SHA-1 of the installed APK (`sourceDir`) paired with the commit timestamp
 * vs `BuildConfig.BASE_COMMIT_EPOCH_MS` so:
 *  - Every GitHub commit that updates `apk/Lumina-Gallery.apk` triggers an update popup automatically
 *    without manual version-file maintenance.
 *  - Non-APK commits (docs, README, etc.) never trigger false popups.
 *  - Installing an update immediately matches the remote Git blob SHA-1 (zero update loops).
 *  - Unpushed local ADB dev builds never trigger downgrade prompts.
 */
class AppUpdateManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val networkMonitor: NetworkMonitor
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val updatesDir: File
        get() = File(context.cacheDir, UPDATES_DIR_NAME).apply { if (!exists()) mkdirs() }

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeDownloadJob: Job? = null

    @Volatile
    private var activeDownloadCall: Call? = null

    @Volatile
    private var cachedInstalledBlobSha: String? = null

    fun startDownloadAndInstall(activityContext: Context, update: GitHubAppUpdate) {
        if (activeDownloadJob?.isActive == true) return
        val appOrActivityCtx = activityContext
        activeDownloadJob = managerScope.launch {
            downloadAndInstallUpdate(appOrActivityCtx, update)
        }
    }

    fun cancelDownload() {
        val current = _state.value
        activeDownloadCall?.cancel()
        activeDownloadCall = null
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        if (current is AppUpdateState.Downloading) {
            runCatching { File(updatesDir, "Lumina-Gallery-${current.update.shortSha}.apk.part").delete() }
            _state.value = AppUpdateState.Available(update = current.update, showPopup = true)
        }
    }

    suspend fun checkForUpdate(manual: Boolean): AppUpdateState = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current is AppUpdateState.Downloading) return@withContext current

        if (!networkMonitor.networkState.value.isOnline) {
            if (manual) {
                val err = AppUpdateState.Error("No internet connection", showPopup = false)
                _state.value = err
                return@withContext err
            }
            return@withContext current
        }

        if (!manual) {
            val prefs = preferencesManager.getCurrentPreferences()
            if (!prefs.autoCheckUpdates) return@withContext current
            val lastCheck = preferencesManager.getLastUpdateCheckEpochMs()
            if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return@withContext current
            }
        }

        if (manual) {
            _state.value = AppUpdateState.Checking
        }

        runCatching {
            val contentsReq = Request.Builder()
                .url(GITHUB_CONTENTS_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()

            val (remoteBlobSha, remoteSizeBytes) = httpClient.newCall(contentsReq).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val sha = json.optString("sha", "").trim()
                        val size = json.optLong("size", 0L)
                        if (sha.isBlank()) throw IOException("Missing APK metadata from GitHub")
                        sha to size
                    }
                    404 -> throw IOException("GitHub repo is private or apk/Lumina-Gallery.apk not found")
                    403 -> throw IOException("GitHub API rate limit reached — try again later")
                    else -> throw IOException("GitHub returned HTTP ${resp.code}")
                }
            }
            // Only record last check timestamp after GitHub responded cleanly so transient errors can retry
            preferencesManager.setLastUpdateCheckEpochMs(System.currentTimeMillis())

            val localBlobSha = getInstalledApkGitBlobSha1()
            if (localBlobSha != null && remoteBlobSha.equals(localBlobSha, ignoreCase = true)) {
                pruneCachedApks(keepFileName = null)
                val upToDate = AppUpdateState.UpToDate(System.currentTimeMillis())
                _state.value = upToDate
                return@withContext upToDate
            }

            val commitsReq = Request.Builder()
                .url(GITHUB_COMMITS_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()

            val commitInfo = httpClient.newCall(commitsReq).execute().use { resp ->
                if (resp.code != 200) {
                    throw IOException("Could not load commit info (HTTP ${resp.code})")
                }
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) {
                    throw IOException("No APK commits found on main")
                }
                val first = arr.getJSONObject(0)
                val commitSha = first.optString("sha", "").trim()
                val commitObj = first.optJSONObject("commit")
                val rawMessage = commitObj?.optString("message", "").orEmpty().trim()
                val isoDate = commitObj?.optJSONObject("committer")?.optString("date", "").orEmpty()
                    .ifBlank { commitObj?.optJSONObject("author")?.optString("date", "").orEmpty() }
                val commitEpochMs = parseIsoEpochMs(isoDate)
                Triple(commitSha, rawMessage, commitEpochMs)
            }

            val (commitSha, rawMessage, commitEpochMs) = commitInfo

            // Avoid downgrade prompts when running a fresher local ADB build ahead of GitHub main
            if (BuildConfig.BASE_COMMIT_EPOCH_MS > 0L &&
                commitEpochMs > 0L &&
                commitEpochMs <= BuildConfig.BASE_COMMIT_EPOCH_MS
            ) {
                val upToDate = AppUpdateState.UpToDate(System.currentTimeMillis())
                _state.value = upToDate
                return@withContext upToDate
            }

            val shortSha = commitSha.take(7).ifBlank { remoteBlobSha.take(7) }
            val update = GitHubAppUpdate(
                commitSha = commitSha.ifBlank { remoteBlobSha },
                shortSha = shortSha,
                commitMessage = rawMessage.ifBlank { "New Lumina Gallery build available on GitHub." },
                commitDateLabel = formatCommitDate(commitEpochMs),
                commitEpochMs = commitEpochMs,
                apkBlobSha = remoteBlobSha,
                apkSizeBytes = remoteSizeBytes,
                downloadUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/${commitSha.ifBlank { BRANCH }}/$APK_REPO_PATH"
            )

            val skippedSha = preferencesManager.getDismissedUpdateSha()
            val shouldPop = manual || !skippedSha.equals(update.commitSha, ignoreCase = true)

            // If we already downloaded and verified this exact commit earlier, jump straight to ReadyToInstall
            val existingFile = File(updatesDir, "Lumina-Gallery-$shortSha.apk")
            if (existingFile.exists() &&
                computeGitBlobSha1(existingFile)?.equals(remoteBlobSha, ignoreCase = true) == true
            ) {
                val ready = AppUpdateState.ReadyToInstall(
                    update = update,
                    apkFilePath = existingFile.absolutePath,
                    needsInstallPermission = !canRequestPackageInstalls()
                )
                _state.value = if (shouldPop) ready else AppUpdateState.Available(update, showPopup = false)
                return@withContext _state.value
            }

            val available = AppUpdateState.Available(update = update, showPopup = shouldPop)
            _state.value = available
            available
        }.getOrElse { err ->
            if (manual) {
                val errState = AppUpdateState.Error(
                    message = err.localizedMessage ?: "Failed to check for updates",
                    showPopup = false
                )
                _state.value = errState
                errState
            } else {
                current
            }
        }
    }

    fun reopenAvailablePopup() {
        when (val current = _state.value) {
            is AppUpdateState.Available -> _state.value = current.copy(showPopup = true)
            is AppUpdateState.Error -> {
                if (current.update != null) {
                    _state.value = AppUpdateState.Available(current.update, showPopup = true)
                }
            }
            else -> Unit
        }
    }

    fun dismissPopup() {
        when (val current = _state.value) {
            is AppUpdateState.Available -> _state.value = current.copy(showPopup = false)
            is AppUpdateState.ReadyToInstall -> _state.value = AppUpdateState.Available(current.update, showPopup = false)
            is AppUpdateState.Error -> _state.value = if (current.update != null) {
                AppUpdateState.Available(current.update, showPopup = false)
            } else {
                AppUpdateState.Idle
            }
            else -> Unit
        }
    }

    suspend fun skipUpdate(commitSha: String) {
        preferencesManager.setDismissedUpdateSha(commitSha)
        dismissPopup()
    }

    suspend fun downloadAndInstallUpdate(
        activityContext: Context,
        update: GitHubAppUpdate
    ) = withContext(Dispatchers.IO) {
        val finalName = "Lumina-Gallery-${update.shortSha}.apk"
        val finalFile = File(updatesDir, finalName)
        val partFile = File(updatesDir, "$finalName.part")

        if (finalFile.exists() &&
            computeGitBlobSha1(finalFile)?.equals(update.apkBlobSha, ignoreCase = true) == true
        ) {
            handleReadyApk(activityContext, update, finalFile)
            return@withContext
        }

        _state.value = AppUpdateState.Downloading(
            update = update,
            downloadedBytes = 0L,
            totalBytes = update.apkSizeBytes
        )

        val downloadResult = runCatching {
            pruneCachedApks(keepFileName = finalName)
            if (partFile.exists()) partFile.delete()

            val req = Request.Builder()
                .url(update.downloadUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val call = httpClient.newCall(req)
            activeDownloadCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Download failed (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IOException("Empty APK response")
                    val totalBytes = body.contentLength().takeIf { it > 0L } ?: update.apkSizeBytes
                    var downloaded = 0L
                    var lastEmitTime = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 80L || downloaded == totalBytes) {
                                    lastEmitTime = now
                                    _state.value = AppUpdateState.Downloading(
                                        update = update,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes
                                    )
                                }
                            }
                            if (!isActive) throw CancellationException("Update download cancelled")
                        }
                    }
                }
            } finally {
                if (activeDownloadCall === call) activeDownloadCall = null
            }

            // 1. Verify downloaded APK matches GitHub's Git blob SHA-1
            val downloadedSha = computeGitBlobSha1(partFile)
            if (downloadedSha == null || !downloadedSha.equals(update.apkBlobSha, ignoreCase = true)) {
                partFile.delete()
                throw IOException("Downloaded APK integrity check failed — please retry")
            }

            // 2. Verify APK package name & signing certificate SHA-256 match the installed app
            if (!verifyApkSignatureMatchesInstalled(partFile)) {
                partFile.delete()
                throw IOException("Update APK signature does not match installed Lumina Gallery — blocked for security")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            handleReadyApk(activityContext, update, finalFile)
        }
        downloadResult.onFailure { err ->
            runCatching { partFile.delete() }
            if (err is CancellationException) {
                if (_state.value is AppUpdateState.Downloading) {
                    _state.value = AppUpdateState.Available(update = update, showPopup = true)
                }
                throw err
            }
            _state.value = AppUpdateState.Error(
                message = err.localizedMessage ?: "Failed to download update",
                update = update,
                showPopup = true
            )
        }
    }

    private fun verifyApkSignatureMatchesInstalled(apkFile: File): Boolean = runCatching {
        val pm = context.packageManager
        val installedSigners = readInstalledSigningCertSha256s(pm)
        if (installedSigners.isEmpty()) return@runCatching true
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return@runCatching false

        // Allow base package match (release vs .debug suffix)
        val installedBasePkg = context.packageName.removeSuffix(".debug")
        val archiveBasePkg = archiveInfo.packageName.orEmpty().removeSuffix(".debug")
        if (archiveBasePkg != installedBasePkg) return@runCatching false

        val archiveSigners = extractSignerSha256s(archiveInfo)
        archiveSigners.isNotEmpty() && archiveSigners.any { it in installedSigners }
    }.getOrDefault(false)

    private fun readInstalledSigningCertSha256s(pm: PackageManager): Set<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        extractSignerSha256s(info)
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun extractSignerSha256s(pkgInfo: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            } else {
                pkgInfo.signatures
            }
        } else {
            pkgInfo.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return sigs.mapTo(HashSet()) { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun handleReadyApk(
        activityContext: Context,
        update: GitHubAppUpdate,
        apkFile: File
    ) {
        val hasPerm = canRequestPackageInstalls()
        _state.value = AppUpdateState.ReadyToInstall(
            update = update,
            apkFilePath = apkFile.absolutePath,
            needsInstallPermission = !hasPerm
        )
        if (hasPerm) {
            withContext(Dispatchers.Main) {
                launchPackageInstaller(activityContext, apkFile)
            }
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }
    }

    fun openInstallUnknownAppsSettings(activityContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                if (activityContext !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { activityContext.startActivity(intent) }
        }
    }

    fun refreshInstallPermissionAndLaunchIfReady(activityContext: Context) {
        val current = _state.value as? AppUpdateState.ReadyToInstall ?: return
        val hasPerm = canRequestPackageInstalls()
        if (current.needsInstallPermission && hasPerm) {
            _state.value = current.copy(needsInstallPermission = false)
            val file = File(current.apkFilePath)
            if (file.exists()) {
                launchPackageInstaller(activityContext, file)
            }
        }
    }

    fun launchDownloadedInstaller(activityContext: Context) {
        val current = _state.value as? AppUpdateState.ReadyToInstall ?: return
        val file = File(current.apkFilePath)
        if (!file.exists()) {
            _state.value = AppUpdateState.Available(current.update, showPopup = true)
            return
        }
        if (!canRequestPackageInstalls()) {
            _state.value = current.copy(needsInstallPermission = true)
            openInstallUnknownAppsSettings(activityContext)
            return
        }
        launchPackageInstaller(activityContext, file)
    }

    private fun launchPackageInstaller(activityContext: Context, apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
        }.onFailure { err ->
            val current = _state.value as? AppUpdateState.ReadyToInstall
            _state.value = AppUpdateState.Error(
                message = err.localizedMessage ?: "Could not open Android package installer",
                update = current?.update,
                showPopup = true
            )
        }
    }

    private fun getInstalledApkGitBlobSha1(): String? {
        cachedInstalledBlobSha?.let { return it }
        val apkPath = context.applicationInfo.sourceDir ?: return null
        val computed = computeGitBlobSha1(File(apkPath))
        if (computed != null) {
            cachedInstalledBlobSha = computed
        }
        return computed
    }

    private fun computeGitBlobSha1(file: File): String? = runCatching {
        if (!file.exists() || !file.canRead()) return null
        val digest = MessageDigest.getInstance("SHA-1")
        val header = "blob ${file.length()}\u0000".toByteArray(Charsets.US_ASCII)
        digest.update(header)
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun pruneCachedApks(keepFileName: String?) {
        updatesDir.listFiles()?.forEach { f ->
            if (keepFileName == null || f.name != keepFileName) {
                runCatching { f.delete() }
            }
        }
    }

    private fun parseIsoEpochMs(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    private fun formatCommitDate(epochMs: Long): String {
        if (epochMs <= 0L) return "Recent update"
        return runCatching {
            val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.getDefault()).format(zdt)
        }.getOrDefault("Recent update")
    }

    companion object {
        const val REPO_OWNER = "knetto"
        const val REPO_NAME = "Lumina_Gallery"
        const val BRANCH = "main"
        const val APK_REPO_PATH = "apk/Lumina-Gallery.apk"

        private const val GITHUB_CONTENTS_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$APK_REPO_PATH?ref=$BRANCH"
        private const val GITHUB_COMMITS_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/commits?path=$APK_REPO_PATH&sha=$BRANCH&per_page=1"
        private const val USER_AGENT = "LuminaGallery-Android/${BuildConfig.VERSION_NAME}"
        private const val UPDATES_DIR_NAME = "app_updates"
        private const val AUTO_CHECK_INTERVAL_MS = 15 * 60 * 1000L
    }
}
