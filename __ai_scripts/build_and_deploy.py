#!/usr/bin/env python3
"""Build and deploy Maps2Gpx to a connected Android device.

Inputs: optional build variant, adb device serial, clean-build, test, build-only, and launch flags.
Outputs: a built APK installed on the selected device, with the launcher activity opened.
Example: python __ai_scripts/build_and_deploy.py --clean
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


PACKAGE = "pl.net.xtech.maps2gpx"
LAUNCH_ACTIVITY = f"{PACKAGE}/.SavedRoutesActivity"
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build, install, and launch Maps2Gpx on a connected Android device."
    )
    parser.add_argument(
        "--variant",
        choices=("debug", "release"),
        default="debug",
        help="Android build variant to deploy (default: debug).",
    )
    parser.add_argument(
        "--device",
        metavar="SERIAL",
        help="adb device serial; required only when multiple devices are connected.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Run Gradle clean before assembling.",
    )
    parser.add_argument(
        "--tests",
        action="store_true",
        help="Run unit tests for the selected variant before assembling.",
    )
    parser.add_argument(
        "--build-only",
        action="store_true",
        help="Build without discovering, installing to, or launching a device.",
    )
    parser.add_argument(
        "--no-launch",
        action="store_true",
        help="Install the APK without launching the app.",
    )
    return parser.parse_args()


def run(command: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("$ " + subprocess.list2cmdline(command), flush=True)
    return subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        check=True,
        text=True,
        capture_output=capture,
    )


def gradle_command(tasks: list[str]) -> list[str]:
    if os.name != "nt":
        return [str(REPOSITORY_ROOT / "gradlew"), *tasks]

    git_bash_candidates = (
        Path(r"C:\Program Files\Git\bin\bash.exe"),
        Path(r"C:\Program Files (x86)\Git\bin\bash.exe"),
    )
    git_bash = next((path for path in git_bash_candidates if path.is_file()), None)
    if git_bash is None:
        raise RuntimeError(
            "Git for Windows Bash was not found in Program Files; install Git for Windows."
        )
    return [str(git_bash), "./gradlew", *tasks]


def android_sdk_from_local_properties() -> Path | None:
    properties = REPOSITORY_ROOT / "local.properties"
    if not properties.is_file():
        return None
    for raw_line in properties.read_text(encoding="utf-8").splitlines():
        if raw_line.startswith("sdk.dir="):
            value = raw_line.partition("=")[2].replace(r"\:", ":").replace(r"\\", "\\")
            return Path(value)
    return None


def find_adb() -> str:
    executable = shutil.which("adb")
    if executable:
        return executable

    sdk_candidates = [
        android_sdk_from_local_properties(),
        Path(os.environ["ANDROID_SDK_ROOT"]) if os.environ.get("ANDROID_SDK_ROOT") else None,
        Path(os.environ["ANDROID_HOME"]) if os.environ.get("ANDROID_HOME") else None,
    ]
    adb_name = "adb.exe" if os.name == "nt" else "adb"
    for sdk in sdk_candidates:
        if sdk is not None:
            candidate = sdk / "platform-tools" / adb_name
            if candidate.is_file():
                return str(candidate)
    raise RuntimeError(
        "adb was not found. Install Android SDK Platform-Tools or set ANDROID_SDK_ROOT."
    )


def connected_devices(adb: str) -> list[str]:
    result = run([adb, "devices"], capture=True)
    devices = []
    unavailable = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 2:
            continue
        if fields[1] == "device":
            devices.append(fields[0])
        elif fields[1] in {"offline", "unauthorized"}:
            unavailable.append(f"{fields[0]} ({fields[1]})")
    if not devices:
        detail = f" Found: {', '.join(unavailable)}." if unavailable else ""
        raise RuntimeError(
            "No authorized Android device is connected. Enable USB debugging and accept the "
            f"device authorization prompt.{detail}"
        )
    return devices


def select_device(adb: str, requested: str | None) -> str:
    devices = connected_devices(adb)
    if requested:
        if requested not in devices:
            raise RuntimeError(
                f"Device {requested!r} is not connected. Available: {', '.join(devices)}"
            )
        return requested
    if len(devices) > 1:
        raise RuntimeError(
            "Multiple devices are connected; choose one with --device SERIAL: "
            + ", ".join(devices)
        )
    return devices[0]


def apk_path(variant: str) -> Path:
    return REPOSITORY_ROOT / "app" / "build" / "outputs" / "apk" / variant / f"app-{variant}.apk"


def main() -> int:
    args = parse_args()
    variant_task = "assemble" + args.variant.capitalize()
    test_task = "test" + args.variant.capitalize() + "UnitTest"
    tasks = (["clean"] if args.clean else [])
    if args.tests:
        tasks.append(test_task)
    tasks.append(variant_task)

    run(gradle_command(tasks))

    apk = apk_path(args.variant)
    if not apk.is_file():
        raise RuntimeError(f"Gradle succeeded but the expected APK was not found: {apk}")

    if args.build_only:
        print(f"Build complete: {apk}", flush=True)
        return 0

    adb = find_adb()
    serial = select_device(adb, args.device)
    print(f"Deploying {apk.name} to {serial}", flush=True)
    run([adb, "-s", serial, "install", "-r", "-t", str(apk)])

    if not args.no_launch:
        run([
            adb,
            "-s",
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            LAUNCH_ACTIVITY,
        ])

    print("Deployment complete.", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
