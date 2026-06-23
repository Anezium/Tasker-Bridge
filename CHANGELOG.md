# Changelog

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
