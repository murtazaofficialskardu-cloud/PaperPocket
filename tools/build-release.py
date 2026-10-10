#!/usr/bin/env python3
"""Build signed releases using private local properties or environment variables.

Signing values are passed to Gradle in the child environment, never in command
arguments or output. Keep the properties file and keystore outside the project.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "PAPERPOCKET_KEYSTORE_PATH",
    "PAPERPOCKET_KEYSTORE_PASSWORD",
    "PAPERPOCKET_KEY_ALIAS",
    "PAPERPOCKET_KEY_PASSWORD",
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signing-properties", type=Path)
    parser.add_argument("--keystore", type=Path)
    args = parser.parse_args()
    task_env = os.environ.copy()
    if args.signing_properties:
        for line in args.signing_properties.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, separator, value = line.partition("=")
            if not separator or name not in REQUIRED:
                raise SystemExit("Signing file contains an unknown setting.")
            task_env[name] = value
    if args.keystore:
        task_env["PAPERPOCKET_KEYSTORE_PATH"] = str(args.keystore.resolve())
    missing = [name for name in REQUIRED if not task_env.get(name)]
    if missing:
        raise SystemExit("Missing signing settings: " + ", ".join(missing))
    if not Path(task_env["PAPERPOCKET_KEYSTORE_PATH"]).is_file():
        raise SystemExit("The configured upload keystore is unavailable.")
    executable = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run(
        [str(executable), "--no-daemon", ":app:assembleRelease", ":app:bundleRelease",
         ":app:testDebugUnitTest", ":app:lintRelease"],
        cwd=ROOT,
        env=task_env,
        check=True,
    )
    print("Signed APK: app/build/outputs/apk/release/app-release.apk")
    print("Signed AAB: app/build/outputs/bundle/release/app-release.aab")


if __name__ == "__main__":
    main()
