# Paketleme

Nihai kullanıcıya kaynak kod verilmez. macOS için `.dmg`, Windows için `.exe/.msi` verilmelidir.

## macOS

```bash
export JAVAFX_HOME=/path/to/javafx-sdk-21.0.4
./scripts/package-macos-dmg.sh
```

Çıktı:

```text
dist/macos/EveysGibEsuReporter-0.1.0.dmg
```

## Windows

```powershell
$env:JAVAFX_HOME="C:\javafx-sdk-21.0.4"
.\scripts\package-windows-exe.ps1
```

Çıktı:

```text
dist\windows\EveysGibEsuReporter-0.1.0.exe
```
