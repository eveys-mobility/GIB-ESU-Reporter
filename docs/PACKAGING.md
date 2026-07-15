# Paketleme

Nihai kullanıcıya kaynak kod verilmez. GitHub Release akışı macOS, Windows ve Linux paketlerini otomatik üretir.

## GitHub Release

Release workflow dosyası:

```text
.github/workflows/release.yml
```

Yeni sürüm yayınlamak için:

```bash
# pom.xml içindeki version ile tag aynı olmalı
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

Workflow şu asset'leri üretir:

```text
EveysGibEsuReporter-<version>-macos.dmg
EveysGibEsuReporter-<version>-windows.zip
EveysGibEsuReporter-<version>-linux.tar.gz
eveys-gib-esu-reporter-<version>-cli.jar
SHA256SUMS.txt
```

Windows ve Linux paketleri jpackage app-image olarak hazırlanır ve kendi Java runtime'ını içerir. macOS paketi DMG olarak hazırlanır; imzasız olduğu için ilk açılışta Gatekeeper ek onay isteyebilir.

GitHub repo ayarlarında Actions'ın release yazabilmesi için `Settings > Actions > General > Workflow permissions` alanında write izni açık olmalıdır.

## macOS

```bash
./scripts/package-macos-dmg.sh
```

Çıktı:

```text
dist/desktop-macos/EveysGibEsuReporter-0.1.0-macos.dmg
```

## Windows

GitHub Release workflow'u Windows için içinde Java runtime bulunan portable ZIP üretir. Yerelde Windows üzerinde aynı jpackage app-image çıktısını almak için:

```powershell
.\scripts\package-desktop.ps1
```

### Portable ZIP

macOS veya Linux üzerinde Windows kullanıcısına gönderilecek, Java 17/21 kurulu olmasını bekleyen daha basit taşınabilir paket:

```bash
./scripts/package-windows-portable.sh
```

Çıktı:

```text
dist/windows-portable/EveysGibEsuReporter-0.1.0-windows-portable.zip
```

Kullanıcı ZIP'i açıp `Baslat.bat` dosyasını çalıştırır. Paket gerçek `config/application.yml`, Excel, XML/ZIP çıktı veya sertifika dosyası içermez.

### Windows EXE Installer

Windows üzerinde installer üretmek için:

```powershell
$env:JAVAFX_HOME="C:\javafx-sdk-21.0.4"
.\scripts\package-windows-exe.ps1
```

Çıktı:

```text
dist\windows\EveysGibEsuReporter-0.1.0.exe
```

## Linux

Yerelde Linux app-image arşivi üretmek için:

```bash
./scripts/package-desktop.sh
```

Çıktı:

```text
dist/desktop-linux/EveysGibEsuReporter-0.1.0-linux.tar.gz
```
