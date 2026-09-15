<#
Uploads the debug APK (plus a small companion .version.txt) to the shared
"CarTheftGuard" Drive folder, overwriting the previous build in place (same
file ID/link every time). The app's About tab reads this same folder
automatically (see DriveUpdates.java) -- this script is what makes a build
show up there without any manual file transfer. The companion version file
lets the About screen show what version you're ABOUT to install, next to
the currently-installed "Version" row, before you commit to it (added
2026-09-15, same reasoning as JC-ESP32P4-M3's publish script).
Requires the "gdrive" rclone remote to already be configured (rclone config).
#>
$rclone = "C:\Users\yossi\AppData\Local\Microsoft\WinGet\Packages\Rclone.Rclone_Microsoft.Winget.Source_8wekyb3d8bbwe\rclone-v1.75.0-windows-amd64\rclone.exe"
$apk = "C:\projects\CarTheftGuard\app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $apk)) {
    Write-Error "APK not found at $apk - build it first (gradlew.bat :app:assembleDebug)"
    exit 1
}

& $rclone copyto $apk "gdrive:CarTheftGuard/app-debug.apk"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Upload failed"
    exit 1
}

# Same "0.6.0-<hash>" scheme as build.gradle's versionName -- keep both in
# sync if that prefix ever changes.
$hash = git -C "C:\projects\CarTheftGuard" rev-parse --short HEAD
$version = "0.6.0-$hash"
$versionFile = Join-Path $env:TEMP "app-debug.version.txt"
Set-Content -Path $versionFile -Value $version -NoNewline
& $rclone copyto $versionFile "gdrive:CarTheftGuard/app-debug.version.txt"
Remove-Item $versionFile

Write-Output "Uploaded ($version). Shareable link:"
& $rclone link "gdrive:CarTheftGuard/app-debug.apk"
