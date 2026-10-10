#!/usr/bin/env python3
"""Use the packaged Java SDK manager without the new CLI auto-download launcher."""
import os
from pathlib import Path
import sys

sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    raise SystemExit('Set ANDROID_HOME to the SDK directory first.')
lib = Path(sdk) / 'cmdline-tools/latest/lib'
jars = sorted(lib.rglob('*.jar'))
if not jars:
    raise SystemExit('Android command-line tools Java libraries are unavailable.')
java_home = os.environ.get('JAVA_HOME')
if not java_home:
    raise SystemExit('Set JAVA_HOME to the installed JDK first.')
java = Path(java_home) / 'bin/java'
os.execv(str(java), [str(java), '-cp', os.pathsep.join(str(jar) for jar in jars),
                   'com.android.sdklib.tool.sdkmanager.SdkManagerCli', *sys.argv[1:]])
