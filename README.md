# Tasker Bridge

<p align="center">
  <a href="https://github.com/Anezium/Tasker-Bridge/releases/latest"><img src="https://img.shields.io/github/v/release/Anezium/Tasker-Bridge?label=APK&color=5CF018" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Rokid-Glasses-0F1514?logo=android&logoColor=white" alt="Rokid Glasses" />
  <img src="https://img.shields.io/badge/Android-31%2B-3DDC84?logo=android&logoColor=white" alt="Android 31+" />
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Tasker-Automation-FF7A00" alt="Tasker automation" />
</p>

<p align="center">
  <a href="https://ko-fi.com/M8R61ZTXMI" target="_blank">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi4.png?v=6" border="0" alt="Buy Me a Coffee at ko-fi.com" />
  </a>
</p>

Tasker Bridge lets you launch your Android Tasker automations from a simple Rokid Glasses HUD. Install the phone APK, arm the wake bridge, then pick and run your tasks directly from the glasses.

<p align="center">
  <a href="assets/screenshots/phone.png"><img src="assets/screenshots/phone.png" width="260" alt="Tasker Bridge phone companion showing the connected HUD and Tasker task list" /></a>
  &nbsp;&nbsp;&nbsp;&nbsp;
  <a href="assets/screenshots/glasses.png"><img src="assets/screenshots/glasses.png" width="260" alt="Tasker Bridge Rokid Glasses HUD showing project navigation" /></a>
</p>

<p align="center">
  <em>Phone bridge and Tasker tasks &middot; Rokid Glasses project HUD</em>
</p>

## Download

Get the latest phone APK from [GitHub Releases](https://github.com/Anezium/Tasker-Bridge/releases/latest).

You only need to install the phone APK manually. The phone build embeds the glasses helper APK and can upload/install/open it on the glasses through CXR-L from the **Glasses HUD** actions. Runtime task commands use Bluetooth, not CXR-L.

See [CHANGELOG.md](CHANGELOG.md) for release notes and upgrade details.

## What It Does

- Lists named Tasker tasks on the Rokid Glasses HUD.
- Launches the selected Tasker task from the glasses.
- Keeps Tasker access and execution on the Android phone.
- Uses Global Hi Rokid CXR-L only to install and launch the glasses helper.
- Uses a low-power BLE wake signal from the HUD, then a short Bluetooth RFCOMM session for task lists and launch commands.
- Pairs by the Tasker Bridge Bluetooth service endpoint, not by the device name.
- Runs a foreground `connectedDevice` session only while the HUD is actively connecting.
- Refreshes Tasker on HUD open/resume instead of polling forever.
- Responds cache-first, then refreshes Tasker and only pushes an update if the task list changed.

## Requirements

- Rokid Glasses paired with the phone.
- Global Hi Rokid installed on the phone.
- Tasker installed on the phone.
- Tasker external access enabled.
- Tasker run permission granted to Tasker Bridge.
- Wi-Fi enabled on the phone for CXR-L helper upload/install.

Tasker Bridge currently targets Android 31+ on the phone.

## Setup

1. Install the phone APK from the latest release.
2. Open Tasker Bridge on the phone.
3. Accept the Android permissions.
4. Tap **Install HUD** when the helper needs to be installed or updated.
5. Tap **Launch HUD** to open it on the glasses.
6. Tap **Arm wake bridge** before using the HUD. Tapping **Launch HUD** also arms it.
7. On first Bluetooth connection, the phone and HUD remember each other.
8. On the glasses, swipe to choose a task and tap to launch it.

## Controls

```text
Swipe forward: next task
Swipe back: previous task
Tap / OK: launch selected task
Back: hide the HUD
```

The HUD also supports DPAD key events for ADB testing.

## How It Works

```text
Phone app
  -> Global Hi Rokid authorization
  -> CXR-L CUSTOMAPP session for com.anezium.taskerbridge.glasses
  -> helper APK check/upload/install
  -> helper activity launch
  -> CXR-L disconnects after setup

Glasses helper
  -> BLE wake client while HUD opens
  -> writes wake_tasks to the phone
  -> Bluetooth RFCOMM client while HUD is open
  -> sends READY / REQUEST_TASKS
  -> receives TASK_LIST
  -> sends LAUNCH_TASK

Phone app
  -> BLE GATT wake server stays armed after the user enables the bridge
  -> foreground connectedDevice service opens a short RFCOMM session after wake
  -> sends Tasker run broadcast
  -> returns LAUNCH_RESULT
```

The first successful RFCOMM connection to the Tasker Bridge service UUID is remembered as the paired HUD. Device names such as "Rokid" or "Glasses" are never used for routing, because users can rename their glasses freely. After pairing, the phone accepts only the saved Bluetooth address; use **Forget Bluetooth pairing** to learn a different HUD. The glasses helper also remembers the first compatible phone endpoint after install. The BLE wake characteristic is only a doorbell; task names and launch commands still travel over the paired RFCOMM session.

Tasker Bridge uses newline-delimited JSON messages over a stable Bluetooth RFCOMM service UUID:

- phone to glasses: `TASK_LIST`, `LAUNCH_RESULT`, status updates
- glasses to phone: `READY`, `REQUEST_TASKS`, `LAUNCH_TASK`, selection changes

## Battery Behavior

The bridge is designed to sit idle. It does not hold a phone wake lock, does not refresh Tasker on a timer, and does not keep a CXR-L custom app session active. Opening the phone app does not start a permanent foreground service by itself; use **Arm wake bridge** or **Launch HUD** when you want glasses commands available. While armed, the phone exposes a low-power BLE GATT wake endpoint. When the HUD opens, it writes a small `wake_tasks` request, the phone opens a foreground RFCOMM session, sends/receives Tasker messages, then drops the session after the HUD disconnects or stays idle.

When the glasses HUD is open in the foreground, it keeps the glasses display awake intentionally so the menu remains usable. When the HUD leaves the foreground, it stops the glasses-side Bluetooth listener.

If the phone app is force-stopped from Android settings, the glasses cannot wake it through Bluetooth because Android clears the app process and receivers. Open the phone app again and tap **Arm wake bridge**.

## Build

Use the local isolated Gradle home if the global Kotlin DSL cache is stale:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --no-daemon :phone-app:assembleDebug
```

For a GitHub release APK:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --no-daemon :phone-app:assembleRelease
```

If no release signing secrets are configured, the release variant falls back to the Android debug signing config so the APK remains installable for sideloading.

Outputs:

```text
phone-app/build/outputs/apk/debug/phone-app-debug.apk
phone-app/build/outputs/apk/release/phone-app-release.apk
glasses-helper/build/outputs/apk/debug/glasses-helper-debug.apk
glasses-helper/build/outputs/apk/release/glasses-helper-release.apk
```

The phone build automatically embeds the helper as:

```text
tasker-bridge-glasses-debug.apk
```

inside the phone APK assets.

## Project Layout

```text
phone-app/       Android phone companion, BLE wake, Bluetooth bridge, CXR-L setup, Tasker runtime
glasses-helper/ Rokid glasses HUD, BLE wake client, Bluetooth RFCOMM client
shared/         JSON protocol and shared task models
assets/         README screenshots and project media
```

## Notes

Tasker Bridge is experimental Rokid tooling. It depends on Android Bluetooth behavior, Global Hi Rokid CXR-L setup behavior, and Tasker's public external-access/run-task integration, so firmware, Hi Rokid, and Tasker updates can affect the bridge.
