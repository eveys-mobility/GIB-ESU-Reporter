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
mvn package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Remove-Item -Recurse -Force $DistDir, $InputDir, $JavaFxDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $DistDir, $InputDir, $JavaFxDir | Out-Null

Copy-Item "target\$JarName" (Join-Path $InputDir $JarName)

Write-Host "Preparing JavaFX runtime modules..."
mvn -q dependency:copy-dependencies `
  "-DincludeGroupIds=org.openjfx" `
  "-DoutputDirectory=$JavaFxDir"
if ($LASTEXITCODE -ne 0) { throw "JavaFX dependency copy failed" }

$jpackageArgs = @(
  "--type", "app-image",
  "--name", $AppName,
  "--app-version", $JPackageVersion,
  "--input", $InputDir,
  "--main-jar", $JarName,
  "--main-class", $MainClass,
  "--module-path", $JavaFxDir,
  "--add-modules", "java.se,javafx.controls,javafx.graphics,jdk.crypto.cryptoki,jdk.localedata",
  "--java-options", "--add-modules=jdk.crypto.cryptoki",
  "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
  "--dest", $DistDir
)

& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "Desktop packaging failed" }

$AppImageDir = Join-Path $DistDir $AppName
$RuntimeJava = Join-Path $AppImageDir "runtime\bin\java.exe"
$PackagedJar = Join-Path $AppImageDir "app\$JarName"
$CheckDir = Join-Path $RootDir ("target\runtime-check-windows-" + [guid]::NewGuid().ToString("N"))
$CheckClasses = Join-Path $CheckDir "classes"
$CheckHome = Join-Path $CheckDir "home"
$CheckWork = Join-Path $CheckDir "work"
$InputJar = Join-Path $InputDir $JarName
New-Item -ItemType Directory -Force $CheckClasses | Out-Null
& javac --release 17 -cp $InputJar -d $CheckClasses (Join-Path $PSScriptRoot "RuntimeSmokeCheck.java")
if ($LASTEXITCODE -ne 0) { throw "Runtime check compilation failed" }
Push-Location ([System.IO.Path]::GetPathRoot($RootDir.Path))
try {
  & $RuntimeJava --add-modules jdk.crypto.cryptoki "-Duser.home=$CheckHome" `
    -cp "$CheckClasses;$PackagedJar" dev.eveys.gibesu.desktop.RuntimeSmokeCheck $CheckWork
  if ($LASTEXITCODE -ne 0) { throw "Packaged runtime check failed" }
} finally {
  Pop-Location
}

if ($PackageType -ne "app-image") {
  & jpackage --type $PackageType --name $AppName --app-image $AppImageDir --dest $DistDir
  if ($LASTEXITCODE -ne 0) { throw "Installer packaging failed" }
}

if ($PackageType -eq "app-image") {
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
