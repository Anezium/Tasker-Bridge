# Changelog

## v0.2.9-preview.29 - 2026-06-26

### Preview Fix

- Keep HUD-visible task-list and launch retries alive after the initial retry ramp, with a slow two-minute cadence until the user leaves Tasker Bridge.
- Restart the phone Bluetooth session when a task list or launch acknowledgement cannot be delivered, instead of leaving the HUD stuck on a dead writer.
- Deduplicate in-flight launch request IDs on the phone so a slow acknowledgement cannot run the same Tasker task twice during extended HUD retries.
- Preserve the battery model: the new helper retry loop runs only while the Tasker Bridge HUD is foreground/visible and is cancelled by the existing close/onStop path.

### Upgrade Notes

- Install the new phone APK, then tap **Install HUD** so bundled helper `0.2.6-preview.16` reaches the glasses.
- Tap **Arm wake bridge** again before long-idle testing.
- This is still not a CXR-L keepalive; runtime CXR remains visible-HUD fallback only.

## v0.2.9-preview.23 - 2026-06-26

### Preview Fix

- Make HUD task launches wait for a phone `LaunchResult` acknowledgement instead of closing after a local socket write, so stale Bluetooth writers can no longer hide a failed launch.
- Add per-launch request IDs and phone-side result caching so retrying the same HUD tap does not run the same Tasker task twice if only the acknowledgement was lost.
- Redeliver active HUD session start intents if Android kills the foreground service mid-wake, and rearm wake health if the phone task is removed.
- Extend task-list and launch retries while the HUD is visibly open, giving the phone several minutes to recover from deep idle without adding any glasses background keepalive.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.14` reaches the glasses, then tap **Arm wake bridge**.
- A successful task tap should now keep the HUD open until the phone confirms the launch. If it cannot confirm, it will keep retrying instead of silently disappearing.
- If Alan can still reproduce after this build, send the full `Wake debug ...` line and whether the HUD stayed open on `Launch sent...` or stayed on `Waiting for phone link`.

## v0.2.9-preview.22 - 2026-06-26

### Preview Fix

- Add a pending launch retry window on the glasses HUD: if a task is tapped while the phone link is stale, the HUD now wakes the phone and retries the launch request instead of failing once.
- Add a phone-side RFCOMM idle watchdog so the classic Bluetooth listener is periodically rebuilt while the wake bridge is armed, matching the existing BLE wake rebuild strategy.
- Push cached tasks as soon as the runtime CXR fallback becomes ready, even if the HUD request message was lost during reconnect.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.13` reaches the glasses, then tap **Arm wake bridge**.
- The new retry work still runs only while the Tasker Bridge HUD is open; it does not add a glasses background keepalive.
- If Alan can still reproduce after this build, send the full `Wake debug ...` line, which now includes `rfcomm=...`.

## v0.2.9-preview.21 - 2026-06-26

### Preview Fix

- Add a CXR-L/CXR-S task transport fallback for the visible HUD session: if BLE wake starts the phone but RFCOMM does not deliver the task list, the phone can send the same Tasker protocol over CXR.
- Keep the battery model conservative: the glasses CXR bridge starts only when the Tasker Bridge HUD opens and is stopped by the existing HUD close/onStop path.
- Make HUD status copy transport-neutral, so testers see `Waiting for phone link` instead of a Bluetooth-only message when CXR fallback is also available.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.12` reaches the glasses, then tap **Arm wake bridge**.
- This is still not a background CXR keepalive. BLE wake remains the idle path; CXR is used only during the active HUD session as a fallback writer.
- If long-idle launch still fails, send whether the phone has Hi Rokid authorization/Wi-Fi enabled, the `Wake debug ...` line, and the HUD status text.

## v0.2.9-preview.20 - 2026-06-26

### Preview Fix

- Add a HUD-visible-only reverse RFCOMM fallback: while Tasker Bridge is open on the glasses, the helper also listens for the phone to call back.
- When a long-idle wake starts a phone HUD session, the phone now both listens for the glasses and briefly tries to connect out to the last trusted glasses address, avoiding stale one-way RFCOMM listener failures.
- Keep idle battery behavior unchanged: the new glasses listener exists only while the HUD is open and is closed by the existing HUD `close()` path.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.11` reaches the glasses, then tap **Arm wake bridge**.
- This is still not a CXR-L keepalive. Runtime tasks remain BLE wake plus RFCOMM, with a second RFCOMM direction only during the visible HUD session.
- If long-idle launch still fails, open the phone app immediately and send the full `Wake debug ...` line plus whether the HUD ever shows phone Bluetooth connected.

## v0.2.9-preview.19 - 2026-06-26

### Preview Fix

- Add phone-side wake maintenance details to the `Wake debug` line, including the scheduled rearm mode, next rearm estimate, and last BLE wake rebuild age.
- Keep the long-idle repair strategy phone-only: no helper changes, no longer glasses beacon window, and no CXR-L runtime reconnect.
- Preserve preview.18's forced phone BLE wake rebuild cadence while making future long-idle failures easier to diagnose without opening or rearming the bridge first.

### Upgrade Notes

- Install the new phone APK, then tap **Arm wake bridge** once.
- Bundled HUD helper remains `0.2.6-preview.10`; reinstall HUD only if it is older.
- If long-idle wake still fails, open the phone app immediately and send the full `Wake debug ...` line.

