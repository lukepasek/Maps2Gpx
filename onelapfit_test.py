#!/usr/bin/env python3
"""Exercise OneLapFit using a GPX saved by Maps2Gpx on a connected Android device.

Inputs: an authorized adb device with OneLapFit installed; prompts only when several devices exist.
Outputs: a timestamped log and screenshots under __ai_scripts/onelapfit_runs.
Example: python onelapfit_test.py
"""

from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


PACKAGE = "com.onelap.fitness"
ACTIVITY = "com.kai.app_route.ui.ImportGpxActivity"
MAPS_PACKAGE = "pl.net.xtech.maps2gpx"
MAPS_LIBRARY_ACTIVITY = "pl.net.xtech.maps2gpx.SavedRoutesActivity"
HOME_ACTIVITY = "com.onelap.fitness.activity.home.HomeActivity"
ROUTE_HISTORY_ACTIVITY = "com.kai.app_route.ui.RouteHistoryActivity"
ROUTE_DETAIL_ACTIVITY = "com.kai.app_bike_computer.activity.BikeComputerH5Activity"

REPOSITORY_ROOT = Path(__file__).resolve().parent
ADB = None


class Tee:
    """Write console output to its original stream and the current run log."""

    def __init__(self, stream, log_file):
        self.stream = stream
        self.log_file = log_file

    def write(self, text):
        self.stream.write(text)
        self.log_file.write(text)
        return len(text)

    def flush(self):
        self.stream.flush()
        self.log_file.flush()

def run(cmd, check=True, capture=True, print_output=True):
    print("\n$ " + " ".join(map(str, cmd)))

    result = subprocess.run(
        cmd,
        text=True,
        capture_output=capture,
    )

    if capture and print_output:
        if result.stdout:
            print(result.stdout.rstrip())
        if result.stderr:
            print(result.stderr.rstrip(), file=sys.stderr)

    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(cmd)}"
        )

    return result


def find_adb():
    executable = shutil.which("adb")
    if executable:
        return executable

    sdk_candidates = []
    properties = REPOSITORY_ROOT / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                value = line.partition("=")[2].replace(r"\:", ":").replace(r"\\", "\\")
                sdk_candidates.append(Path(value))
                break
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(variable):
            sdk_candidates.append(Path(os.environ[variable]))

    adb_name = "adb.exe" if os.name == "nt" else "adb"
    for sdk in sdk_candidates:
        candidate = sdk / "platform-tools" / adb_name
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(
        "adb was not found. Install Android SDK Platform-Tools or configure sdk.dir in "
        "local.properties."
    )


def adb(*args, check=True, print_output=True):
    global ADB
    if ADB is None:
        ADB = find_adb()
    return run([ADB, *args], check=check, print_output=print_output)


def find_device():
    result = adb("devices", print_output=False)

    devices = []

    for line in result.stdout.splitlines():
        line = line.strip()

        if not line or line.startswith("List of devices"):
            continue

        parts = line.split()

        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if not devices:
        raise RuntimeError(
            "No Android device found.\n"
            "Make sure USB debugging is enabled and run: adb devices"
        )

    if len(devices) > 1:
        print("\nMultiple devices found:")
        for i, device in enumerate(devices):
            print(f"  {i + 1}: {device}")

        selection = input("Select device [1]: ").strip() or "1"
        serial = devices[int(selection) - 1]
    else:
        serial = devices[0]

    print(f"\nUsing device: {serial}")

    return serial


def adb_device(serial, *args, check=True, print_output=True):
    global ADB
    if ADB is None:
        ADB = find_adb()
    return run(
        [ADB, "-s", serial, *args],
        check=check,
        print_output=print_output,
    )


def check_onelapfit(serial):
    print("\n=== Checking OnelapFit installation ===")

    result = adb_device(
        serial,
        "shell",
        "pm",
        "path",
        PACKAGE,
        check=False,
        print_output=False,
    )

    if result.returncode != 0 or not result.stdout.strip():
        raise RuntimeError(
            f"{PACKAGE} is not installed on the device."
        )

    print("OnelapFit installed:")
    print(result.stdout.strip())


