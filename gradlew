#!/bin/sh
# Portable Gradle launcher. This source bundle intentionally contains no wrapper JAR.
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.13
GRADLE_CACHE=${GRADLE_USER_HOME:-${HOME}/.gradle}/paperpocket-distributions
GRADLE_EXEC="$GRADLE_CACHE/gradle-$GRADLE_VERSION/bin/gradle"

if command -v gradle >/dev/null 2>&1; then
    if gradle --version 2>/dev/null | sed -n '/^Gradle /p' | head -n 1 | grep -qx "Gradle $GRADLE_VERSION"; then
        exec gradle --project-dir "$APP_ROOT" "$@"
    fi
fi

if [ ! -x "$GRADLE_EXEC" ]; then
    sh "$APP_ROOT/tools/bootstrap-gradle.sh"
fi
exec "$GRADLE_EXEC" --project-dir "$APP_ROOT" "$@"
