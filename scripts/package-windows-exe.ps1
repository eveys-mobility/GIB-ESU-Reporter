$ErrorActionPreference = "Stop"

if (-not $env:JAVAFX_HOME) {
  throw "JAVAFX_HOME JavaFX SDK klasörünü göstermeli. Örn: C:\javafx-sdk-21.0.4"
}

$AppName = "EveysGibEsuReporter"
$Version = "0.1.0"
$Jar = "eveys-gib-esu-reporter-$Version.jar"

mvn -DskipTests package
Remove-Item -Recurse -Force dist\input, dist\windows -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist\input, dist\windows | Out-Null
Copy-Item "target\$Jar" "dist\input\$Jar"

jpackage `
  --type exe `
  --name $AppName `
  --app-version $Version `
  --input dist\input `
  --main-jar $Jar `
  --main-class dev.eveys.gibesu.desktop.DesktopApp `
  --module-path "$env:JAVAFX_HOME\lib" `
  --add-modules javafx.controls,javafx.graphics `
  --dest dist\windows