def inspect_activity(serial):
    print("\n=== Checking ImportGpxActivity ===")

    result = adb_device(
        serial,
        "shell",
        "dumpsys",
        "package",
        PACKAGE,
        print_output=False,
    )

    text = result.stdout

    index = text.find(ACTIVITY)

    if index >= 0:
        start = max(0, index - 500)
        end = min(len(text), index + 1200)

        print(text[start:end])

    else:
        raise RuntimeError(f"{ACTIVITY} was not found in the installed package.")


def clear_logcat(serial):
    print("\n=== Clearing logcat ===")

    adb_device(
        serial,
        "logcat",
        "-c",
    )


def dump_logcat(serial, seconds=3):
    print("\n=== Relevant logcat ===")

    time.sleep(seconds)

    process = adb_device(
        serial, "shell", "pidof", PACKAGE, check=False, print_output=False
    )
    process_ids = process.stdout.split()
    if not process_ids:
        print("OnelapFit is not running; no app log is available.")
        return

    result = adb_device(
        serial, "logcat", "-d", "--pid", process_ids[0], "-v", "time", "*:W",
        print_output=False,
    )

    interesting = []

    keywords = [
        "ImportGpx",
        "gpx",
        "AndroidRuntime",
        "FATAL EXCEPTION",
        "SecurityException",
        "FileNotFoundException",
        "Permission Denial",
    ]

    for line in result.stdout.splitlines():
        lower = line.lower()

        if any(keyword.lower() in lower for keyword in keywords):
            interesting.append(line)

    if interesting:
        print("\n".join(line[:500] for line in interesting[-40:]))
    else:
        print("No GPX import errors were logged by OnelapFit.")


def capture_screenshot(serial, destination):
    print(f"\n=== Capturing {destination.name} ===")
    time.sleep(1)
    global ADB
    if ADB is None:
        ADB = find_adb()
    command = [ADB, "-s", serial, "exec-out", "screencap", "-p"]
    print("\n$ " + " ".join(map(str, command)))
    result = subprocess.run(command, capture_output=True)
    if result.returncode != 0:
        error = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"Could not capture Android screenshot: {error}")
    if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Android screencap did not return a valid PNG image.")
    destination.write_bytes(result.stdout)
    print(f"Saved screenshot: {destination}")


def force_stop(serial, package=PACKAGE):
    print(f"\n=== Stopping {package} ===")

    adb_device(
        serial,
        "shell",
        "am",
        "force-stop",
        package,
        check=False,
    )


