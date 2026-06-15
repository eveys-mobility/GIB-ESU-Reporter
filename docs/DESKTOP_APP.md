# Masaüstü Uygulama

Masaüstü uygulama, muhasebe/operasyon personelinin terminal kullanmadan EŞÜ raporu göndermesi için hazırlanmıştır.

## Tek ekran akışı

```text
VKN / Unvan / EPDK lisans no
AKİS PKCS#11 yolu
Sertifika alias
PIN
Excel dosyası
Dönem
Hazırla ve Test Et
Canlıya Gönder
```

## Güvenlik davranışları

- PIN kaydedilmez.
- Test ortamında success30 alınmadan canlı gönderim açılmaz.
- Canlı gönderim için yazılı onay istenir.
- Truststore dosyaları kullanıcı klasöründe otomatik hazırlanır.
- Config dosyası kullanıcıdan seçtirilmez.
