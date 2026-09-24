---
name: maps2gpx-build-deploy
description: "Build, compile, install, deploy, or launch the Maps2Gpx Android app on a connected phone or emulator. Use when asked to build an APK, deploy to a device, reinstall the app, select an adb target, perform a clean build, or install without launching. Always drive the workflow through __ai_scripts/build_and_deploy.py."
argument-hint: "[--variant debug|release] [--clean] [--device SERIAL] [--no-launch]"
user-invocable: true
disable-model-invocation: false
---

# Maps2Gpx Build And Deploy

Use the repository utility at `__ai_scripts/build_and_deploy.py` as the single entry point for building and deploying this app.

## Workflow

1. Run commands from the repository root in Git for Windows Bash.
2. Translate the request into supported script options:
   - Default build, install, and launch: `python __ai_scripts/build_and_deploy.py`
   - Select a build type: add `--variant debug` or `--variant release`
   - Clean build: add `--clean`
   - Specific connected device: add `--device SERIAL`
   - Install without launching: add `--no-launch`
3. Run the selected command with the terminal tool in synchronous mode.
4. Report the Gradle result, APK name, selected device serial, install result, and launch result.

## Device Selection

- Let the script auto-select when exactly one authorized device is connected.
- If multiple devices are reported, ask the user which listed serial to use, then rerun with `--device SERIAL`.
- If a device is `unauthorized`, tell the user to unlock it and accept the USB debugging prompt. Do not repeatedly rerun while authorization is pending.
- If no device is connected, report that the build may have succeeded but deployment could not proceed. Ask the user to connect a device only when deployment is required.

## Failure Handling

- Treat the script's final exit code as authoritative.
- On a Gradle failure, summarize the first actionable compile or resource error. Do not hide it behind the later deployment failure.
- On an adb install failure, preserve the built APK and report the exact high-level reason, such as authorization, insufficient storage, signature mismatch, or multiple devices.
- For a signature mismatch, do not uninstall automatically because that deletes app data. Explain that uninstalling is destructive and obtain explicit approval first.
- Do not replace this workflow with direct `gradlew` and `adb` commands unless diagnosing a defect in the script itself.

## Expected Success

A successful run ends with `Deployment complete.` The default debug APK is `app/build/outputs/apk/debug/app-debug.apk`, and the launched component is `pl.net.xtech.maps2gpx/.SavedRoutesActivity`.
