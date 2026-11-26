<#
.SYNOPSIS
  Lumina Gallery local dev loop: runs the app in a lightweight Android emulator on this laptop,
  filled with photos from your PC Pictures folder. No phone, no release APK, no GitHub push needed.

.EXAMPLES
  .\tools\dev-emulator.ps1                 # start emulator, sync photos, build (if changed) + install + launch
  .\tools\dev-emulator.ps1 -SkipPhotos     # after a code change: rebuild + reinstall (fastest)
  .\tools\dev-emulator.ps1 -NoBuild -SkipPhotos  # just launch emulator + existing debug APK (zero Gradle RAM)
  .\tools\dev-emulator.ps1 -PhotoCount 1500 -IncludeVideos
  .\tools\dev-emulator.ps1 -PhotosOnly     # only (re)sync photos
  .\tools\dev-emulator.ps1 -Wipe           # fresh emulator (factory reset)

  The debug build installs as "com.luminagallery.drive.debug", completely separate from the app on
  your phone, and is only ever installed on the emulator (never on a USB-connected phone).
#>
param(
    [int]$PhotoCount = 250,
    [string]$PicturesDir = (Join-Path $env:USERPROFILE 'Pictures'),
    [switch]$IncludeVideos,
    [int]$MaxVideoMB = 80,
    [switch]$SkipPhotos,
    [switch]$PhotosOnly,
    [switch]$NoBuild,
    [switch]$ForceBuild,
    [switch]$ColdBoot,
    [switch]$Wipe
)

$ErrorActionPreference = 'Continue'   # adb/gradle print harmless warnings on stderr
$repo = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:JAVA_HOME = 'C:\Users\corne\.jdks\jdk-21.0.13.1+2'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avdName = 'Lumina_Dev'
$package = 'com.luminagallery.drive.debug'
$remoteRoot = '/sdcard/Pictures/PC'
$apkPath = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 1. AVD (lightweight 2 GB RAM)
# Written by hand: avdmanager on this SDK produces a broken template config (devices.xml bug).
$avdRoot = Join-Path $env:USERPROFILE '.android\avd'
$avdDir = Join-Path $avdRoot "$avdName.avd"
$cfgPath = Join-Path $avdDir 'config.ini'
if (-not (Test-Path $cfgPath) -or -not (Select-String -Path $cfgPath -Pattern '^AvdId=' -Quiet)) {
    Step "Creating lightweight emulator '$avdName' (one-time)"
    New-Item -ItemType Directory -Force -Path $avdDir | Out-Null
    Set-Content -Encoding ASCII -Path (Join-Path $avdRoot "$avdName.ini") -Value @"
avd.ini.encoding=UTF-8
path=$avdDir
path.rel=avd\$avdName.avd
target=android-36
"@
    Set-Content -Encoding ASCII -Path $cfgPath -Value @"
AvdId=$avdName
avd.ini.displayname=Lumina Dev
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
image.sysdir.1=system-images\android-36\google_apis_playstore\x86_64\
tag.id=google_apis_playstore
tag.display=Google Play
PlayStore.enabled=true
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.ramSize=2560
vm.heapSize=384
disk.dataPartition.size=12G
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.keyboard=yes
hw.mainKeys=no
hw.initialOrientation=portrait
showDeviceFrame=no
fastboot.forceColdBoot=no
"@
}

