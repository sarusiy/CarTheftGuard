<#
Clears logcat and tails it live, filtered to crash-relevant lines only, so
you can reproduce a real bug with your own finger on the phone and see the
stack trace immediately -- this is what actually caught the fetchCanMode()
crash on 2026-09-11, where synthetic "adb shell input tap" at recorded
coordinates did not reliably reproduce it. Ctrl+C to stop.
#>
param(
    [string]$Package = 'com.sarusiy.cartheftguard'
)

$ErrorActionPreference = 'Stop'

function Get-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $fallback) { return $fallback }
    throw "adb not found in PATH or at $fallback"
}

$adb = Get-Adb
& $adb logcat -c
Write-Host "Watching for crashes in $Package -- reproduce the bug now. Ctrl+C to stop."
& $adb logcat -v time | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|$Package.*Exception"
