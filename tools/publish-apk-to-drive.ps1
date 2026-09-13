<#
Uploads the debug APK to the shared "CarTheftGuard" Drive folder, overwriting
the previous build in place (same file ID/link every time). The app's About
tab reads this same folder automatically (see DriveUpdates.java) -- this
script is what makes a build show up there without any manual file transfer.
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

Write-Output "Uploaded. Shareable link:"
& $rclone link "gdrive:CarTheftGuard/app-debug.apk"
