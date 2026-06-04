# Tasker Bridge

<p align="center">
  <a href="https://github.com/Anezium/Tasker-Bridge/releases/latest"><img src="https://img.shields.io/github/v/release/Anezium/Tasker-Bridge?label=APK&color=5CF018" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Rokid-Glasses-0F1514?logo=android&logoColor=white" alt="Rokid Glasses" />
  <img src="https://img.shields.io/badge/Android-31%2B-3DDC84?logo=android&logoColor=white" alt="Android 31+" />
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Tasker-Automation-FF7A00" alt="Tasker automation" />
</p>

Android/Kotlin bridge for launching Tasker automations from a Rokid Glasses HUD.

<p align="center">
  <a href="https://ko-fi.com/M8R61ZTXMI" target="_blank">
    <img height="36" style="border:0px;height:36px;" src="https://storage.ko-fi.com/cdn/kofi4.png?v=6" border="0" alt="Buy Me a Coffee at ko-fi.com" />
  </a>
</p>

Tasker Bridge keeps Tasker on the phone, where it belongs, and puts only a tiny navigation HUD on the glasses. The phone app owns Hi Rokid authorization, CXR-L connection, Tasker access, helper APK upload/install, and task execution. The glasses helper renders a 480 x 640 AR-safe list and sends launch requests back to the phone.

<p align="center">
  <img src="assets/screenshots/phone.png" width="260" alt="Tasker Bridge phone app" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/screenshots/glasses.png" width="260" alt="Tasker Bridge glasses HUD" />
</p>

<p align="center">
  <em>Phone companion &middot; Rokid Glasses HUD</em>
</p>

## Download

Get the latest phone APK from [GitHub Releases](https://github.com/Anezium/Tasker-Bridge/releases/latest).

You only need to install the phone APK manually. The phone build embeds the glasses helper APK and can upload/install/open it on the glasses through CXR-L when you tap **Start bridge**.

## What It Does

- Lists named Tasker tasks on the Rokid Glasses HUD.
- Launches the selected Tasker task from the glasses.
- Keeps Tasker access and execution on the Android phone.
- Uses Global Hi Rokid CXR-L to install and launch the glasses helper.
- Runs a lightweight foreground `connectedDevice` bridge on the phone.
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
4. Authorize the app in Global Hi Rokid when prompted.
5. Tap **Start bridge**.
6. The phone checks whether the glasses helper is installed, uploads it if needed, then opens the HUD.
7. On the glasses, swipe to choose a task and tap to launch it.

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

Glasses helper
  -> CXRServiceBridge READY / REQUEST_TASKS
  -> receives TASK_LIST
  -> sends LAUNCH_TASK

Phone app
  -> sends Tasker run broadcast
  -> returns LAUNCH_RESULT
```

Tasker Bridge uses small JSON messages over stable CXR channels:

- `anezium_tasker_bridge_control`: phone to glasses
- `anezium_tasker_bridge_status`: glasses to phone

## Battery Behavior

The phone bridge is designed to sit idle. It does not hold a wake lock, does not refresh Tasker on a timer, and does not spam CXR messages. The foreground service exists so Android keeps the CXR-L listener alive; Tasker refresh happens when the HUD opens/resumes or when the HUD explicitly asks for the task list.

If the phone app is force-stopped from Android settings, the glasses cannot wake it through CXR because the phone-side CXR callback no longer exists. Open the phone app again to restart the bridge.

## Build

Use the local isolated Gradle home if the global Kotlin DSL cache is stale:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user --no-daemon :phone-app:assembleDebug
```

Outputs:

```text
phone-app/build/outputs/apk/debug/phone-app-debug.apk
glasses-helper/build/outputs/apk/debug/glasses-helper-debug.apk
```

The phone build automatically embeds the helper as:

```text
tasker-bridge-glasses-debug.apk
```

inside the phone APK assets.

## Project Layout

```text
phone-app/       Android phone companion, CXR-L bridge, Tasker runtime
glasses-helper/ Rokid glasses HUD, CXRServiceBridge client
shared/         JSON protocol and shared task models
assets/         README screenshots and project media
```

## Notes

Tasker Bridge is experimental Rokid tooling. It depends on Global Hi Rokid CXR-L behavior and Tasker's public external-access/run-task integration, so firmware, Hi Rokid, and Tasker updates can affect the bridge.
