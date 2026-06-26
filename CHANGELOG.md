# Changelog

## v0.2.9-preview.11 - 2026-06-26

### Preview Fix

- Request Bluetooth scan/connect/advertise runtime permissions inside the glasses HUD so the preview.10 wake beacon can actually run on Android 12+ firmwares.
- Show the glasses-side wake beacon status in the HUD bridge status line when it is sent, unavailable, or blocked by permission.
- Do not treat the phone BLE wake paths as all-or-nothing: the new HUD-beacon scan can stay armed even if the old phone-advertising wake path is unavailable.
- Recreate the phone RFCOMM listener whenever a HUD wake/session starts, so a stale long-idle `accept()` socket cannot block reconnect after several hours.
- Tie the phone RFCOMM accept/read loop to its current job generation so old listener cleanup cannot close a fresh listener.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.7` reaches the glasses, then tap **Arm wake bridge**.
- If the glasses show a Bluetooth permission prompt after the helper update, allow it once; otherwise the HUD beacon fallback cannot wake the phone.
- This keeps the no-CXR-L runtime path. CXR-L remains setup-only.

## v0.2.9-preview.10 - 2026-06-26

### Preview Fix

- Add a reversed BLE wake path for long-idle launches: the phone now arms an Android `PendingIntent` BLE scan while the wake bridge is armed, and the glasses HUD emits a short wake beacon when it opens or retries.
- Keep the existing phone-advertises/glasses-scan wake path as a fast path, but no longer depend on the phone process already being alive after several hours.
- Start the phone foreground Bluetooth session from the HUD beacon receiver, then re-arm wake health before the glasses RFCOMM reconnects.
- Stop the glasses HUD beacon as soon as the HUD closes, so the new wake path does not add background battery drain on the glasses.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.6` reaches the glasses, then tap **Arm wake bridge**.
- The normal runtime still does not keep CXR-L connected. CXR-L remains setup-only; task loading/launching uses BLE wake plus Bluetooth RFCOMM.
- This preview specifically targets the "works first, then after a few hours the HUD cannot load tasks" report.

## v0.2.9-preview.9 - 2026-06-26

### Preview Fix

- Stop the HUD Bluetooth bridge immediately during helper close instead of waiting behind a best-effort "closing" status send.
- Ignore stale stop/status work from an older HUD session after a new Bluetooth session has already started.
- Tie the glasses RFCOMM read loop and BLE wake callbacks to their current operation so old close/reopen callbacks cannot cancel a fresh wake or reconnect.
- Reset local wake/request throttles on helper close so the next HUD open can wake the phone immediately without leaving hidden Bluetooth work running.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.5` reaches the glasses, then tap **Arm wake bridge**.
- This remains the no-CXR-L runtime path: CXR-L is only for install/update or phone-triggered HUD open; normal task loading/launching uses BLE wake plus Bluetooth RFCOMM.
- During long-idle testing, the HUD should still stop its glasses-side Bluetooth work as soon as it leaves the foreground.

## v0.2.9-preview.8 - 2026-06-26

### Preview Fix

- Close any in-flight glasses RFCOMM connection attempt immediately when the HUD leaves the foreground.
- Track and close the pending Bluetooth socket before waiting for the helper connect loop to finish, reducing the chance of hidden Bluetooth work after leaving Tasker Bridge.
- Make helper shutdown idempotent so rapid HUD close/reopen cannot leave the runtime in a stale started state.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.4` reaches the glasses, then tap **Arm wake bridge**.
- This keeps the preview.7 no-CXR-L runtime path and stale-phone fallback, but hardens the battery side of HUD shutdown.

## v0.2.9-preview.7 - 2026-06-26

### Preview Fix

