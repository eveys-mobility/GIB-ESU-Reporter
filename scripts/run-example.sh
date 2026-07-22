#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f config/application.yml ]]; then
  echo "Önce config/application.example.yml dosyasını config/application.yml olarak kopyalayıp doldurun."
  exit 1
fi

if [[ $# -lt 2 ]]; then
  echo "Kullanım: $0 <excel.xlsx> <YYYY-MM>"
  exit 1
fi

mvn -DskipTests package
VERSION="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)"
java -jar "target/eveys-gib-esu-reporter-${VERSION}.jar" \
  generate \
  --config config/application.yml \
  --input "$1" \
  --period "$2" \
  --out out
