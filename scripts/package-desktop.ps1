$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RootDir

$AppName = "EveysGibEsuReporter"
$MainClass = "dev.eveys.gibesu.desktop.DesktopApp"
$Version = $env:VERSION
if ([string]::IsNullOrWhiteSpace($Version)) {
  $Version = (& mvn -q help:evaluate "-Dexpression=project.version" "-DforceStdout").Trim()
}
$BaseVersion = ($Version -split "-", 2)[0]
$JPackageVersion = $env:JPACKAGE_VERSION
if ([string]::IsNullOrWhiteSpace($JPackageVersion)) {
  $JPackageVersion = $BaseVersion
}
if ($JPackageVersion.StartsWith("0.")) {
  # macOS jpackage rejects versions whose first integer is 0. Keep release
  # file names on the Maven version, but use a positive app version internally.
  $JPackageVersion = "1." + $JPackageVersion.Substring(2)
}

$PackageType = $env:PACKAGE_TYPE
if ([string]::IsNullOrWhiteSpace($PackageType)) {
  $PackageType = "app-image"
}

$JarName = "eveys-gib-esu-reporter-$Version.jar"
$DistDir = Join-Path $RootDir "dist\desktop-windows"
$InputDir = Join-Path $RootDir "dist\jpackage-input-windows"
$JavaFxDir = Join-Path $RootDir "target\javafx-windows"

Write-Host "Building $AppName $Version for windows ($PackageType)..."
mvn -DskipTests package

Remove-Item -Recurse -Force $DistDir, $InputDir, $JavaFxDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $DistDir, $InputDir, $JavaFxDir | Out-Null

Copy-Item "target\$JarName" (Join-Path $InputDir $JarName)

Write-Host "Preparing JavaFX runtime modules..."
mvn -q dependency:copy-dependencies `
  "-DincludeGroupIds=org.openjfx" `
  "-DoutputDirectory=$JavaFxDir"

$jpackageArgs = @(
  "--type", $PackageType,
  "--name", $AppName,
  "--app-version", $JPackageVersion,
  "--input", $InputDir,
  "--main-jar", $JarName,
  "--main-class", $MainClass,
  "--module-path", $JavaFxDir,
  "--add-modules", "javafx.controls,javafx.graphics",
  "--dest", $DistDir
)

& jpackage @jpackageArgs

if ($PackageType -eq "app-image") {
  $AppImageDir = Join-Path $DistDir $AppName
  $ZipFile = Join-Path $DistDir "$AppName-$Version-windows.zip"
  if (Test-Path $ZipFile) {
    Remove-Item -Force $ZipFile
  }
  Compress-Archive -Path $AppImageDir -DestinationPath $ZipFile
  Write-Host $ZipFile
} elseif ($PackageType -eq "exe") {
  Get-ChildItem $DistDir -Filter "$AppName*.exe" | ForEach-Object {
    Rename-Item $_.FullName "$AppName-$Version-windows.exe"
  }
  Write-Host $DistDir
} else {
  Write-Host $DistDir
}
