#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "$JAVA_CMD" >/dev/null 2>&1 && [ ! -x "$JAVA_CMD" ]; then JAVA_CMD=java; fi
exec "$JAVA_CMD" -Dfile.encoding=UTF-8 -Xmx64m -Xms64m -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
