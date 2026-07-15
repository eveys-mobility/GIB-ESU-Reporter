# Eveys GİB EŞÜ Reporter

Eveys GİB EŞÜ Reporter, elektrikli araç şarj hizmetlerine ait aylık EŞÜ bildirimlerini Excel dosyasından okuyup GİB formatında XML üreten, mali mühür ile XAdES-BES imzalayan, ZIP paket oluşturan ve GİB servislerine SOAP WS-Security ile gönderen Java tabanlı bir uygulamadır.

Proje hem terminal kullanımı için CLI komutları hem de operasyon/muhasebe personeli için masaüstü arayüz içerir.

---

## Özellikler

- Excel dosyasından şarj işlem verisi okuma
- Plaka normalizasyonu
- Plaka bazında aylık kWh ve tutar toplama
- GİB EŞÜ XML formatında rapor üretimi
- Gömülü GİB EŞÜ XSD dosyaları ile doğrulama
- Mali mühür ile XAdES-BES XML imzası
- Yerel XML imza doğrulama
- ZIP paket oluşturma
- SOAP WS-Security imzalı GİB gönderimi
- Test ve canlı ortam desteği
- Test başarılı olmadan canlı gönderimi engelleyen GUI akışı
- Başarılı paketleri ve servis cevaplarını arşivleme
- macOS, Windows ve Linux için paketlenebilir masaüstü uygulama
- GitHub Release ile otomatik masaüstü paketleri

---

## Klasör yapısı

```text
src/main/java/dev/eveys/gibesu/
  audit/       Audit dosyaları
  cli/         Terminal komutları
  config/      Ayar modeli
  desktop/     JavaFX arayüz
  excel/       Excel okuma
  gib/         SOAP client ve paketleme
  model/       Veri modelleri
  sign/        PKCS#11 / XAdES-BES imza
  util/        Yardımcı sınıflar
  verify/      XML imza doğrulama
  xml/         GİB XML üretimi ve XSD doğrulama
```

Dokümantasyon:

```text
docs/CLI_USAGE.md
docs/GUI_USAGE.md
docs/MUHASEBE_KULLANIM.md
docs/TRUSTSTORE.md
docs/PACKAGING.md
docs/SECURITY.md
```

---

## Gereksinimler

Geliştirme ortamı için:

- Java 17 veya üzeri; JDK 17 veya 21 LTS önerilir
- Maven 3.x
- JavaFX SDK, yalnızca paketleme için gerekebilir
- AKİS/KamuSM mali mühür sürücüsü

Son kullanıcı için:

- Mali mühür cihazı
- AKİS/KamuSM sürücüsü
- Mali mühür PIN'i
- Aylık Excel raporu
- Paketlenmiş masaüstü uygulama

Son kullanıcıya Maven, kaynak kod veya terminal komutu gerekmez. Nihai kullanım için GitHub Releases üzerinden macOS `.dmg`, Windows `.zip` ve Linux `.tar.gz` paketleri dağıtılabilir.

---

## Masaüstü uygulama

Geliştirme sırasında arayüzü çalıştırmak için:

```bash
mvn javafx:run
```

Arayüz tek ekranda çalışır:

```text
VKN
Unvan
EPDK lisans no
AKİS PKCS#11 yolu
Sertifika alias
PIN
Excel dosyası
Dönem
Hazırla ve Test Et
Canlıya Gönder
```

Güvenlik davranışları:

- PIN kaydedilmez.
- Test ortamında başarılı sonuç alınmadan canlı gönderim aktif olmaz.
- Canlı gönderim için ayrıca yazılı onay istenir.
- GİB SSL truststore dosyaları gerekiyorsa kullanıcı klasöründe otomatik hazırlanır.
- Gömülü XSD dosyaları kullanılır; kullanıcıdan ayrıca XSD seçmesi istenmez.
- Config dosyası kullanıcıdan seçtirilmez.

Detaylı kullanım için:

