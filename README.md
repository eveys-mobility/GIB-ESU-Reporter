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
- macOS ve Windows için paketlenebilir masaüstü uygulama

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

- Java 17 veya üzeri
- Maven 3.x
- JavaFX SDK, yalnızca paketleme için gerekebilir
- AKİS/KamuSM mali mühür sürücüsü

Son kullanıcı için:

- Mali mühür cihazı
- AKİS/KamuSM sürücüsü
- Mali mühür PIN'i
- Aylık Excel raporu
- Paketlenmiş masaüstü uygulama

Son kullanıcıya Maven, kaynak kod veya terminal komutu gerekmez. Nihai kullanım için macOS tarafında `.dmg`, Windows tarafında `.exe` veya `.msi` verilmesi önerilir.

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

Build:

```bash
mvn -DskipTests package
```

Örnek config hazırlama:

```bash
cp config/application.example.yml config/application.yml
```

Rapor üretme:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar generate \
  --config config/application.yml \
  --input sample.xlsx \
  --period 2026-05 \
  --out out
```

Mali mühür PIN'i:

```bash
read -s MALI_MUHUR_PIN
echo
export MALI_MUHUR_PIN
```

İmzalama:

```bash
java -jar target/eveys-gib-esu-reporter-0.1.0.jar sign \
  --config config/application.yml \
  --input out/esu-rapor-2026-05-unsigned.xml \
  --out out
```

İmzayı doğrulama:

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

Gönderim ve durum sorgulama:

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

Uygulama yaygın yolları otomatik dener.

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

Otomatik bulunamazsa arayüzden bir defa dosya seçilebilir.

Mali mühür tokenlarında birden fazla sertifika alias'ı olabilir. Test gönderimi ile doğru alias doğrulanmalıdır. Yaygın alias biçimi:

```text
XXXXXXXXXXSIGN0
XXXXXXXXXXSIGN1
```

---

## SSL truststore

Java bazı sistemlerde GİB SSL sertifika zincirini otomatik tanımayabilir. Masaüstü uygulama gerekli truststore dosyalarını kullanıcı klasöründe otomatik hazırlamaya çalışır:

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

macOS `.dmg` üretimi:

```bash
export JAVAFX_HOME=/path/to/javafx-sdk-21.0.4
./scripts/package-macos-dmg.sh
```

Windows `.exe` üretimi:

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

Java GİB SSL sertifikasını tanımıyordur. Masaüstü uygulama truststore üretmeyi otomatik dener. CLI kullanımı için `docs/TRUSTSTORE.md` dosyasına bakın.

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

---

## Lisans

MIT License

---

## Yapımcı

Bu proje **Eveys** tarafından geliştirilmiştir.
