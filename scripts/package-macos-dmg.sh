#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OS_ID=macos PACKAGE_TYPE=dmg ./scripts/package-desktop.sh
