<#
.SYNOPSIS
    Installs the release APK onto the connected phone without losing any state.

.DESCRIPTION
    Deliberately narrow. It never uninstalls, and it never runs an instrumented
    test, because both of those are destructive here:

      * `adb uninstall` (and the uninstall Gradle performs after
        connectedAndroidTest, including on failure) wipes the downloaded Whisper
        model, the RECORD_AUDIO grant, the battery exemption, and every
        transcript recorded so far.
      * SummaryEngineInstrumentedTest.seed() and AcousticCaptureInstrumentedTest
        call deleteAll() on the real database.

    `install -r` keeps all of it. This build also carries Room MIGRATION_1_2
    across a live database with fallbackToDestructiveMigration deliberately
    absent, so the first thing checked after installing is that the database
    still opens.

.EXAMPLE
    powershell -File scripts\install-on-phone.ps1
#>
[CmdletBinding()]
param(
    [string]$Apk,
    [int]$WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'

# `am start` below deliberately has no `2>&1`. Windows PowerShell 5.1 wraps a
# native command's redirected stderr in a NativeCommandError record, and under
# 'Stop' that becomes terminating -- so `am start` printing its harmless
# "Activity not started, intent has been delivered to currently running top-most
# instance" (which it does whenever Echo is already open) aborted the script
# after a perfectly good install.

# Resolved here rather than as a param default: under `powershell -File`,
# $PSScriptRoot is not reliably populated while the param block is being bound,
# and the default silently became "\..\app\build\..." -- a relative path that
# resolves against the caller's working directory and finds nothing.
if (-not $Apk) {
    $root = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $Apk = Join-Path $root "..\app\build\outputs\apk\release\app-release.apk"
}

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $Apk)) { throw "APK not found at $Apk -- run: .\gradlew :app:assembleRelease -PechoAbi=arm64-v8a" }

Write-Host "Waiting for a device (up to $WaitSeconds s). Plug the phone in and allow USB debugging." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$serial = $null
while ((Get-Date) -lt $deadline) {
    # `adb reconnect offline` is here because this phone drops to `offline`
    # regularly; without it the loop watches a device it can never talk to.
    & $adb reconnect offline 2>&1 | Out-Null
    $line = (& $adb devices) | Where-Object { $_ -match "\sdevice$" } | Select-Object -First 1
    if ($line) { $serial = ($line -split "\s+")[0]; break }
    Start-Sleep -Seconds 2
}
if (-not $serial) { throw "No device appeared within $WaitSeconds s." }
Write-Host "Device: $serial" -ForegroundColor Green

# State worth knowing before the install kills the process.
$before = & $adb -s $serial shell dumpsys package com.mandar.echo 2>&1 |
    Select-String -Pattern 'versionCode|RECORD_AUDIO: granted' | Select-Object -First 3
if ($before) { Write-Host "Installed now:"; $before | ForEach-Object { Write-Host "  $_" } }

Write-Host "`nInstalling $([math]::Round((Get-Item $Apk).Length/1MB,1)) MB (keeping data)..." -ForegroundColor Cyan
$out = & $adb -s $serial install -r $Apk
$out | ForEach-Object { Write-Host "  $_" }
# `$out` is an array of lines, and `-notmatch` on an array returns the elements
# that do NOT match -- which is non-empty, and therefore truthy, on every
# successful install (adb also prints "Performing Streamed Install"). Written the
# obvious way, this threw on success and reported a failure that had not happened.
if (-not ($out -match 'Success')) { throw "install -r failed -- data left untouched." }

# Clear the log first so the only Room output on screen belongs to this launch.
& $adb -s $serial logcat -c 2>&1 | Out-Null
& $adb -s $serial shell am start -n com.mandar.echo/.MainActivity | Out-Null
Start-Sleep -Seconds 6

Write-Host "`n--- database opened? ---" -ForegroundColor Cyan
$crash = & $adb -s $serial logcat -d -t 400 2>&1 |
    Select-String -Pattern 'IllegalStateException|Migration didn|Room cannot verify|AndroidRuntime'
if ($crash) {
    Write-Host "MIGRATION PROBLEM -- transcripts are still on the device, the app just cannot open them:" -ForegroundColor Red
    $crash | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
} else {
    Write-Host "  no migration error in the log" -ForegroundColor Green
}

Write-Host "`n--- pipeline ---" -ForegroundColor Cyan
& $adb -s $serial logcat -d -t 400 2>&1 |
    Select-String -Pattern 'TranscriptionPipeline|CloudTranscriber|CloudGate|RecordingService' |
    Select-Object -Last 25 | ForEach-Object { Write-Host "  $_" }

Write-Host "`nTail live with:" -ForegroundColor DarkGray
Write-Host "  $adb -s $serial logcat -s TranscriptionPipeline:I CloudTranscriber:I CloudGate:W" -ForegroundColor DarkGray
