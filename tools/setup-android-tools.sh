#!/bin/sh
# Install official JDK 17, Android SDK 36, and Gradle 8.13 with verified archives.
# Network required. Keep inherited proxy routing and TLS verification enabled.
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLCHAIN_ROOT=${PAPERPOCKET_TOOLCHAIN_DIR:-$APP_ROOT/.toolchains}
SDK_ROOT="$TOOLCHAIN_ROOT/android-sdk"
ANDROID_HOME="$SDK_ROOT"
ANDROID_SDK_ROOT="$SDK_ROOT"
ANDROID_USER_HOME="$TOOLCHAIN_ROOT/android-user"
export ANDROID_HOME ANDROID_SDK_ROOT ANDROID_USER_HOME
mkdir -p "$TOOLCHAIN_ROOT" "$SDK_ROOT/cmdline-tools" "$ANDROID_USER_HOME"
for COMMAND in curl python3 unzip tar; do
    if ! command -v "$COMMAND" >/dev/null 2>&1; then
        echo "Missing required tool: $COMMAND" >&2
        exit 1
    fi
done
STAGING=$(mktemp -d "$TOOLCHAIN_ROOT/.tool-bootstrap-XXXXXX")
trap 'rm -rf "$STAGING"' EXIT HUP INT TERM

if [ ! -x "$TOOLCHAIN_ROOT/jdk17/bin/javac" ]; then
    curl --fail --location --connect-timeout 15 --max-time 120 --proto '=https' --tlsv1.2 \
        'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse' \
        -o "$STAGING/jdk-metadata.json"
    python3 - "$STAGING/jdk-metadata.json" "$STAGING/jdk-details" <<'PY'
import json, re, sys
from urllib.parse import urlparse
assets = json.load(open(sys.argv[1], encoding='utf-8'))
package = assets[0]['binary']['package']
url, checksum = package['link'], package['checksum']
if urlparse(url).scheme != 'https' or not re.fullmatch(r'[0-9a-fA-F]{64}', checksum):
    raise SystemExit('Invalid official Adoptium archive metadata')
with open(sys.argv[2], 'w', encoding='utf-8') as output:
    output.write(url + '\n' + checksum.lower() + '\n')
PY
    JDK_URL=$(sed -n '1p' "$STAGING/jdk-details")
    curl --fail --location --connect-timeout 15 --max-time 600 --proto '=https' --tlsv1.2 \
        "$JDK_URL" -o "$STAGING/jdk.tar.gz"
    python3 - "$STAGING/jdk.tar.gz" "$STAGING/jdk-details" <<'PY'
import hashlib, sys
expected = open(sys.argv[2], encoding='utf-8').read().splitlines()[1]
hasher = hashlib.sha256()
with open(sys.argv[1], 'rb') as archive:
    for chunk in iter(lambda: archive.read(1024 * 1024), b''): hasher.update(chunk)
if hasher.hexdigest() != expected: raise SystemExit('JDK SHA-256 mismatch; refusing installation')
PY
    mkdir "$STAGING/jdk17"
    tar -xzf "$STAGING/jdk.tar.gz" --strip-components=1 -C "$STAGING/jdk17"
    test -x "$STAGING/jdk17/bin/javac"
    mv "$STAGING/jdk17" "$TOOLCHAIN_ROOT/jdk17"
fi
JAVA_HOME="$TOOLCHAIN_ROOT/jdk17"
PATH="$JAVA_HOME/bin:$PATH"
export JAVA_HOME PATH
if [ -f /etc/ssl/certs/java/cacerts ]; then
    # Retain the managed runtime's trusted proxy CA in the downloaded JDK.
    cp /etc/ssl/certs/java/cacerts "$JAVA_HOME/lib/security/cacerts"
fi

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl --fail --location --connect-timeout 15 --max-time 120 --proto '=https' --tlsv1.2 \
        https://dl.google.com/android/repository/repository2-1.xml -o "$STAGING/repository.xml"
    python3 - "$STAGING/repository.xml" "$STAGING/archive-details" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
def local(tag): return tag.rsplit('}', 1)[-1]
root = ET.parse(sys.argv[1]).getroot()
for package in root.iter():
    if local(package.tag) != 'remotePackage' or package.attrib.get('path') != 'cmdline-tools;latest': continue
    for archive in package.iter():
        if local(archive.tag) != 'archive': continue
        if [c.text for c in archive if local(c.tag) == 'host-os'] != ['linux']: continue
        complete = next((c for c in archive if local(c.tag) == 'complete'), None)
        if complete is None: continue
        url = next((c.text for c in complete if local(c.tag) == 'url'), '')
        checksum = next((c for c in complete if local(c.tag) == 'checksum'), None)
        if not re.fullmatch(r'commandlinetools-linux-[0-9]+_latest\.zip', url or ''): raise SystemExit('Unexpected Android archive name')
        if checksum is None or checksum.attrib.get('type', 'sha1') != 'sha1' or not re.fullmatch(r'[0-9a-fA-F]{40}', checksum.text or ''):
            raise SystemExit('Missing official Android SHA-1 checksum')
        with open(sys.argv[2], 'w', encoding='ascii') as output: output.write(url + '\n' + checksum.text.lower() + '\n')
        raise SystemExit(0)
