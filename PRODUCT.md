# Tasker Bridge

Tasker Bridge is a Rokid companion app for launching Android Tasker automations from the glasses. The phone app owns Tasker access and CXR-L authorization. The glasses helper is installed and launched through CXR-L, then renders a small HUD for selecting and launching named Tasker tasks.

Users are Rokid power users who already maintain Tasker automations on their phone and want quick, no-touch access from the glasses.

Register: product.

Principles:
- CXR-L is the install, launch, and command path from the phone to the glasses helper.
- Tasker stays phone-side. The glasses never query or execute Tasker directly.
- The glasses HUD uses 480 x 640 portrait, black AR-safe rendering, large text, outline focus, and one-axis navigation.
- The phone app exposes setup status clearly: Hi Rokid auth, CXR-L connection, helper install/launch, Tasker external access, and task list freshness.