```text
docs/GUI_USAGE.md
docs/MUHASEBE_KULLANIM.md
```

---

## CLI kullanımı

### Hızlı kurulum

Projeyi ilk kez indirdikten sonra Java ve Maven'in görüldüğünü kontrol edin:

```bash
java -version
mvn -version
```

Bağımlılıklar Maven ile otomatik indirilir ve çalıştırılabilir JAR üretilir:

```bash
mvn -DskipTests package
java -jar target/eveys-gib-esu-reporter-0.1.0.jar --help
```

Config dosyasını hazırlayın:

```bash
cp config/application.example.yml config/application.yml
```

`config/application.yml` içinde özellikle şu alanları gerçek değerlere göre doldurun:

```text
company.vkn
company.unvan
company.epdkLisansNo
signing.pkcs11Library
signing.keyAlias
client.environment
```

`signing.keyAlias` boş bırakılırsa token içinde tek private key alias varsa otomatik kullanılır. Birden fazla alias varsa hata mesajında mevcut alias'lar görünür; örnek mali mühür alias biçimleri:

```text
<VKN>SIGN0
<VKN>SIGN1
```

### Excel dosyası

Varsayılan olarak ilk sayfa ve ilk satır başlık satırı kabul edilir. Farklı bir sayfa, başlık satırı veya kolon adı varsa `config/application.yml` içindeki `excel` alanları düzenlenebilir:

```yaml
excel:
  sheetName: ""
  headerRow: 1
  plateColumn: ""
  kwhColumn: ""
  amountColumn: ""
```

Kolon adları boş bırakılırsa uygulama yaygın başlıkları otomatik arar:

```text
Plaka: plaka, plaka no, plate, vehicle plate, arac plaka
kWh: kwh, enerji, tuketim, energy, toplam kwh, sarj miktari
Tutar: tl, tutar, toplam tutar, gelir, amount, price, ucret
```

Rapor dönemi `YYYY-MM` formatında verilmelidir; örnek: `2026-05`.

### Test ortamı akışı

Canlı gönderimden önce `client.environment: "test"` ile test ortamında `success30` alınmalıdır.

Her ay normal kullanım için tek komut kullanılabilir. PIN ortam değişkeninde yoksa komut terminalden gizli şekilde PIN ister:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar monthly \
  --config config/application.yml \
  --input "sample.xlsx" \
  --period 2026-05 \
  --out out \
  --send
```

Bu komut sırasıyla XML üretir, XSD doğrular, mali mühürle imzalar, imzayı yerelde doğrular, ZIP oluşturur, GİB'e gönderir ve status sorgular.

Status sonucu hemen `success30` dönmezse komut varsayılan olarak 3 kez, 10 saniye arayla tekrar dener. Gerekirse şu parametrelerle değiştirilebilir:

```bash
--status-retries 5 --status-wait-seconds 20
```

Mali mühür PIN'ini terminal geçmişine yazmadan girin.

macOS/zsh:

```bash
unset MALI_MUHUR_PIN
read -rs "MALI_MUHUR_PIN?Mali mühür PIN: "
echo
export MALI_MUHUR_PIN
```

bash:

```bash
unset MALI_MUHUR_PIN
read -rsp "Mali mühür PIN: " MALI_MUHUR_PIN
echo
export MALI_MUHUR_PIN
```

Rapor üretin. Canlıya geçmeden önce XSD doğrulama için `--validate` kullanılması önerilir:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar generate \
  --config config/application.yml \
  --input sample.xlsx \
  --period 2026-05 \
  --out out \
  --validate
```

XML'i mali mühür ile imzalayın:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar sign \
  --config config/application.yml \
  --input out/esu-rapor-2026-05-unsigned.xml \
  --out out
```

İmzayı yerelde doğrulayın:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar verify-signature \
  --input out/esu-rapor-2026-05-signed.xml
```

Paketleme:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar package \
  --input out/esu-rapor-2026-05-signed.xml \
  --out out