raise SystemExit('Official repository does not contain Linux cmdline-tools;latest')
PY
    ARCHIVE=$(sed -n '1p' "$STAGING/archive-details")
    curl --fail --location --connect-timeout 15 --max-time 600 --proto '=https' --tlsv1.2 \
        "https://dl.google.com/android/repository/$ARCHIVE" -o "$STAGING/tools.zip"
    python3 - "$STAGING/tools.zip" "$STAGING/archive-details" <<'PY'
import hashlib, sys
expected = open(sys.argv[2], encoding='ascii').read().splitlines()[1]
hasher = hashlib.sha1()
with open(sys.argv[1], 'rb') as archive:
    for chunk in iter(lambda: archive.read(1024 * 1024), b''): hasher.update(chunk)
if hasher.hexdigest() != expected: raise SystemExit('Android tools checksum mismatch; refusing installation')
PY
    unzip -q "$STAGING/tools.zip" -d "$STAGING"
    test -x "$STAGING/cmdline-tools/bin/sdkmanager"
    mv "$STAGING/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

# SDK Java client needs explicit proxy options in proxy-based environments.
PROXY_SETTINGS=$(python3 - <<'PY'
import os
from urllib.parse import urlparse
value = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or os.environ.get('HTTP_PROXY') or os.environ.get('http_proxy')
if value:
    parsed = urlparse(value)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname: raise SystemExit('Unsupported configured HTTP proxy')
    print(parsed.hostname)
    print(parsed.port or (443 if parsed.scheme == 'https' else 80))
PY
)
if [ -n "$PROXY_SETTINGS" ]; then
    PROXY_HOST=$(printf '%s\n' "$PROXY_SETTINGS" | sed -n '1p')
    PROXY_PORT=$(printf '%s\n' "$PROXY_SETTINGS" | sed -n '2p')
    set -- --proxy=http "--proxy_host=$PROXY_HOST" "--proxy_port=$PROXY_PORT"
else
    set --
fi
# Current sdkmanager shell launchers auto-download a separate Android CLI.
# Its packaged Java implementation keeps explicit SDK root/proxy support.
yes | python3 "$APP_ROOT/tools/sdkmanager-java.py" "--sdk_root=$SDK_ROOT" "$@" --licenses >/dev/null
python3 "$APP_ROOT/tools/sdkmanager-java.py" "--sdk_root=$SDK_ROOT" "$@" \
    'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
GRADLE_USER_HOME=${GRADLE_USER_HOME:-$TOOLCHAIN_ROOT/gradle-cache}
export GRADLE_USER_HOME
sh "$APP_ROOT/tools/bootstrap-gradle.sh"
python3 - "$APP_ROOT/local.properties" "$SDK_ROOT" "$TOOLCHAIN_ROOT" "$GRADLE_USER_HOME" <<'PY'
import os, shlex, sys
from urllib.parse import urlparse
with open(sys.argv[1], 'w', encoding='utf-8') as output:
    output.write('sdk.dir=' + sys.argv[2].replace('\\', '\\\\').replace(':', '\\:') + '\n')
with open(sys.argv[3] + '/env.sh', 'w', encoding='utf-8') as output:
    for name, value in [('JAVA_HOME', sys.argv[3] + '/jdk17'), ('ANDROID_HOME', sys.argv[2]), ('ANDROID_SDK_ROOT', sys.argv[2]), ('ANDROID_USER_HOME', sys.argv[3] + '/android-user'), ('GRADLE_USER_HOME', sys.argv[4])]:
        output.write('export ' + name + '=' + shlex.quote(value) + '\n')
    output.write('export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"\n')
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or os.environ.get('HTTP_PROXY') or os.environ.get('http_proxy')
if proxy:
    parsed = urlparse(proxy)
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    with open(sys.argv[4] + '/gradle.properties', 'a', encoding='utf-8') as output:
        for protocol in ['http', 'https']:
            output.write('systemProp.' + protocol + '.proxyHost=' + parsed.hostname + '\n')
            output.write('systemProp.' + protocol + '.proxyPort=' + str(port) + '\n')
PY
echo "Toolchain ready. Source $TOOLCHAIN_ROOT/env.sh before building."
