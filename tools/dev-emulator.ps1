<#
.SYNOPSIS
  Lumina Gallery local dev loop: runs the app in a lightweight, debloated Android emulator
  filled with photos from your PC Pictures folder. No phone, no release APK, no GitHub push needed.

.EXAMPLES
  .\tools\dev-emulator.ps1                       # start emulator, debloat once, sync photos, build + install + launch
  .\tools\dev-emulator.ps1 -SkipPhotos           # after a code change: fast x86_64 debug rebuild + reinstall + relaunch
  .\tools\dev-emulator.ps1 -NoBuild -SkipPhotos  # just launch emulator + existing debug APK (zero Gradle RAM)
  .\tools\dev-emulator.ps1 -PhotoCount 1500 -IncludeVideos
  .\tools\dev-emulator.ps1 -PhotosOnly           # only (re)sync photos
  .\tools\dev-emulator.ps1 -Debloat              # re-apply guest debloat (disables unused Google background apps)
  .\tools\dev-emulator.ps1 -NoAudio              # boot without audio device emulation
  .\tools\dev-emulator.ps1 -FullHD               # use 1080x2400@420 instead of lightweight 720x1600@280 (same dp layout)
  .\tools\dev-emulator.ps1 -Wipe                 # fresh emulator (factory reset)

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
    [switch]$Wipe,
    [switch]$Debloat,
    [switch]$NoAudio,
    [switch]$FullHD
)

$ErrorActionPreference = 'Continue'   # adb/gradle print harmless warnings on stderr
$repo = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:JAVA_HOME = 'C:\Users\corne\.jdks\jdk-21.0.13.1+2'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avdName = 'Lumina_Dev'
$package = 'com.luminagallery.drive.debug'
$mainActivity = "$package/com.luminagallery.drive.MainActivity"
$remoteRoot = '/sdcard/Pictures/PC'
$apkPath = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'

$ramMB = 2560
$cpuCores = 4
# 720x1600 @ 280 dpi = 411.4dp x 914.3dp (exact same Compose layout as 1080x2400 @ 420 dpi, 56% fewer pixels)
$lcdWidth = if ($FullHD) { 1080 } else { 720 }
$lcdHeight = if ($FullHD) { 2400 } else { 1600 }
$lcdDensity = if ($FullHD) { 420 } else { 280 }

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

function Get-EmulatorSerial {
    (& $adb devices) | Select-String '^(emulator-\d+)\s+device' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
}

function Set-ConfigValue([string]$path, [hashtable]$updates) {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    if (Test-Path $path) {
        foreach ($l in (Get-Content $path)) { $lines.Add([string]$l) }
    }
    $changed = $false
    foreach ($key in $updates.Keys) {
        $val = "$($updates[$key])"
        $prefix = "$key="
        $idx = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i].StartsWith($prefix)) { $idx = $i; break }
        }
        if ($idx -ge 0) {
            if ($lines[$idx] -ne "$prefix$val") {
                $lines[$idx] = "$prefix$val"
                $changed = $true
            }
        } else {
            $lines.Add("$prefix$val")
            $changed = $true
        }
    }
    if ($changed) {
        Set-Content -Encoding ASCII -Path $path -Value $lines
    }
    return $changed
}

# ---------------------------------------------------------------- 1. AVD (lightweight 2 GB RAM, 4 vCPU, 720p same-dp display)
# Written by hand: avdmanager on this SDK produces a broken template config (devices.xml bug).
$avdRoot = Join-Path $env:USERPROFILE '.android\avd'
$avdDir = Join-Path $avdRoot "$avdName.avd"
$cfgPath = Join-Path $avdDir 'config.ini'
$configChanged = $false
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
hw.cpu.ncore=$cpuCores
image.sysdir.1=system-images\android-36\google_apis_playstore\x86_64\
tag.id=google_apis_playstore
tag.display=Google Play
PlayStore.enabled=true
hw.lcd.width=$lcdWidth
hw.lcd.height=$lcdHeight
hw.lcd.density=$lcdDensity
hw.ramSize=$ramMB
vm.heapSize=384
disk.dataPartition.size=12G
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.keyboard=yes
hw.mainKeys=no
hw.initialOrientation=portrait
hw.camera.back=none
hw.camera.front=none
hw.audioInput=no
hw.sdCard=no
hw.trackBall=no
hw.dPad=no
showDeviceFrame=no
fastboot.forceColdBoot=no
"@
} elseif (-not (Get-EmulatorSerial)) {
    # Keep existing AVD tuned to lightweight settings (preserving existing disk.dataPartition.size)
    $configChanged = Set-ConfigValue $cfgPath @{
        'hw.cpu.ncore'    = $cpuCores
        'hw.lcd.width'    = $lcdWidth
        'hw.lcd.height'   = $lcdHeight
        'hw.lcd.density'  = $lcdDensity
        'hw.ramSize'      = $ramMB
        'vm.heapSize'     = 384
        'hw.camera.back'  = 'none'
        'hw.camera.front' = 'none'
        'hw.audioInput'   = 'no'
        'hw.sdCard'       = 'no'
        'hw.trackBall'    = 'no'
        'hw.dPad'         = 'no'
    }
    if ($configChanged) {
        Write-Host "Updated $avdName config.ini (${ramMB}MB RAM, $cpuCores cores, ${lcdWidth}x${lcdHeight}@${lcdDensity}dpi) — cold-booting once to refresh Quick Boot snapshot." -ForegroundColor Yellow
        $ColdBoot = $true
    }
}

