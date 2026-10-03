# CarTheftGuard watch app

Zepp OS mini app for the Amazfit Balance (API 3.0, round 480x480). Version 0.1 is
a single "LED speed" screen: SLOWER / FASTER send `freq <ms>` to the board over
BLE and show the board's reply.

## Board protocol

The board advertises as `JC-P4-C6` with service `0xFFF0`:

- `0xFFF1`: write `freq <ms>` (10-60000)
- `0xFFF2`: read / notify, replies `OK freq=<ms> ms` or `ERR ...`

The watch connects only with full 128-bit UUIDs (`0000fff0-0000-1000-8000-00805f9b34fb`
form) and `pair: false`; short UUIDs and the library's default pairing both failed.
The BLE helper is a vendored copy of Zepp's MIT-licensed easy-ble (`lib/ble-master.js`).

## Install on the watch

The QR-scan install did not work on our watch; Bridge install does.

1. Zepp app on the phone: Developer Mode, "+", Bridge (keep the Zepp app open).
2. Install the CLI once: `npm.cmd install -g @zeppos/zeus-cli` (use the `.cmd`
   shims on PowerShell setups that block `.ps1` scripts).
3. In this folder: `zeus.cmd bridge`, then at `bridge$`: `connect`, then `install`
   and choose "Amazfit Balance".

The firmware side lives in `JC-ESP32S3-CAN` (`src/bringup_s3.c`).
