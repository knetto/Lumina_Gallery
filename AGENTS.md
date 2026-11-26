ocat# Lumina Gallery — AI Project Guidelines

## Mandatory Commit & Phone Auto-Update Workflow

The installed app on the user's phone checks `apk/Lumina-Gallery.apk` on `origin/main` (`knetto/Lumina_Gallery`) via `AppUpdateManager`. It compares the installed APK's Git Blob SHA-1 against the committed `apk/Lumina-Gallery.apk` on GitHub and verifies that the commit timestamp is newer than `BuildConfig.BASE_COMMIT_EPOCH_MS`.

**Whenever the user asks to commit app changes** (unless they explicitly say to skip the APK, or the commit only touches non-app docs/config files), ALWAYS perform these steps in order:

1. **Run unit tests** (set JDK 21 first on Windows):
   ```powershell
   $env:JAVA_HOME = 'C:\Users\corne\.jdks\jdk-21.0.13.1+2'
   .\gradlew.bat testDebugUnitTest --no-daemon
   ```
2. **Build the minified release APK** BEFORE creating the commit (`BuildConfig.BASE_COMMIT_EPOCH_MS` reads `git log -1 --format=%ct`, so building before committing ensures the new commit timestamp is newer than the embedded base timestamp):
   ```powershell
   $env:JAVA_HOME = 'C:\Users\corne\.jdks\jdk-21.0.13.1+2'
   .\gradlew.bat assembleRelease --no-daemon
   ```
   - Always use `assembleRelease` (never `assembleDebug` or `assembleOptimized`) so the package ID is `com.luminagallery.drive` (no `.debug` suffix) and ABIs match `arm64-v8a` + `armeabi-v7a`.
3. **Update the tracked APK**:
   ```powershell
   Copy-Item -Force "app\build\outputs\apk\release\app-release.apk" "apk\Lumina-Gallery.apk"
   ```
4. **Verify `apk/Lumina-Gallery.apk`** before committing:
   - File size is below GitHub's 100 MB limit.
   - `aapt2 dump badging apk/Lumina-Gallery.apk` reports `package: name='com.luminagallery.drive'`.
   - `apksigner verify --print-certs apk/Lumina-Gallery.apk` matches the installed app's signing certificate (`SHA-256: fd30f003b0f05e43a425a62ebc6eb1525f8dafeb217e5dd6dc46e79593cfbc43`, `SHA-1: b3744c48ab6d15385cebda376f287b247fa00afb`, unless a custom `keystore.properties` is intentionally configured).
5. **Commit code changes and `apk/Lumina-Gallery.apk` together** in a single commit on `main` using a clear, human-readable commit message (this message is displayed inside the in-app update dialog on the phone).
6. **Push to `origin main`** (`git push origin main`) so the phone receives the update prompt immediately.

## Local Emulator Auto-Refresh Workflow

Whenever you finish making app code changes under `app/src/main` (or `app/build.gradle.kts` / `gradle/libs.versions.toml`), check if the local Android emulator is already running (`& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices` lists an `emulator-*` device in `device` state):

- **If an emulator is running:** automatically run `.\tools\dev-emulator.ps1 -SkipPhotos` so the lightweight `x86_64` debug APK is rebuilt, installed onto the emulator (never onto a USB-connected phone), and relaunched automatically for the user to see immediately.
- **If no emulator is running** (or the changes only touch unit tests, docs, or tooling scripts, or the user explicitly asks to skip it): do **not** launch the emulator unprompted.
- When the user asks to commit, still follow the **Mandatory Commit & Phone Auto-Update Workflow** above (`assembleRelease` with `arm64-v8a` + `armeabi-v7a`).

## Project Guardrails & Conventions

- **Shell environment:** Windows PowerShell 5.1 — use `;` (not `&&`) to chain commands, and set `$env:JAVA_HOME = 'C:\Users\corne\.jdks\jdk-21.0.13.1+2'` before invoking `.\gradlew.bat`.
- **Automated audit rules (`app/src/test/java/com/luminagallery/drive/UiSafetyAuditTest.kt`):**
  - Never use `!!` in `ViewerScreen.kt`, `VideoPlayerPage.kt`, or `ViewerViewModel.kt`.
  - Keep `sideMargin.coerceAtLeast(0.dp)` and `bottomDockClearance.coerceAtLeast(0.dp)` in `ViewerScreen.kt`.
  - Inflate `R.layout.view_exo_texture_player` instead of calling `PlayerView(ctx)` directly.
  - Never use unbounded `readBytes()` in `MotionPhotoDetector.kt`.
  - Keep `popEnterTransition = null` on `FolderRoute` and `CollectionRoute`, and avoid `delayMillis = 55` in `LuminaNavGraph.kt`.
  - Always add explicit Room migrations in `GalleryDatabase.kt` (never use `fallbackToDestructiveMigration`), keep `excludedAlbumIdsFlow` filtering in `ViewerViewModel.kt`, and never disable `lockHiddenAlbums` in `SettingsViewModel.resetSettingsToDefaults`.
