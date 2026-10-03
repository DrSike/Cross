# Cross Android companion

Cross is a private, single-user Android companion for an Apple Watch app. The phone is a BLE GATT server/peripheral; the watch is the central. No login, cloud account, analytics, or multi-user identity is included.

## Project

- Kotlin, Jetpack Compose Material 3, Navigation Compose
- `minSdk 26`, `compileSdk 37`, `targetSdk 36`
- Application/package: `com.cross.app`
- Responsive Material 3 Expressive cards use window size classes and adaptive grids; expanded widths use a navigation rail, while compact phones use a bottom navigation bar. The theme follows the Android system light/dark appearance and uses the finite expressive motion scheme.
- `app/src/main/java/com/cross/app/ble/CrossBleServer.kt` owns the phone-side GATT server, advertising, reconnect state, RX writes, and TX notifications.

## Setup

1. Install Android Studio with an Android SDK 37 platform and build tools.
2. Clone and open this project as an existing Gradle project.
3. Let Android Studio download the pinned Gradle 8.14.5 distribution and Maven dependencies, then run the `app` configuration on an Android 8.0+ phone or emulator.
4. Enable Bluetooth. On Android 12+, grant Nearby devices permissions when prompted. On Android 13+, grant notification permission if you want system-facing notification UI.
5. Open Cross settings and enable its notification listener access. This is a protected system setting and cannot be granted by a normal runtime dialog.
6. You can optionally grant Health Connect permission so health data can be synced to other apps such as Google Fit.
7. Start BLE in the connection card, then launch the Apple Watch Cross companion and pair/connect from the watch side.
8. Cross trusts one bonded watch at a time. To move to another watch, use **Settings → Replace paired watch**, confirm the warning, and complete pairing with the replacement. Cross never resets this trust automatically.

## BLE and JSON protocol

Service UUID: `7F4F0001-9B6E-4C5B-8B4E-4A7B8B90C001`  
RX (watch writes): `7F4F0002-9B6E-4C5B-8B4E-4A7B8B90C001`  
TX (phone notifies): `7F4F0003-9B6E-4C5B-8B4E-4A7B8B90C001`

Every message is a strict schema-v2 JSON envelope. `request_id` is required, nonblank, and limited to 128 UTF-8 bytes on every message; `timestamp_ms` is a positive integral JSON number. ACKs additionally require `ack_for` with the same bound and an explicit Boolean `success`; optional `error` must be a string. Legacy `id`, `for`, and `health_snapshot` fields are rejected.

```json
{"schema_version":2,"type":"media_command","request_id":"uuid","timestamp_ms":1710000000000,"command":"next"}
```

Supported types are `hello`, `notification`, `notification_action`, `media_state`, `media_command`, `health_sync`, `find_device`, `ping`, and `ack`. JSON uses the watch-compatible `schema_version`/`request_id` envelope and is split into JSON chunk envelopes with `frame_id`, `chunk_index`, `chunk_count`, and standard base64 payload. Both platforms use 48 raw bytes per chunk so the expanded JSON envelope stays within common ATT notification limits. The implementation reassembles frames, acknowledges complete inbound messages, and keeps raw chunks below common ATT payload limits.

`health_sync` nests one generation under `health_sync`, with exact sleep sessions and stage intervals, workout sessions, and explicit deletions. Android uses the watch record ID as the stable Health Connect client identity and `generation_ms` as the client record version. It acknowledges health sync only after the generation is durably indexed and Health Connect succeeds; validation, provider, permission, and import failures receive a negative acknowledgement. Applied generation hashes and request-ID bindings make retransmissions idempotent without rolling records back.

## Feature notes

- Notifications default to allowed and can be explicitly allowed/blocked from Overview -> Manage notification rules. Cross discovers launchable installed apps and uses their package names internally; no package-name entry field is exposed. Posted events include package, title, text, timestamp, and action metadata. Action buttons and `RemoteInput` replies are executed through the original app's `PendingIntent` when Android exposes them.
- Media control is watch-led: commands arrive from Apple Watch, Android executes them through `MediaSessionManager`/the active `MediaController`, and the phone pushes authoritative metadata, playback state, position, and duration back to the watch. Android only exposes active sessions after notification-listener/media access is available; some OEM media apps expose incomplete metadata.
- Health data is pushed from the watch as lossless sleep and workout sessions. The latest generation is stored durably until Health Connect accepts it; unavailable-provider, missing-permission, in-progress, success, and failure states remain visible on Overview and Settings. Sleep stages are written on `SleepSessionRecord`; workouts use `ExerciseSessionRecord`, with associated active-energy and distance records when supplied. Android does not send health data back to the watch.
- The Android Find surface only sends a watch haptic request. The watch can send an explicit `target=phone` request when its own “Ring phone” action is used; ringtone behavior follows the phone's system ringtone and OEM audio policy.
- Useful logs use tags `CrossBleServer`, `CrossNotifications`, `MediaManager`, `DeviceLocator`, and `HealthRepository`.

## Platform limitations

- BLE GATT server support, advertising payload behavior, background execution, and notification action semantics vary by phone/OEM. Keep Cross allowed to run in the background and disable aggressive battery optimization if reconnection is unreliable.
- BLE advertising uses the Cross service UUID only in the primary packet to stay within the legacy 31-byte payload limit on Samsung and other devices.
- The phone and watch must implement the same Cross v2 service UUIDs and JSON envelope. Android is the GATT server; it does not scan for the watch in this project.
- RX, TX, and CCCD access require authenticated encrypted BLE. A bonded watch must subscribe and complete the v2 HELLO exchange on every connection before Cross accepts or sends application data. HELLO binds the watch identity to a per-install Android UUID that is generated and synchronously persisted before transport starts.
- Health Connect privacy details are available under **Settings → How Cross uses health data** and through the platform permission-usage/rationale entry points.
- Notification listener access is powerful system access and must be enabled manually. Apps may omit notification actions, redact content, or refuse a `PendingIntent` reply.
- MediaSessionManager returns only sessions visible to the current listener permission and may return no session when a player is paused or killed.
- `targetSdk 36` and Android edge-to-edge/OEM policy can change system bar insets; Compose window-size adaptation is implemented, but device-specific fold posture behavior should be verified on Samsung hardware.
