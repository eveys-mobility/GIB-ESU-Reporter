# CLI Kullanım Kılavuzu

## Build

```bash
mvn -DskipTests package
```

## Config

```bash
cp config/application.example.yml config/application.yml
```

## Aylık Düzenli Gönderim İçin

Her ay tek komutla tüm akışı çalıştırmak için:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar monthly \
  --config config/application.yml \
  --input "sample.xlsx" \
  --period 2026-0 \
  --out out \
  --send
```

Komut sırasıyla generate, validate, sign, verify-signature, package, send ve status adımlarını çalıştırır. `MALI_MUHUR_PIN` ortam değişkeni yoksa terminalden gizli PIN ister.

Status sonucu hemen `success30` dönmezse varsayılan olarak 3 kez, 10 saniye arayla tekrar denenir. Bu davranış `--status-retries` ve `--status-wait-seconds` ile ayarlanabilir.

Canlı ortam için `config/application.yml` içinde `client.environment: "prod"` olmalı ve yanlışlıkla canlı gönderimi önlemek için açık onay verilmelidir:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar monthly \
  --config config/application.yml \
  --input "sample.xlsx" \
  --period 2026-05 \
  --out out-prod \
  --send \
  --confirm-prod "CANLI GONDER"
```

## Generate

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar generate \
  --config config/application.yml \
  --input sample.xlsx \
  --period 2026-05 \
  --out out
```

## Sign

```bash
read -s MALI_MUHUR_PIN
echo
export MALI_MUHUR_PIN

java -jar target/eveys-gib-esu-reporter-0.1.0.jar sign \
  --config config/application.yml \
  --input out/esu-rapor-2026-05-unsigned.xml \
  --out out
```

## Verify

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar verify-signature \
  --input out/esu-rapor-2026-05-signed.xml
```

## Package

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar package \
  --input out/esu-rapor-2026-05-signed.xml \
  --out out
```

## Send / Status

```bash
ZIP_FILE=$(ls -t out/*.zip | head -1)
PAKET_ID=$(basename "$ZIP_FILE" .zip)

java -jar target/eveys-gib-esu-reporter-0.1.0.jar send \
  --config config/application.yml \
  --zip "$ZIP_FILE" \
  --out out

java -jar target/eveys-gib-esu-reporter-0.1.0.jar status \
  --config config/application.yml \
  --paket-id "$PAKET_ID" \
  --out out
```