# ---------------------------------------------------------------- 2. Build FIRST (before emulator claims RAM, or incremental x86_64 when running)
if (-not $PhotosOnly -and -not $NoBuild) {
    $needsBuild = $ForceBuild -or (-not (Test-Path $apkPath))
    if (-not $needsBuild) {
        $apkTime = (Get-Item $apkPath).LastWriteTime
        $newestSrc = Get-ChildItem -Path (Join-Path $repo 'app\src\main'), (Join-Path $repo 'app\build.gradle.kts'), (Join-Path $repo 'gradle\libs.versions.toml') -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newestSrc -and $newestSrc.LastWriteTime -gt $apkTime) {
            $needsBuild = $true
        }
    }
    if ($needsBuild) {
        Step 'Building x86_64 debug APK (--no-daemon, 2 GB heap cap to spare laptop RAM)'
        Push-Location $repo
        try {
            & .\gradlew.bat assembleDebug `
                '-Plumina.debug.abi=x86_64' `
                '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' `
                --no-daemon --max-workers=2 --console=plain
            if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
        } finally { Pop-Location }
    } else {
        Write-Host 'Debug APK is already up to date — skipping Gradle build.' -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------- 3. Boot emulator
$serial = Get-EmulatorSerial
if (-not $serial) {
    Step "Starting emulator (${ramMB} MB RAM, $cpuCores cores, ${lcdWidth}x${lcdHeight}@${lcdDensity}dpi, GPU accelerated)"
    $emuArgs = @(
        '-avd', $avdName,
        '-memory', "$ramMB",
        '-cores', "$cpuCores",
        '-netdelay', 'none',
        '-netspeed', 'full',
        '-gpu', 'host',
        '-no-boot-anim',
        '-no-metrics',
        '-camera-back', 'none',
        '-camera-front', 'none'
    )
    if ($NoAudio) { $emuArgs += '-no-audio' }
    if ($ColdBoot) { $emuArgs += '-no-snapshot-load' }
    if ($Wipe) {
        $emuArgs += '-wipe-data'
        Remove-Item (Join-Path $repo '.dev-emulator-photos.txt'), (Join-Path $repo '.dev-emulator-debloated.txt'), (Join-Path $repo '.dev-emulator-installed.txt') -ErrorAction SilentlyContinue
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
    Start-Sleep 2
}
Write-Host "Using $serial"

# ---------------------------------------------------------------- 3b. One-time guest debloat (saves ~800+ MB guest RAM & stops Google Photos fighting MediaStore)
$debloatStampPath = Join-Path $repo '.dev-emulator-debloated.txt'
if ($Debloat -or -not (Test-Path $debloatStampPath)) {
    Step 'Disabling unused preinstalled background apps inside emulator (reversible; keeps Play Services & Drive auth)'
    $bloatPkgs = @(
        'com.google.android.apps.photos'
        'com.google.android.apps.wellbeing'
        'com.google.android.apps.messaging'
        'com.google.android.googlequicksearchbox'
        'com.google.android.as'
        'com.google.android.as.oss'
        'com.google.android.tts'
        'com.google.android.settings.intelligence'
        'com.google.android.dialer'
        'com.google.android.marvin.talkback'
        'com.google.android.accessibility.switchaccess'
        'com.google.android.apps.accessibility.voiceaccess'
        'com.google.android.apps.safetyhub'
        'com.google.android.projection.gearhead'
        'com.google.android.healthconnect.controller'
        'com.google.android.health.connect.backuprestore'
        'com.google.android.youtube'
        'com.google.android.apps.youtube.music'
        'com.google.android.apps.maps'
        'com.google.android.apps.docs'
        'com.google.android.gm'
        'com.google.android.calendar'
        'com.google.android.apps.restore'
        'com.google.android.feedback'
        'com.google.android.deskclock'
        'com.google.android.apps.wallpaper'
    )
    $cmd = ($bloatPkgs | ForEach-Object { "pm disable-user --user 0 $_ 2>/dev/null || true" }) -join '; '
    $out = & $adb -s $serial shell $cmd 2>&1
    $disabledCount = ($out | Select-String 'new state: disabled-user').Count
    Write-Host "Disabled $disabledCount unused system apps." -ForegroundColor DarkGray
    Set-Content -Path $debloatStampPath -Value (Get-Date -Format 'o')
}

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
    $apkMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
    Step "Installing debug APK ($apkMB MB) onto emulator"
    & $adb -s $serial install -r -d $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed.' }
    Set-Content -Path $installedStampPath -Value $apkStamp
} else {
    Write-Host 'Same debug APK already installed — skipping reinstall.' -ForegroundColor DarkGray
}

Step 'Launching Lumina (debug)'
$launchCmd = @(
    "pm grant $package android.permission.READ_MEDIA_IMAGES 2>/dev/null || true"
    "pm grant $package android.permission.READ_MEDIA_VIDEO 2>/dev/null || true"
    "pm grant $package android.permission.ACCESS_MEDIA_LOCATION 2>/dev/null || true"
    "pm grant $package android.permission.POST_NOTIFICATIONS 2>/dev/null || true"
    "am start -S -W -n $mainActivity"
) -join '; '
& $adb -s $serial shell $launchCmd 2>&1 | Out-Null
Write-Host "`nDone! After editing code run:  .\tools\dev-emulator.ps1 -SkipPhotos" -ForegroundColor Green