```

Test ortamına gönderin. `status` komutunu yalnızca `send` başarılı dönerse çalıştırın:

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

Başarılı test cevabı örneği:

```text
HTTP status: 200
GIB return: success30...
```

### Canlı ortam akışı

Test ortamında `success30` alınmadan canlı gönderim yapılmamalıdır.

Canlı gönderim için `config/application.yml` içinde ortamı değiştirin:

```yaml
client:
  environment: "prod"
```

Karışıklığı önlemek için canlı çıktıları ayrı klasöre üretin:

Tek komutla canlı gönderim:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar monthly \
  --config config/application.yml \
  --input "sample.xlsx" \
  --period 2026-05 \
  --out out-prod \
  --send \
  --confirm-prod "CANLI GONDER"
```

Aynı akışı adım adım çalıştırmak isterseniz:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar generate \
  --config config/application.yml \
  --input sample.xlsx \
  --period 2026-05 \
  --out out-prod \
  --validate

java -jar target/eveys-gib-esu-reporter-0.1.0.jar sign \
  --config config/application.yml \
  --input out-prod/esu-rapor-2026-05-unsigned.xml \
  --out out-prod

java -jar target/eveys-gib-esu-reporter-0.1.0.jar verify-signature \
  --input out-prod/esu-rapor-2026-05-signed.xml

java -jar target/eveys-gib-esu-reporter-0.1.0.jar package \
  --input out-prod/esu-rapor-2026-05-signed.xml \
  --out out-prod

