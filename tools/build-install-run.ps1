<#
Builds the debug APK, installs it on the connected phone, and launches it
cleanly. Uses "adb shell am start" (NOT "adb shell monkey ... 1") for the
launch -- monkey injects one random synthetic UI event after starting the
app, which can look like a spurious crash/minimize that never actually
happened. See memory: project-android-app-stability-20260911.
#>
param(
    [string]$Package = 'com.sarusiy.cartheftguard',
    [string]$Activity = '.MainActivity'
)

$ErrorActionPreference = 'Stop'
Set-Location "C:\projects\CarTheftGuard"

function Get-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $fallback) { return $fallback }
    throw "adb not found in PATH or at $fallback"
}

$adb = Get-Adb
$apk = "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Building..."
& .\gradlew.bat assembleDebug -q
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

Write-Host "Installing..."
& $adb install -r $apk

Write-Host "Launching..."
& $adb shell am force-stop $Package
Start-Sleep -Milliseconds 500
& $adb shell am start -n "$Package/$Activity"
Start-Sleep -Seconds 2

& $adb shell dumpsys activity activities | Select-String -Pattern "topResumedActivity"
