#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVAFX_HOME:?JAVAFX_HOME JavaFX SDK klasörünü göstermeli. Örn: /Users/me/Downloads/javafx-sdk-21.0.4}"

APP_NAME="EveysGibEsuReporter"
VERSION="0.1.0"
JAR="eveys-gib-esu-reporter-${VERSION}.jar"

mvn -DskipTests package
rm -rf dist/input dist/macos
mkdir -p dist/input dist/macos
cp "target/${JAR}" dist/input/

jpackage \
  --type dmg \
  --name "${APP_NAME}" \
  --app-version "${VERSION}" \
  --input dist/input \
  --main-jar "${JAR}" \
  --main-class dev.eveys.gibesu.desktop.DesktopApp \
  --module-path "${JAVAFX_HOME}/lib" \
  --add-modules javafx.controls,javafx.graphics \
  --dest dist/macos
