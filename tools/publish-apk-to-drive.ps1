<#
Uploads the debug APK to the "CarTheftGuard-APK" folder in Google Drive,
overwriting the previous build in place (same file ID/link every time).
Requires the "gdrive" rclone remote to already be configured (rclone config).
#>
$rclone = "C:\Users\yossi\AppData\Local\Microsoft\WinGet\Packages\Rclone.Rclone_Microsoft.Winget.Source_8wekyb3d8bbwe\rclone-v1.75.0-windows-amd64\rclone.exe"
$apk = "C:\projects\CarTheftGuard\app\build\outputs\apk\debug\app-debug.apk"

if (-not (Test-Path $apk)) {
    Write-Error "APK not found at $apk - build it first (gradlew.bat :app:assembleDebug)"
    exit 1
}

& $rclone copyto $apk "gdrive:CarTheftGuard-APK/app-debug.apk"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Upload failed"
    exit 1
}

Write-Output "Uploaded. Shareable link:"
& $rclone link "gdrive:CarTheftGuard-APK/app-debug.apk"
