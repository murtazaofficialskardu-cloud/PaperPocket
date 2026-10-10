#!/bin/sh
# Download the official Gradle distribution and verify its published SHA-256.
# curl retains the caller's proxy settings and TLS certificate verification.
set -eu
VERSION=8.13
CACHE=${GRADLE_USER_HOME:-${HOME}/.gradle}/paperpocket-distributions
if [ -x "$CACHE/gradle-$VERSION/bin/gradle" ]; then
    exit 0
fi
for COMMAND in curl unzip; do
    if ! command -v "$COMMAND" >/dev/null 2>&1; then
        echo "Missing $COMMAND. Install it or install Gradle $VERSION locally." >&2
        exit 1
    fi
done
mkdir -p "$CACHE"
STAGING=$(mktemp -d "$CACHE/.bootstrap-XXXXXX")
trap 'rm -rf "$STAGING"' EXIT HUP INT TERM
BASE="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
echo "Installing Gradle $VERSION from services.gradle.org (network access required)."
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$BASE.sha256" -o "$STAGING/checksum"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$BASE" -o "$STAGING/gradle.zip"
EXPECTED=$(tr -d '[:space:]' < "$STAGING/checksum")
if [ ${#EXPECTED} -ne 64 ] || printf '%s' "$EXPECTED" | grep -q '[^0-9a-fA-F]'; then
    echo "Invalid official SHA-256 response; refusing installation." >&2
    exit 1
fi
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$STAGING/gradle.zip" | cut -d ' ' -f 1)
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$STAGING/gradle.zip" | cut -d ' ' -f 1)
else
    echo "Missing SHA-256 tool; refusing installation." >&2
    exit 1
fi
if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "Gradle checksum mismatch; refusing installation." >&2
    exit 1
fi
unzip -q "$STAGING/gradle.zip" -d "$STAGING"
if [ ! -x "$STAGING/gradle-$VERSION/bin/gradle" ]; then
    echo "Gradle archive is incomplete." >&2
    exit 1
fi
mv "$STAGING/gradle-$VERSION" "$CACHE/"
echo "Installed Gradle $VERSION."
