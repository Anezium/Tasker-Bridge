# Changelog

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