- Keep Tasker Bridge on the no-CXR-L runtime path: CXR-L remains only for helper install/update and phone-triggered HUD opening.
- Update the glasses helper to recover from a stale remembered phone Bluetooth address after repeated connection failures, with throttled fallback searches, then remember the new phone only after a valid Tasker Bridge handshake.
- Show both bundled and last installed helper versions in the phone UI so testers can confirm the glasses helper was actually updated.
- Refresh the phone bridge summary copy to describe the passive Bluetooth listener that stays ready while the wake bridge is armed.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.3` reaches the glasses, then tap **Arm wake bridge**.
- Keep the Tasker Bridge notification running during long-idle testing.
- For this test, open the HUD from the glasses after a few hours; the runtime task list should load over Bluetooth without CXR-L.

## v0.2.9-preview.6 - 2026-06-26

### Preview Fix

- Keep the passive RFCOMM server ready while the foreground wake bridge is armed.
- BLE wake remains active and watched, but the HUD no longer depends on BLE success before it can connect to the phone.
- The phone still does not connect to the glasses or keep CXR-L alive while idle; it only listens for HUD connections.
- After an idle HUD session, return to the armed passive listener instead of closing the Bluetooth server.

### Upgrade Notes

- Install the new phone APK and tap **Arm wake bridge**.
- Keep the Tasker Bridge notification running during long-idle testing.
- Reinstall HUD only if the helper is older than bundled helper `0.2.6-preview.2`.

## v0.2.9-preview.5 - 2026-06-26

### Preview Fix

- Treat BLE wake as healthy only after advertising is confirmed, not merely after the GATT server exists.
- Keep the bridge shown as armed while the foreground service is repairing BLE wake, but surface the real BLE status in the notification.
- Make the watchdog re-check health after a repair attempt instead of assuming restart success.

## v0.2.9-preview.4 - 2026-06-26

### Preview Fix

- Add a foreground-service watchdog that checks BLE wake health while the bridge is armed.
- Detect missing GATT server, unconfirmed advertiser, advertiser failure, missing permission, and Bluetooth-off states.
- Restart BLE wake automatically when the watchdog finds the idle wake path unhealthy.
- Keep the watchdog out of the active RFCOMM session so task launch traffic is not interrupted.

### Upgrade Notes

- Install the new phone APK and tap **Arm wake bridge**.
- Keep the Tasker Bridge notification running during the long-idle test.
- The bundled glasses helper is still `0.2.6-preview.2`; reinstall HUD only if the helper is older.

## v0.2.9-preview.3 - 2026-06-26

### Preview Fix

- Keep the BLE wake advertiser alive inside a lightweight foreground `connectedDevice` service after the user arms the wake bridge.
- Do not keep the RFCOMM task channel open in that idle service; RFCOMM still opens only after the HUD sends `wake_tasks`.
- After a HUD session idles/disconnects, return to BLE-wake-only foreground mode instead of stopping the service and risking process cleanup.
- Re-arm the foreground wake service from companion presence and autostart receiver callbacks.

### Upgrade Notes

- Install the new phone APK.
- Tap **Link glasses for wake** / **Arm wake bridge** once and leave the Tasker Bridge notification running.
- `Install HUD` is only needed if the glasses helper is older than bundled helper `0.2.6-preview.2`.
- Test the old failure case by leaving the phone idle for a few hours, then opening the HUD from the glasses.

## v0.2.9-preview.2 - 2026-06-26

### Preview Fix

- Add Android Companion Device Manager linking for the glasses before arming BLE wake.
- Add a `CompanionDeviceService` so Android can bind Tasker Bridge while the linked glasses are nearby and allow background connected-device wake work.
- Request the companion background/foreground-service permissions used by the Relay wake path.
- Re-arm BLE wake from companion presence callbacks, boot/update, Bluetooth-on, and glasses ACL reconnect.
- Make the glasses HUD retry BLE wake while it is still waiting for the phone, so a late phone re-arm can recover without closing the HUD.

### Upgrade Notes

- Install the new phone APK first.
- Tap **Install HUD** to push bundled helper `0.2.6-preview.2`.
- Tap **Link glasses for wake** / **Arm wake bridge** on the phone and accept the Android companion-device prompt.
- After the companion link is accepted, leave the phone app and test launching the HUD again after a few hours.

## v0.2.9-preview.1 - 2026-06-24

### Preview

- Replace the always-on sticky RFCOMM listener with a Relay-style BLE wake doorbell.
- Arm a low-power BLE GATT endpoint on the phone; the glasses HUD writes `wake_tasks` when it opens.
- Open the foreground `connectedDevice` RFCOMM service only for the active HUD session, then stop it after idle/disconnect.
- Keep CXR-L as setup-only for installing and launching the glasses helper; runtime task commands do not require an active CXR-L session.
- Re-arm BLE wake after reboot, app update, Bluetooth-on, or glasses ACL reconnect when the user previously armed the bridge.

### Upgrade Notes

- Install the new phone APK first.
- Open Tasker Bridge, grant the new Bluetooth advertise permission if Android asks, then tap **Install HUD** to push the bundled helper `0.2.6-preview.1`.
- Tap **Arm wake bridge** once before testing from the glasses.
- This is a preview build for battery testing; if Android blocks a background wake on a specific phone firmware, open the phone app once and arm the bridge again.

## v0.2.8-bt - 2026-06-24

### Fixed

- Keep the passive phone Bluetooth bridge sticky so Android can restore the listener after the service is killed in the background.
- Preserve the explicit Stop action as non-sticky so users can still turn off the foreground bridge intentionally.

## v0.2.7-bt - 2026-06-23

### Changed

- The phone app no longer starts the foreground Bluetooth bridge just because the UI was opened.
- The Bluetooth runtime is inverted so the phone listens passively and the glasses HUD connects only while it is open, avoiding phone-side polling that can wake the glasses Bluetooth stack.
- The glasses HUD now stops its Bluetooth listener when it leaves the foreground, while still keeping the display awake when the HUD is visible.
- The foreground bridge can now be stopped from the phone UI or notification.

## v0.2.5-bt - 2026-06-05

Tasker Bridge now uses Bluetooth as its runtime command channel. CXR-L is kept only for one-shot helper setup: installing, updating, and launching the glasses HUD.

### Added

- Bluetooth RFCOMM runtime between the phone bridge and the glasses HUD.
- Versioned JSON protocol handshake with explicit phone/helper roles.
- App-level Bluetooth pairing lock based on the first successful Tasker Bridge service connection, not the public device name.
- Phone foreground `connectedDevice` bridge so HUD commands can reach Tasker while the phone app is in the background.
- Glasses HUD project grouping, measured scrolling, and R08-friendly accessibility actions.
- Cached task-list delivery followed by refresh-on-demand to reduce idle work.

### Changed

- CXR-L no longer stays active for runtime commands after setup completes.
- The phone app embeds the glasses helper APK and tracks the installed helper version.
- The HUD selection model is centralized so visual focus, scroll position, and activation use the same selected item.
- Bluetooth sends now report real write success/failure instead of optimistic delivery.

### Fixed

- R08 navigation could visually move while activating the wrong row.
- Unknown future protocol messages no longer crash decoding.
- Triple/quadruple-style launcher interactions are no longer affected by Tasker Bridge keeping a CXR runtime connection alive.

### Upgrade Notes

- Install the new phone APK first.
- Open Tasker Bridge on the phone and use **Install HUD** to push the bundled helper `0.2.3-bt` to the glasses.
- Use **Forget Bluetooth pairing** only if you want to pair the HUD with a different phone/glasses endpoint.