ZIP_FILE=$(ls -t out-prod/*.zip | head -1)
PAKET_ID=$(basename "$ZIP_FILE" .zip)

java -jar target/eveys-gib-esu-reporter-0.1.0.jar send \
  --config config/application.yml \
  --zip "$ZIP_FILE" \
  --out out-prod

java -jar target/eveys-gib-esu-reporter-0.1.0.jar status \
  --config config/application.yml \
  --paket-id "$PAKET_ID" \
  --out out-prod
```

Daha detaylı terminal kullanımı için:

```text
docs/CLI_USAGE.md
```

---

## Gömülü GİB EŞÜ dosyaları

GİB EŞÜ XSD ve örnek dosyaları proje içinde yer alır:

```text
gib-esu-paket/Esu_GIB_Paket_V3/esuRapor.xsd
gib-esu-paket/Esu_GIB_Paket_V3/esuOrnek1.xml
gib-esu-paket/Esu_GIB_Paket_V3/esuAnlikRapor.xml
```

Aynı dosyalar Java resource olarak da bulunur:

```text
src/main/resources/gib-esu-paket/Esu_GIB_Paket_V3/
```

Bu yapı sayesinde masaüstü uygulamada kullanıcıdan harici XSD seçmesi istenmez.

---

## AKİS PKCS#11

Mali mühür imzası için AKİS PKCS#11 kütüphanesi gerekir.

Uygulama yaygın yolları otomatik dener. Terminal kullanımında `signing.pkcs11Library` alanı doğru AKİS/KamuSM kütüphanesini göstermelidir.

macOS:

```text
/usr/local/lib/libakisp11.dylib
/Library/Java/Extensions/libakisp11.dylib
```

Windows:

```text
C:\Windows\System32\akisp11.dll
C:\Windows\SysWOW64\akisp11.dll
C:\Program Files\AKIS\akisp11.dll
C:\Program Files (x86)\AKIS\akisp11.dll
```

Otomatik bulunamazsa arayüzden bir defa dosya seçilebilir veya `config/application.yml` içindeki `signing.pkcs11Library` alanı düzenlenebilir.

Mali mühür tokenlarında birden fazla sertifika alias'ı olabilir. Terminalde yanlış alias verilirse hata mesajında mevcut alias'lar listelenir. Yaygın alias biçimi:

```text
XXXXXXXXXXSIGN0
XXXXXXXXXXSIGN1
```

---

## SSL truststore

Java bazı sistemlerde GİB SSL sertifika zincirini otomatik tanımayabilir. Uygulama gerekli truststore dosyalarını kullanıcı klasöründe otomatik hazırlamaya çalışır:

```text
~/.eveys-gib-esu/certs/gib-test-truststore.jks
~/.eveys-gib-esu/certs/gib-prod-truststore.jks
```

CLI ile manuel üretim gerektiğinde:

```text
docs/TRUSTSTORE.md
```

---

## Paketleme

GitHub Release otomasyonu:

```bash
# pom.xml version 0.1.0 ise tag v0.1.0 olmalı
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

Workflow macOS, Windows ve Linux masaüstü paketlerini release asset olarak yükler:

```text
EveysGibEsuReporter-<version>-macos.dmg
EveysGibEsuReporter-<version>-windows.zip
EveysGibEsuReporter-<version>-linux.tar.gz
eveys-gib-esu-reporter-<version>-cli.jar
SHA256SUMS.txt
```

macOS `.dmg` üretimini yerelde denemek için:

```bash
./scripts/package-macos-dmg.sh
```

Windows jpackage app-image ZIP üretimini yerelde denemek için:

```powershell
.\scripts\package-desktop.ps1
```

Windows `.exe` installer üretimi:

```powershell
$env:JAVAFX_HOME="C:\javafx-sdk-21.0.4"
.\scripts\package-windows-exe.ps1
```

Detay:

```text
docs/PACKAGING.md
```

---

## Güvenlik

Repoya eklenmemesi gereken dosyalar:

```text
config/application.yml
Excel dosyaları
Üretilen XML dosyaları
Üretilen ZIP paketleri
SOAP request/response dosyaları
Truststore dosyaları
PEM/JKS/P12/PFX sertifika dosyaları
Mali mühür PIN'i
.env dosyaları
Gerçek şirket bilgileri
```

PIN hiçbir zaman config dosyasına yazılmaz. GUI tarafında PIN sadece işlem sırasında bellekte tutulur.

Detay:

```text
docs/SECURITY.md
```

---

## Sık karşılaşılan hatalar

### `PKIX path building failed`

Java GİB SSL sertifikasını tanımıyordur. Uygulama resmi GİB test/prod hostları için kullanıcı klasöründe otomatik truststore hazırlamaya çalışır. Manuel üretim gerekirse `docs/TRUSTSTORE.md` dosyasına bakın.

### `signing.keyAlias token icinde bulunamadi`

Config içindeki `signing.keyAlias` token içinde yoktur. Hata mesajındaki mevcut alias'lardan şirket mali mührüne ait olan seçilmelidir. Örnek:

```yaml
signing:
  keyAlias: "<VKN>SIGN0"
```

### `Gönderici yetkisi yok`

SOAP isteği GİB'e ulaşmış ancak kullanılan sertifika/VKN için gönderici yetkisi bulunamamıştır. Kişisel e-imza yerine şirket mali mührü kullanılmalı ve GİB test/prod tarafında ilgili VKN için yetki tanımlı olmalıdır.

### `154 İmza doğrulanamadı`

Kontrol edilmesi gerekenler:

```text
verify-signature sonucu başarılı mı?
Doğru mali mühür alias'ı seçildi mi?
ZIP içinde tek XML var mı?
Mali mühür sertifikası geçerli mi?
```

### `XAdES.xsd file access is not allowed`

XSD importları gömülü resource resolver ile çözülmelidir. Bu repo gerekli XAdES ve XMLDSig XSD dosyalarını resource olarak içerir.

### `No WS-Security header found`

SOAP isteği WS-Security imzasız gönderilmiş demektir. Uygulama `send` ve `status` çağrılarında SOAP WS-Security imzası ekler.

### `Log4j2 could not find a logging implementation`

Apache POI tarafından basılan uyarıdır. `generate` komutu raporu üretmişse engelleyici değildir.

---

## Lisans

MIT License

---

## Yapımcı

Bu proje **Eveys** tarafından geliştirilmiştir.
