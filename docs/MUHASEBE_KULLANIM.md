# Muhasebe Kullanım Kılavuzu

Bu belge terminal kullanmayan personel içindir.

## Gerekli olanlar

- Mali mühür cihazı
- Mali mühür PIN'i
- Aylık Excel raporu
- Eveys GİB EŞÜ uygulaması

## Kullanım

1. Mali mührü takın.
2. Uygulamayı açın.
3. VKN, unvan ve EPDK lisans numarasını kontrol edin.
4. AKİS PKCS#11 alanı boşsa `Otomatik Bul` butonuna basın.
5. Sertifika alias alanı boşsa `SIGN0 Öner` veya `Alias Tara` kullanın.
6. Excel dosyasını seçin.
7. Dönemi girin. Örnek: `2026-05`.
8. PIN girin.
9. `Hazırla ve Test Et` butonuna basın.
10. Test sonucu başarılı olursa `Canlıya Gönder` butonuna basın.
11. Uygulama isterse `CANLI GÖNDER` yazın.
12. Canlı sonuç `success30` ise işlem tamamdır.

## Dikkat

- PIN kimseyle paylaşılmaz.
- Test başarılı olmadan canlı gönderim yapılmaz.
- Aynı dönem ikinci defa gönderilmeden önce yöneticiye danışılır.