## v0.2.9-preview.18 - 2026-06-26

### Preview Fix

- Make the foreground wake watchdog forcibly rebuild the phone BLE wake primitives about every ten minutes while idle, so long-idle recovery does not depend only on Android alarm delivery.
- Reuse the stronger rebuild path from preview.17 for both stale-health and scheduled maintenance cases.
- Keep the helper unchanged and keep all extra maintenance phone-side only.

### Upgrade Notes

- Install the new phone APK, then tap **Arm wake bridge** once.
- Bundled HUD helper remains `0.2.6-preview.10`; reinstall HUD only if it is older.

## v0.2.9-preview.17 - 2026-06-26

### Preview Fix

- Make the scheduled phone wake rearm forcibly rebuild the BLE GATT advertiser and HUD-beacon scan instead of trusting stale in-memory `active` flags.
- Keep boot, Bluetooth-on, and companion rearm paths lightweight, while scheduled long-idle maintenance now closes and recreates the phone BLE wake primitives.
- Preserve the glasses battery model: no helper changes and no additional glasses background work.

### Upgrade Notes

- Install the new phone APK, then tap **Arm wake bridge** once so the phone schedules the stronger long-idle rearm.
- Bundled HUD helper remains `0.2.6-preview.10`; reinstall HUD only if it is older.

## v0.2.9-preview.16 - 2026-06-26

### Preview Fix

- Add a phone-side scheduled rearm for the wake bridge so Android can restore the BLE HUD-beacon scan and foreground wake service if they are dropped during long idle.
- Stop recording normal `HUD beacon scan armed` events as wake diagnostics, preserving the useful failure/wake evidence for testers.
- Keep glasses battery behavior unchanged: the scheduled repair runs on the phone only and does not add any glasses background work.

### Upgrade Notes

- Install the new phone APK.
- Bundled HUD helper remains `0.2.6-preview.10`; tap **Install HUD** only if the glasses are still older than preview.15's helper.
- Tap **Arm wake bridge** after install so the phone schedules the long-idle rearm.

## v0.2.9-preview.15 - 2026-06-26

### Preview Fix

- Recreate the phone RFCOMM listener on every HUD wake, even if the phone still thinks an old HUD socket is connected, so long-idle stale sockets cannot block the new HUD session.
- Add hard timeouts to the glasses RFCOMM connect and phone/glasses handshake paths, forcing retries instead of letting a dead Bluetooth socket hang the HUD forever.
- Keep the battery model unchanged: the new retries only run while the HUD is visible and still waiting for the phone.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.10` reaches the glasses, then tap **Arm wake bridge**.
- This still keeps CXR-L setup-only; runtime task loading uses BLE wake plus RFCOMM, with stale long-idle sockets now force-reset.

## v0.2.9-preview.14 - 2026-06-26

### Preview Fix

- Make phone app diagnostic refresh read-only so opening Tasker Bridge after a long-idle HUD failure does not re-arm BLE and overwrite the `Wake debug ...` evidence.
- Keep active repair paths in the foreground wake service, HUD wake session, explicit Arm wake bridge action, and watchdog.
- Preserve the no-drain glasses behavior: no extra HUD background work, no longer beacon window, and no CXR-L runtime reconnect.

### Upgrade Notes

- Install the new phone APK.
- Bundled HUD helper remains `0.2.6-preview.9`; tap **Install HUD** only if the glasses do not already show that bundled helper from preview.13.
- If long-idle launch still fails, open the phone app immediately and send the `Wake debug ...` line before tapping Arm/Launch again.

## v0.2.9-preview.13 - 2026-06-26

### Preview Fix

- Refresh the phone wake diagnostics whenever the phone app is opened or resumed, so the `Wake debug ...` line reflects the latest long-idle failure instead of a stale runtime snapshot.
- Keep the HUD retry loop from cancelling and recreating itself while a wake request is already in flight.
- Reset the helper task-list freshness cache when the HUD opens or closes, forcing a fresh task request on every new HUD session.
- Extend foreground HUD retries to keep sending short wake beacons and task requests for roughly two minutes while the user is visibly waiting.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.9` reaches the glasses, then tap **Arm wake bridge**.
- If long-idle launch still fails, open the phone app immediately and send the refreshed `Wake debug ...` line from the Phone Bridge section.
- This still does not add glasses background work; the longer retry window only runs while the HUD is open.

## v0.2.9-preview.12 - 2026-06-26

### Preview Fix

- Add phone-side wake diagnostics for the long-idle path: last HUD beacon, foreground-session start result, RFCOMM listener state, HUD connection, and task-list delivery are now recorded in the Phone Bridge section.
- Record foreground-service start failures instead of silently returning false when Android refuses a wake/session start.
- Record the BLE wake receiver and RFCOMM listener events without logging device identifiers.
- Surface glasses-side HUD beacon success/failure in the HUD bridge status line so testers can see whether the helper is actually sending the fallback wake signal.

### Upgrade Notes

- Install the new phone APK, tap **Install HUD** so bundled helper `0.2.6-preview.8` reaches the glasses, then tap **Arm wake bridge**.
- After a failed long-idle launch, open the phone app and copy the `Wake debug ...` line from the Phone Bridge section.
- This does not move runtime back to CXR-L; it keeps the BLE wake plus RFCOMM runtime path.

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