# ---------------------------------------------------------------- 2. Build FIRST (before emulator claims RAM)
if (-not $PhotosOnly -and -not $NoBuild) {
    $needsBuild = $ForceBuild -or (-not (Test-Path $apkPath))
    if (-not $needsBuild) {
        $apkTime = (Get-Item $apkPath).LastWriteTime
        $newestSrc = Get-ChildItem -Path (Join-Path $repo 'app\src\main'), (Join-Path $repo 'app\build.gradle.kts') -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newestSrc -and $newestSrc.LastWriteTime -gt $apkTime) {
            $needsBuild = $true
        }
    }
    if ($needsBuild) {
        Step 'Building debug APK (--no-daemon so Gradle frees RAM immediately)'
        Push-Location $repo
        try {
            & .\gradlew.bat assembleDebug --no-daemon --max-workers=2 --console=plain
            if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
        } finally { Pop-Location }
    } else {
        Write-Host 'Debug APK is already up to date — skipping Gradle build.' -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------- 3. Boot emulator
function Get-EmulatorSerial {
    (& $adb devices) | Select-String '^(emulator-\d+)\s+device' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
}
$serial = Get-EmulatorSerial
if (-not $serial) {
    Step 'Starting emulator (2.5 GB RAM, GPU accelerated)'
    $emuArgs = @('-avd', $avdName, '-memory', '2560', '-cores', '2', '-netdelay', 'none', '-netspeed', 'full', '-gpu', 'host')
    if ($ColdBoot) { $emuArgs += '-no-snapshot-load' }
    if ($Wipe) {
        $emuArgs += '-wipe-data'
        Remove-Item (Join-Path $repo '.dev-emulator-photos.txt') -ErrorAction SilentlyContinue
    }
    $emuLog = Join-Path $env:TEMP 'lumina-emulator.log'
    Start-Process -FilePath $emulator -ArgumentList $emuArgs -RedirectStandardOutput $emuLog -RedirectStandardError "$emuLog.err" | Out-Null
    $deadline = (Get-Date).AddMinutes(5)
    do { Start-Sleep 2; $serial = Get-EmulatorSerial } until ($serial -or (Get-Date) -gt $deadline)
    if (-not $serial) { Get-Content $emuLog, "$emuLog.err" -Tail 30 -ErrorAction SilentlyContinue; throw 'Emulator did not come online. Log above.' }
    Write-Host "Waiting for Android to finish booting on $serial ..."
    do {
        Start-Sleep 3
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    } until ($booted -eq '1' -or (Get-Date) -gt $deadline)
    if ($booted -ne '1') { Get-Content $emuLog, "$emuLog.err" -Tail 30 -ErrorAction SilentlyContinue; throw 'Android did not finish booting. Log above.' }
    Start-Sleep 3
}
Write-Host "Using $serial"

# ---------------------------------------------------------------- 4. Photos from the PC
if (-not $SkipPhotos) {
    Step "Syncing up to $PhotoCount photos across albums from $PicturesDir"
    $exts = @('.jpg', '.jpeg', '.png', '.heic', '.heif', '.webp', '.gif')
    if ($IncludeVideos) { $exts += @('.mp4', '.mov', '.mkv', '.3gp', '.webm') }
    $videoExts = @('.mp4', '.mov', '.mkv', '.3gp', '.webm')

    # Pick a balanced mix across every subfolder so Albums tab shows real albums (not just screenshots),
    # and skip Lightroom preview caches (*.lrdata*).
    $topDirs = Get-ChildItem -Path $PicturesDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.lrdata*' -and $_.Name -ne 'Lightroom' -and $_.Name -ne 'presets' }
    $perAlbumCap = if ($topDirs.Count -gt 0) { [math]::Max(20, [math]::Ceiling($PhotoCount / [math]::Max(1, $topDirs.Count))) } else { $PhotoCount }
    $picked = New-Object System.Collections.Generic.List[IO.FileInfo]

    # Top-level loose files in Pictures
    Get-ChildItem -Path $PicturesDir -File -ErrorAction SilentlyContinue |
        Where-Object { $exts -contains $_.Extension.ToLowerInvariant() -and $_.Length -ge 2048 } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First $perAlbumCap |
        ForEach-Object { $picked.Add($_) }

    foreach ($dir in $topDirs) {
        Get-ChildItem -Path $dir.FullName -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object {
                $_.FullName -notlike '*.lrdata*' -and
                $exts -contains $_.Extension.ToLowerInvariant() -and
                $_.Length -ge 2048 -and
                ($_.Length -le ($MaxVideoMB * 1MB) -or $videoExts -notcontains $_.Extension.ToLowerInvariant())
            } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First $perAlbumCap |
            ForEach-Object { $picked.Add($_) }
    }
    $files = $picked | Sort-Object LastWriteTime -Descending | Select-Object -First $PhotoCount

    $manifestPath = Join-Path $repo '.dev-emulator-photos.txt'
    $already = @{}
    if (Test-Path $manifestPath) { Get-Content $manifestPath | ForEach-Object { $already[$_] = $true } }
    $createdDirs = @{}
    $touchCmds = New-Object System.Collections.Generic.List[string]
    $pushed = 0
    foreach ($f in $files) {
        if ($already.ContainsKey($f.FullName)) { continue }
        $rel = $f.FullName.Substring($PicturesDir.Length).TrimStart('\')
        $album = if ($rel.Contains('\')) { ($rel.Split('\')[0] -replace '[^\w\- ]', '_') } else { 'Pictures' }
        $remoteDir = "$remoteRoot/$album"
        if (-not $createdDirs.ContainsKey($remoteDir)) {
            & $adb -s $serial shell "mkdir -p '$remoteDir'" | Out-Null
            $createdDirs[$remoteDir] = $true
        }
        $safeName = $f.Name -replace "'", ""
        & $adb -s $serial push --sync "$($f.FullName)" "$remoteDir/$safeName" 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $stamp = $f.LastWriteTime.ToString('yyyyMMddHHmm.ss')
            $touchCmds.Add("touch -m -t $stamp '$remoteDir/$safeName'")
            Add-Content $manifestPath $f.FullName
            $pushed++
            if ($pushed % 25 -eq 0) { Write-Host "  pushed $pushed / $($files.Count) ..." }
        }
    }
    # Apply timestamps in batched shell calls instead of 1 adb process per file
    for ($k = 0; $k -lt $touchCmds.Count; $k += 30) {
        $batch = ($touchCmds.GetRange($k, [math]::Min(30, $touchCmds.Count - $k))) -join '; '
        & $adb -s $serial shell $batch 2>&1 | Out-Null
    }
    Write-Host "Pushed $pushed new files ($($files.Count - $pushed) already on the emulator)."
    if ($pushed -gt 0) {
        Step 'Asking Android to index the new media'
        & $adb -s $serial shell 'content call --uri content://media --method scan_volume --arg external_primary' 2>&1 | Out-Null
    }
}
if ($PhotosOnly) { return }

# ---------------------------------------------------------------- 5. Install (only if APK changed or not installed) & launch
if (-not (Test-Path $apkPath)) { throw "Debug APK not found at $apkPath. Run without -NoBuild." }
$installedStampPath = Join-Path $repo '.dev-emulator-installed.txt'
$apkStamp = (Get-Item $apkPath).LastWriteTimeUtc.ToString('o')
$pkgInstalled = (& $adb -s $serial shell pm list packages $package 2>$null | Out-String) -match [regex]::Escape($package)
$alreadySameApk = $pkgInstalled -and (Test-Path $installedStampPath) -and ((Get-Content $installedStampPath -Raw).Trim() -eq $apkStamp)

if (-not $alreadySameApk -or $ForceBuild) {
    Step 'Installing debug APK onto emulator'
    & $adb -s $serial install -r -d $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed.' }
    Set-Content -Path $installedStampPath -Value $apkStamp
} else {
    Write-Host 'Same debug APK already installed — skipping reinstall.' -ForegroundColor DarkGray
}

foreach ($perm in 'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO', 'android.permission.ACCESS_MEDIA_LOCATION', 'android.permission.POST_NOTIFICATIONS') {
    & $adb -s $serial shell pm grant $package $perm 2>&1 | Out-Null
}
Step 'Launching Lumina (debug)'
& $adb -s $serial shell am force-stop $package | Out-Null
& $adb -s $serial shell monkey -p $package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Host "`nDone! After editing code run:  .\tools\dev-emulator.ps1 -SkipPhotos" -ForegroundColor Green
