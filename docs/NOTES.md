# Teknik Notlar

Bu proje GİB EŞÜ raporlama sürecini temiz bir Java uygulaması olarak uygular.

## XML formatı

XML üretimi GİB EŞÜ XSD dosyasına göre yapılır. XSD ve örnek dosyalar proje içinde resource olarak yer alır.

## İmza

XML dosyası mali mühür ile XAdES-BES formatında imzalanır. İmzalama PKCS#11 üzerinden yapılır.

## Gönderim

GİB gönderimi SOAP WS-Security imzası ile yapılır. `send` ve `status` çağrılarında SOAP imzası otomatik eklenir.

## Güvenlik

PIN, sertifika/truststore dosyaları, Excel verileri ve üretilen XML/ZIP/SOAP çıktıları repoya eklenmemelidir.
