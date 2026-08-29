# CarTheftGuard

Android control app for the JC-ESP32P4-M3 board.

## Current milestone

The first app screen controls the ESP32-P4 LED blink half-period over BLE. Android scan, connection, command write, and visible LED timing change are confirmed on hardware.

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
2. Wait for the app to find `JC-P4-C6`.
3. After status changes to `Ready`, choose a preset or enter a half-period in milliseconds.
4. Tap `Send Frequency`.

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

The screen title must show `CarTheftGuard v0.1.2`; this identifies the version that lists all discovered BLE devices and subscribes to the response characteristic.

## Next work

- Validate `OK` and `ERR` notifications from the board on a phone.
- Add BLE-based Wi-Fi provisioning for SSID/password after LED control is stable.
