#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APP_NAME="EveysGibEsuReporter"
MAIN_CLASS="dev.eveys.gibesu.desktop.DesktopApp"

VERSION="${VERSION:-$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)}"
PACKAGE_TYPE="${PACKAGE_TYPE:-}"
BASE_VERSION="${VERSION%%-*}"
JPACKAGE_VERSION="${JPACKAGE_VERSION:-$BASE_VERSION}"
if [[ "$JPACKAGE_VERSION" == 0.* ]]; then
  # macOS jpackage rejects CFBundleShortVersionString values whose first
  # integer is 0. Keep release file names on the Maven version, but use a
  # positive bundle version internally.
  JPACKAGE_VERSION="1.${JPACKAGE_VERSION#0.}"
fi

case "$(uname -s)" in
  Darwin)
    OS_ID="${OS_ID:-macos}"
    PACKAGE_TYPE="${PACKAGE_TYPE:-dmg}"
    ;;
  Linux)
    OS_ID="${OS_ID:-linux}"
    PACKAGE_TYPE="${PACKAGE_TYPE:-app-image}"
    ;;
  *)
    echo "Unsupported OS for this script. Use scripts/package-desktop.ps1 on Windows." >&2
    exit 1
    ;;
esac

JAR_NAME="eveys-gib-esu-reporter-${VERSION}.jar"
DIST_DIR="$ROOT_DIR/dist/desktop-${OS_ID}"
INPUT_DIR="$ROOT_DIR/dist/jpackage-input-${OS_ID}"
JAVAFX_DIR="$ROOT_DIR/target/javafx-${OS_ID}"

echo "Building ${APP_NAME} ${VERSION} for ${OS_ID} (${PACKAGE_TYPE})..."
mvn package

rm -rf "$DIST_DIR" "$INPUT_DIR" "$JAVAFX_DIR"
mkdir -p "$DIST_DIR" "$INPUT_DIR" "$JAVAFX_DIR"

cp "target/$JAR_NAME" "$INPUT_DIR/$JAR_NAME"

echo "Preparing JavaFX runtime modules..."
mvn -q dependency:copy-dependencies \
  -DincludeGroupIds=org.openjfx \
  -DoutputDirectory="$JAVAFX_DIR"

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$JPACKAGE_VERSION" \
  --input "$INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class "$MAIN_CLASS" \
  --module-path "$JAVAFX_DIR" \
  --add-modules java.se,javafx.controls,javafx.graphics,jdk.crypto.cryptoki,jdk.localedata \
  --java-options "--add-modules=jdk.crypto.cryptoki" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files" \
  --dest "$DIST_DIR"

if [[ "$(uname -s)" == Darwin ]]; then
  APP_IMAGE="$DIST_DIR/${APP_NAME}.app"
  RUNTIME_JAVA="$APP_IMAGE/Contents/runtime/Contents/Home/bin/java"
  PACKAGED_JAR="$APP_IMAGE/Contents/app/$JAR_NAME"
else
  APP_IMAGE="$DIST_DIR/$APP_NAME"
  RUNTIME_JAVA="$APP_IMAGE/lib/runtime/bin/java"
  PACKAGED_JAR="$APP_IMAGE/lib/app/$JAR_NAME"
fi
CHECK_DIR="$(mktemp -d "$ROOT_DIR/target/runtime-check-${OS_ID}.XXXXXX")"
mkdir -p "$CHECK_DIR/classes"
javac --release 17 -cp "$INPUT_DIR/$JAR_NAME" -d "$CHECK_DIR/classes" "$ROOT_DIR/scripts/RuntimeSmokeCheck.java"
echo "Checking the packaged runtime from / ..."
(
  cd /
  "$RUNTIME_JAVA" --add-modules jdk.crypto.cryptoki -Duser.home="$CHECK_DIR/home" \
    -cp "$CHECK_DIR/classes:$PACKAGED_JAR" dev.eveys.gibesu.desktop.RuntimeSmokeCheck "$CHECK_DIR/work"
)

case "$PACKAGE_TYPE" in
  dmg)
    jpackage --type dmg --name "$APP_NAME" --app-image "$APP_IMAGE" --dest "$DIST_DIR"
    dmg_file="$(find "$DIST_DIR" -maxdepth 1 -type f -name "${APP_NAME}*.dmg" | head -1)"
    if [[ -z "$dmg_file" ]]; then
      echo "DMG file was not created." >&2
      exit 1
    fi
    mv "$dmg_file" "$DIST_DIR/${APP_NAME}-${VERSION}-macos.dmg"
    echo "$DIST_DIR/${APP_NAME}-${VERSION}-macos.dmg"
    ;;
  app-image)
    app_image_name="$APP_NAME"
    if [[ ! -d "$DIST_DIR/$app_image_name" && -d "$DIST_DIR/${APP_NAME}.app" ]]; then
      app_image_name="${APP_NAME}.app"
    fi
    if [[ ! -d "$DIST_DIR/$app_image_name" ]]; then
      echo "App image directory was not created." >&2
      exit 1
    fi
    rm -f "$DIST_DIR/${APP_NAME}-${VERSION}-${OS_ID}.tar.gz"
    tar -czf "$DIST_DIR/${APP_NAME}-${VERSION}-${OS_ID}.tar.gz" -C "$DIST_DIR" "$app_image_name"
    echo "$DIST_DIR/${APP_NAME}-${VERSION}-${OS_ID}.tar.gz"
    ;;
  *)
    if [[ "$PACKAGE_TYPE" != app-image ]]; then
      jpackage --type "$PACKAGE_TYPE" --name "$APP_NAME" --app-image "$APP_IMAGE" --dest "$DIST_DIR"
    fi
    echo "$DIST_DIR"
    ;;
esac
