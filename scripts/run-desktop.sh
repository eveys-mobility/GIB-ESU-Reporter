#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -DskipTests package
mvn javafx:run
