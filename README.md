# CarTheftGuard

Android control app for the JC-ESP32P4-M3 board.

## Current milestone

BLE scan/connect, Wi-Fi provisioning over BLE, and frequency control over the local Wi-Fi network's HTTP API are all validated end-to-end on hardware. The Android app connects over BLE, sends Wi-Fi credentials, waits for the board to join the network and report its IP, then sends `freq <ms>` commands directly over Wi-Fi (BLE is only used for provisioning and status, not for the actual frequency commands).

Firmware endpoint:

- BLE device name: `JC-P4-C6`
- Service UUID: `0xFFF0`
- Write characteristic UUID: `0xFFF1`
- Response characteristic UUID: `0xFFF2` (read and notify)
- Command payload: UTF-8 text, for example `freq 250`
- Valid range: `freq 10` through `freq 60000`

The firmware runs on the ESP32-P4 and uses the onboard ESP32-C6 as an ESP-Hosted radio co-processor over SDIO.

## Build

Open this folder in Android Studio:

```text
C:\projects\CarTheftGuard
```

Then let Android Studio sync Gradle and run the `app` configuration on an Android phone.

The Gradle wrapper builds the debug APK with JDK 17. Android Studio can install it directly to a connected phone.

## Phone setup

1. Enable Bluetooth on the phone.
2. Install the app from Android Studio.
3. Grant Bluetooth permissions when Android asks.
4. Make sure the board firmware is flashed and advertising as `JC-P4-C6`.

## Run

1. Tap `Scan`.
2. Connect to `JC-P4-C6`.
3. Tap `Refresh Wi-Fi Networks` and tap the network name that the phone and board should share. Hidden networks can still be entered manually.
4. Enter the Wi-Fi password.
5. Tap `Connect Board to Wi-Fi`.
6. Wait for `Wi-Fi ready: <board-ip>`.
7. Choose a preset or enter a half-period in milliseconds.
8. Tap `Send Frequency Over Wi-Fi`.

Android may ask for Location permission before it can display the Wi-Fi network list. Enable Wi-Fi and Location services on the phone, then tap `Refresh Wi-Fi Networks`. Android may throttle repeated Wi-Fi scans; when that happens the app shows the latest available list.

Once the board is connected over BLE or Wi-Fi, the matching "Scan for Board" / "Connect Board to Wi-Fi" section folds away automatically to reduce clutter. Tapping a Wi-Fi network in the list also folds that list so the password field is front and center. Use **Restart Connection** (near the top) to reset everything and start a fresh scan, and **Clear Log** to clear the message list.

BLE is used only to provision Wi-Fi and report the assigned board IP. The frequency request is then sent over the local Wi-Fi network to:

```text
POST http://<board-ip>/api/frequency
Body: freq <ms>
```

Examples sent by the app:

```text
freq 100
freq 250
freq 500
freq 1000
```

The LED should change blink speed immediately. The app waits for the board's `0xFFF2` notification and displays the actual response, for example:

```text
OK freq=250 ms
```

The screen title shows the current app version, currently `CarTheftGuard v0.2.8`.

## Next work

- Replace plain local HTTP with authenticated HTTPS before using the control API on an untrusted network.
- rclone is used to publish debug builds to Drive (`tools/publish-apk-to-drive.ps1`); its shared client_id is being retired by Google during 2026, so a dedicated OAuth client may be needed later.
