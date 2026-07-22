#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${VERSION:-$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)}"
JAVAFX_VERSION="${JAVAFX_VERSION:-21.0.4}"
APP_NAME="EveysGibEsuReporter"
APP_DIR_NAME="${APP_NAME}-${VERSION}-windows-portable"
DIST_DIR="$ROOT_DIR/dist/windows-portable"
APP_DIR="$DIST_DIR/$APP_DIR_NAME"
JAR_NAME="eveys-gib-esu-reporter-${VERSION}.jar"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"

echo "Building application jar..."
mvn -DskipTests package

echo "Downloading Windows JavaFX runtime jars..."
for module in base graphics controls; do
  mvn -q dependency:get -Dartifact="org.openjfx:javafx-${module}:${JAVAFX_VERSION}:jar"
  mvn -q dependency:get -Dartifact="org.openjfx:javafx-${module}:${JAVAFX_VERSION}:jar:win"
done

rm -rf "$APP_DIR" "$DIST_DIR/$APP_DIR_NAME.zip"
mkdir -p "$APP_DIR/app" "$APP_DIR/lib" "$APP_DIR/config"

cp "target/$JAR_NAME" "$APP_DIR/app/$JAR_NAME"
cp -R "gib-esu-paket" "$APP_DIR/gib-esu-paket"
cp "config/application.example.yml" "$APP_DIR/config/application.example.yml"

for module in base graphics controls; do
  cp "$M2_REPO/org/openjfx/javafx-${module}/${JAVAFX_VERSION}/javafx-${module}-${JAVAFX_VERSION}.jar" "$APP_DIR/lib/"
  cp "$M2_REPO/org/openjfx/javafx-${module}/${JAVAFX_VERSION}/javafx-${module}-${JAVAFX_VERSION}-win.jar" "$APP_DIR/lib/"
done

cat > "$APP_DIR/Baslat.bat" <<EOF
@echo off
setlocal
cd /d "%~dp0"

set "APP_JAR=app\\$JAR_NAME"

where java >nul 2>nul
if errorlevel 1 (
  echo Java bulunamadi.
  echo Lutfen Windows icin Java 17 veya Java 21 kurun.
  echo Ornek: Eclipse Temurin JRE/JDK 17 veya 21.
  echo.
  pause
  exit /b 1
)

java --enable-native-access=ALL-UNNAMED --module-path "lib" --add-modules javafx.controls,javafx.graphics,jdk.crypto.cryptoki -cp "%APP_JAR%" dev.eveys.gibesu.desktop.DesktopApp
if errorlevel 1 (
  echo.
  echo Uygulama hata ile kapandi. Yukaridaki mesaji teknik ekiple paylasin.
  pause
)
EOF

cat > "$APP_DIR/Komut-Satiri.bat" <<EOF
@echo off
setlocal
cd /d "%~dp0"
java --add-modules jdk.crypto.cryptoki -jar "app\\$JAR_NAME" %*
EOF

perl -0pi -e 's/\r?\n/\r\n/g' "$APP_DIR/Baslat.bat" "$APP_DIR/Komut-Satiri.bat"

cat > "$APP_DIR/OKU-BENI-WINDOWS.txt" <<'EOF'
Eveys GIB ESU Reporter - Windows Portable Paket
================================================

Bu paket muhasebe kullanimi icin hazirlanmistir.

Gerekenler:
1. Windows uzerinde Java 17 veya Java 21 kurulu olmali.
   Ornek: Eclipse Temurin JRE/JDK 17 veya 21.
2. Mali muhur ve AKIS/Java AKIS surucusu bilgisayarda kurulu olmali.
3. Mali muhur USB'de takili olmali.

Calistirma:
1. ZIP dosyasini bir klasore cikarin.
2. Baslat.bat dosyasina cift tiklayin.
3. Ilk kullanimda VKN, unvan ve EPDK lisans no alanlarini doldurun.
4. AKIS PKCS#11 yolu icin "Otomatik Bul" deneyin; olmazsa akisp11.dll dosyasini secin.
5. "Alias Tara" ile mali muhur sertifikasini bulun. Genelde SIGN0 ile biten alias kullanilir.
6. PIN'i girin. PIN kaydedilmez.
7. Excel dosyasini secin, donemi YYYY-AA seklinde girin.
8. Once "Hazirla ve Test Et" calistirin. TEST status success30 olursa "Canliya Gonder" acisini kullanin.

Dosyalar:
- Ayarlar: %USERPROFILE%\.eveys-gib-esu\application.yml
- Ciktilar: Uygulama klasoru altinda out-desktop
- Basarili paket arsivi: out-desktop\arsiv

Guvenlik:
- PIN hicbir zaman dosyaya kaydedilmez.
- application.yml, XML, ZIP ve response dosyalari sirket/vergi bilgisi icerebilir; disari paylasmadan once dikkat edin.
- Ayni donem/dosya icin canli gonderimi tekrar etmeyin.

Sorun olursa:
- Java bulunamadi: Java 17 veya 21 kurun, sonra Baslat.bat'i tekrar acin.
- AKIS bulunamadi: Mali muhur surucusunu kurun veya akisp11.dll dosyasini elle secin.
- Alias bulunamadi: Mali muhur takili mi ve PIN dogru mu kontrol edin, sonra Alias Tara'yi tekrar deneyin.
EOF

(cd "$DIST_DIR" && zip -qr "$APP_DIR_NAME.zip" "$APP_DIR_NAME")

echo "Windows portable package created:"
echo "$DIST_DIR/$APP_DIR_NAME.zip"
