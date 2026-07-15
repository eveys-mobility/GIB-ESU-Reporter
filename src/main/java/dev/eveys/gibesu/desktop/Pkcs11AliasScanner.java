package dev.eveys.gibesu.desktop;

import dev.eveys.gibesu.sign.Pkcs11Support;

import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Mali mühür üzerindeki private key alias listesini UI tarafında göstermek için kullanılır.
 * PIN saklamaz; yalnızca token oturumunu açmak için işlem anında kullanır.
 */
public final class Pkcs11AliasScanner {
    private Pkcs11AliasScanner() {}

    public static List<TokenAlias> scan(String pkcs11Library, int slotListIndex, char[] pin) throws Exception {
        if (pkcs11Library == null || pkcs11Library.isBlank()) {
            throw new IllegalArgumentException("PKCS#11 kütüphane yolu boş olamaz.");
        }
        if (pin == null || pin.length == 0) {
            throw new IllegalArgumentException("Alias taramak için mali mühür PIN'i girilmelidir.");
        }

        Provider provider = Pkcs11Support.configureProvider("AKIS_EVEYS_DESKTOP_SCAN", pkcs11Library, slotListIndex, "akis-pkcs11-desktop-scan-");
        KeyStore keyStore = Pkcs11Support.loadKeyStore(provider, pin);

        List<TokenAlias> aliases = new ArrayList<>();
        Enumeration<String> e = keyStore.aliases();
        while (e.hasMoreElements()) {
            String alias = e.nextElement();
            if (!keyStore.isKeyEntry(alias)) continue;
            X509Certificate cert = null;
            try {
                cert = (X509Certificate) keyStore.getCertificate(alias);
            } catch (Exception ignored) { }
            aliases.add(new TokenAlias(
                    alias,
                    cert == null ? "" : cert.getSubjectX500Principal().getName(),
                    cert == null ? "" : cert.getNotAfter().toString()
            ));
        }
        return aliases;
    }

    public record TokenAlias(String alias, String subject, String notAfter) {
        @Override
        public String toString() {
            return alias + (notAfter == null || notAfter.isBlank() ? "" : "  |  Bitiş: " + notAfter);
        }
    }
}
