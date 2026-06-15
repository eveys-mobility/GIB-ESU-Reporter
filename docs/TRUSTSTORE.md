# GİB SSL Truststore Üretimi

Java bazı ortamlarda GİB test/prod SSL sertifika zincirini tanımayabilir.

## Test

```bash
mkdir -p certs/test

openssl s_client -showcerts \
  -servername okctest.gib.gov.tr \
  -connect okctest.gib.gov.tr:443 </dev/null \
  > certs/test/gib-test-chain-raw.txt 2>&1

awk '
/-----BEGIN CERTIFICATE-----/ {
  n++;
  f=sprintf("certs/test/gib-test-%02d.pem", n);
  print > f;
  next;
}
f != "" { print > f; }
/-----END CERTIFICATE-----/ { close(f); f=""; }
' certs/test/gib-test-chain-raw.txt

rm -f certs/gib-test-truststore.jks
for f in certs/test/gib-test-*.pem; do
  alias=$(basename "$f" .pem)
  keytool -importcert -noprompt \
    -alias "$alias" \
    -file "$f" \
    -keystore certs/gib-test-truststore.jks \
    -storepass changeit
done
```

## Prod

```bash
mkdir -p certs/prod

openssl s_client -showcerts \
  -servername okc.gib.gov.tr \
  -connect okc.gib.gov.tr:443 </dev/null \
  > certs/prod/gib-prod-chain-raw.txt 2>&1

awk '
/-----BEGIN CERTIFICATE-----/ {
  n++;
  f=sprintf("certs/prod/gib-prod-%02d.pem", n);
  print > f;
  next;
}
f != "" { print > f; }
/-----END CERTIFICATE-----/ { close(f); f=""; }
' certs/prod/gib-prod-chain-raw.txt

rm -f certs/gib-prod-truststore.jks
for f in certs/prod/gib-prod-*.pem; do
  alias=$(basename "$f" .pem)
  keytool -importcert -noprompt \
    -alias "$alias" \
    -file "$f" \
    -keystore certs/gib-prod-truststore.jks \
    -storepass changeit
done
```

Truststore dosyaları public repoya eklenmemelidir.