def wake_device(serial):
    print("\n=== Waking device ===")
    adb_device(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb_device(serial, "shell", "wm", "dismiss-keyguard", check=False)
    time.sleep(1)


def screen_size(serial):
    result = adb_device(
        serial, "shell", "wm", "size", print_output=False
    )
    for line in reversed(result.stdout.splitlines()):
        if ":" not in line:
            continue
        dimensions = line.partition(":")[2].strip()
        try:
            width, height = (int(value) for value in dimensions.split("x", 1))
            return width, height
        except ValueError:
            continue
    raise RuntimeError(f"Could not determine the device screen size:\n{result.stdout}")


def tap_relative(serial, width, height, x_ratio, y_ratio, label):
    x = round(width * x_ratio)
    y = round(height * y_ratio)
    print(f"\n=== Tapping {label} at ({x}, {y}) ===")
    adb_device(serial, "shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def resumed_activity(serial):
    result = adb_device(
        serial, "shell", "dumpsys", "activity", "activities", print_output=False
    )
    marker = "topResumedActivity="
    for line in result.stdout.splitlines():
        if marker not in line:
            continue
        component = line.partition(marker)[2].split()[2]
        package, activity = component.rstrip("}").split("/", 1)
        return package + activity if activity.startswith(".") else activity
    raise RuntimeError("Android did not report a resumed activity.")


def require_resumed_activity(serial, expected_activity, timeout_seconds=8):
    deadline = time.monotonic() + timeout_seconds
    actual_activity = None
    while time.monotonic() < deadline:
        try:
            actual_activity = resumed_activity(serial)
        except RuntimeError:
            time.sleep(0.25)
            continue
        if actual_activity == expected_activity:
            print(f"Resumed activity: {actual_activity}")
            return
        time.sleep(0.25)
    raise RuntimeError(
        f"Expected resumed activity {expected_activity}, got {actual_activity}."
    )


def test_maps2gpx_saved_route(serial, artifact_dir):
    print("\n=== TEST 1: newest GPX from Maps2Gpx -> OneLapFit ===")

    force_stop(serial)
    force_stop(serial, MAPS_PACKAGE)
    wake_device(serial)
    clear_logcat(serial)
    width, height = screen_size(serial)

    adb_device(
        serial,
        "shell",
        "monkey",
        "-p",
        MAPS_PACKAGE,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
    )
    require_resumed_activity(serial, MAPS_LIBRARY_ACTIVITY)
    time.sleep(2)
    capture_screenshot(serial, artifact_dir / "01-maps2gpx-saved-routes.png")

    tap_relative(serial, width, height, 0.91, 0.24, "newest route external-open arrow")
    require_resumed_activity(serial, ACTIVITY)
    capture_screenshot(serial, artifact_dir / "02-onelapfit-import-preview.png")
    dump_logcat(serial)


def test_open_newest_route(serial, artifact_dir):
    print("\n=== TEST 2: Home -> Profile -> My Routes -> newest route ===")

    force_stop(serial)
    wake_device(serial)
    width, height = screen_size(serial)
    adb_device(
        serial,
        "shell",
        "monkey",
        "-p",
        PACKAGE,
        "-c",
        "android.intent.category.LAUNCHER",
        "1",
    )
    time.sleep(2)
    require_resumed_activity(serial, HOME_ACTIVITY)

    tap_relative(serial, width, height, 1 / 6, 0.897, "Home tab")
    capture_screenshot(serial, artifact_dir / "03-onelapfit-home.png")

    tap_relative(serial, width, height, 5 / 6, 0.897, "Profile tab")
    require_resumed_activity(serial, HOME_ACTIVITY)
    capture_screenshot(serial, artifact_dir / "04-onelapfit-profile.png")

    tap_relative(serial, width, height, 0.5, 0.71, "My Routes")
    require_resumed_activity(serial, ROUTE_HISTORY_ACTIVITY)
    capture_screenshot(serial, artifact_dir / "05-onelapfit-my-routes.png")

    tap_relative(serial, width, height, 0.5, 0.46, "newest route card")
    require_resumed_activity(serial, ROUTE_DETAIL_ACTIVITY)
    capture_screenshot(serial, artifact_dir / "06-onelapfit-newest-route.png")


def main(artifact_dir):
    print("==============================================")
    print(" Maps2Gpx / OnelapFit Intent Test Harness")
    print("==============================================")

    serial = find_device()

    check_onelapfit(serial)

    inspect_activity(serial)

    test_maps2gpx_saved_route(serial, artifact_dir)
    test_open_newest_route(serial, artifact_dir)

    print("\n==============================================")
    print("Tests complete")
    print("==============================================")


def run_with_artifacts():
    artifact_dir = (REPOSITORY_ROOT / "__ai_scripts" / "onelapfit_runs"
                    / datetime.now().strftime("%Y%m%d-%H%M%S"))
    artifact_dir.mkdir(parents=True, exist_ok=False)
    log_path = artifact_dir / "output.log"
    exit_code = 0
    with log_path.open("w", encoding="utf-8", buffering=1) as log_file:
        with redirect_stdout(Tee(sys.stdout, log_file)), redirect_stderr(Tee(sys.stderr, log_file)):
            print(f"Artifacts: {artifact_dir}")
            try:
                main(artifact_dir)
            except (OSError, RuntimeError, ValueError) as error:
                print(f"\nERROR: {error}", file=sys.stderr)
                exit_code = 1
            print(f"Output log: {log_path}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(run_with_artifacts())