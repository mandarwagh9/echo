# Installs Echo on the first connected device/emulator.
#
#   powershell -ExecutionPolicy Bypass -File scripts\verify-on-device.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\verify-on-device.ps1 -RunTests
#
# By default this only installs, grants permissions and launches.
#
# -RunTests additionally runs the instrumented suite. That is DESTRUCTIVE: the
# summary tests call deleteAll() on the real app database, and Gradle uninstalls
# the app afterwards. Only pass it on a device whose recordings you are willing to
# lose -- never on a phone that has been collecting your day.

param(
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$root = Split-Path -Parent $PSScriptRoot

function Section($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

Section "Waiting for a device"
& $adb start-server | Out-Null
$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
if (-not $devices) {
    Write-Host "No device found." -ForegroundColor Red
    Write-Host @"

On the phone:
  1. Settings -> About phone -> tap 'Build number' 7 times
  2. Settings -> System -> Developer options -> enable 'USB debugging'
  3. Replug the cable, set USB mode to 'File transfer (MTP)'
  4. Accept the 'Allow USB debugging?' prompt

Then re-run this script.
"@
    exit 1
}
& $adb devices -l

Section "Device ABI"
$abi = (& $adb shell getprop ro.product.cpu.abi).Trim()
$sdkVer = (& $adb shell getprop ro.build.version.sdk).Trim()
$model = (& $adb shell getprop ro.product.model).Trim()
Write-Host "$model  ·  Android SDK $sdkVer  ·  $abi"

if ($abi -notin @("arm64-v8a", "x86_64")) {
    Write-Host "Unsupported ABI '$abi'. Add it to abiFilters in app/build.gradle.kts." -ForegroundColor Red
    exit 1
}
if ([int]$sdkVer -lt 29) {
    Write-Host "Echo needs Android 10 (SDK 29) or newer; this is SDK $sdkVer." -ForegroundColor Red
    exit 1
}

if ($RunTests) {
    Section "Running instrumented tests (DESTRUCTIVE)"
    Write-Host "This wipes existing transcripts and summaries on the device." -ForegroundColor Yellow
    Push-Location $root
    try {
        & "$root\gradlew.bat" :app:connectedReleaseAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Instrumented tests FAILED." -ForegroundColor Red
            Write-Host "Report: app\build\reports\androidTests\connected\release\index.html"
            exit 1
        }
    } finally { Pop-Location }
    Write-Host "All instrumented tests passed." -ForegroundColor Green
} else {
    Section "Skipping instrumented tests"
    Write-Host "Pass -RunTests to run them. They erase on-device recordings, so they" -ForegroundColor DarkGray
    Write-Host "are opt-in rather than part of a routine install." -ForegroundColor DarkGray
}

Section "Installing the app"
$apk = "$root\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
    Write-Host "APK missing. Run: .\gradlew.bat :app:assembleRelease" -ForegroundColor Red
    exit 1
}
& $adb install -r $apk

Section "Granting runtime permissions"
& $adb shell pm grant com.mandar.echo android.permission.RECORD_AUDIO
if ([int]$sdkVer -ge 33) {
    & $adb shell pm grant com.mandar.echo android.permission.POST_NOTIFICATIONS
}
# Long-running capture dies on many OEM builds without this.
& $adb shell dumpsys deviceidle whitelist +com.mandar.echo | Out-Null

Section "Launching"
& $adb shell am start -n com.mandar.echo/.MainActivity | Out-Null

Write-Host @"

Echo is installed and running.

On the phone:
  1. Settings tab -> Speech model -> download 'Base' (57 MB, one time)
  2. Optionally set Capture -> Chunk length -> 1 minute to see the
     full pipeline within a minute instead of ten
  3. Today tab -> tap the record button

Watch it work:
  $adb logcat -s RecordingService AudioChunker TranscriptionPipeline EchoWhisper SummaryScheduler
"@ -ForegroundColor Green
