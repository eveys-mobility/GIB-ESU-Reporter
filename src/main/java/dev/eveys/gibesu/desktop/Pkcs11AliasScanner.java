package dev.eveys.gibesu.desktop;

import dev.eveys.gibesu.sign.Pkcs11Support;

import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Mali mühür üzerindeki private key alias listesini UI tarafında göstermek için kullanılır.
 * PIN saklamaz; yalnızca token oturumunu açmak için işlem anında kullanır.
 */
public final class Pkcs11AliasScanner {
    private static final int FIRST_AUTOMATIC_SLOT = 0;
    private static final int LAST_AUTOMATIC_SLOT = 9;

    private Pkcs11AliasScanner() {}

    public static List<TokenAlias> scan(String pkcs11Library, int slotListIndex, char[] pin) throws Exception {
        if (pkcs11Library == null || pkcs11Library.isBlank()) {
            throw new IllegalArgumentException("PKCS#11 kütüphane yolu boş olamaz.");
        }
        if (pin == null || pin.length == 0) {
            throw new IllegalArgumentException("Alias taramak için mali mühür PIN'i girilmelidir.");
        }

        List<Integer> slots = candidateSlots(slotListIndex);

        List<String> failures = new ArrayList<>();
        for (int candidateSlot : slots) {
            try {
                List<TokenAlias> aliases = scanSlot(pkcs11Library, candidateSlot, pin);
                if (!aliases.isEmpty()) {
                    return aliases;
                }
                failures.add("slot " + candidateSlot + ": private key alias bulunamadi");
            } catch (Exception e) {
                failures.add("slot " + candidateSlot + ": " + conciseFailure(e));
                if (!shouldTryOtherSlots(e)) {
                    throw e;
                }
            }
        }

        throw new IllegalStateException("PKCS#11 alias taranamadi; hicbir slot calismadi. Windows'ta bu durum genellikle yanlis akisp11.dll yolu, AKİS surucusu ile Java'nin 32/64-bit mimari uyusmazligi, mali muhurun takili olmamasi veya yanlis slotListIndex nedeniyle olur. Denemeler: " + String.join("; ", failures));
    }

    static List<Integer> candidateSlots(int configuredSlot) {
        List<Integer> slots = new ArrayList<>();
        slots.add(configuredSlot);
        for (int candidate = FIRST_AUTOMATIC_SLOT; candidate <= LAST_AUTOMATIC_SLOT; candidate++) {
            if (!slots.contains(candidate)) {
                slots.add(candidate);
            }
        }
        return slots;
    }

    private static List<TokenAlias> scanSlot(String pkcs11Library, int slotListIndex, char[] pin) throws Exception {
        Provider provider = null;
        try {
            provider = Pkcs11Support.configureProvider("AKIS_EVEYS_DESKTOP_SCAN_" + slotListIndex,
                    pkcs11Library, slotListIndex, "akis-pkcs11-desktop-scan-");
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
                        cert == null ? "" : cert.getNotAfter().toString(),
                        slotListIndex
                ));
            }
            return aliases;
        } finally {
            removeConfiguredProvider(provider);
        }
    }

    private static void removeConfiguredProvider(Provider provider) {
        if (provider != null && provider.getName() != null && Security.getProvider(provider.getName()) == provider) {
            Security.removeProvider(provider.getName());
        }
    }

    static boolean shouldTryOtherSlots(Exception failure) {
        String messages = failureMessages(failure);
        if (messages.contains("ckr_pin") || messages.contains("pin incorrect") ||
                messages.contains("pin_incorrect") || messages.contains("pin locked") ||
                messages.contains("pin_locked") || messages.contains("wrong pin") ||
                messages.contains("authentication failed") || messages.contains("login failed")) {
            return false;
        }
        return messages.contains("pkcs#11 keystore acilamadi") ||
                messages.contains("keystore service") ||
                messages.contains("pkcs11 not found") ||
                messages.contains("no such algorithm: pkcs11") ||
                messages.contains("provider keystore servisleri: (yok)") ||
                messages.contains("pkcs#11 provider ayarlanamadi") ||
                messages.contains("token not present") ||
                messages.contains("ckr_token_not_present") ||
                messages.contains("slot id invalid") ||
                messages.contains("ckr_slot_id_invalid");
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                if (!messages.isEmpty()) {
                    messages.append(' ');
                }
                messages.append(message.toLowerCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static String conciseFailure(Exception failure) {
        Throwable current = failure;
        while (current.getMessage() == null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 180 ? message.substring(0, 177) + "..." : message;
    }

    public record TokenAlias(String alias, String subject, String notAfter, int slotListIndex) {
        public TokenAlias(String alias, String subject, String notAfter) {
            this(alias, subject, notAfter, -1);
        }

        @Override
        public String toString() {
            return alias + (notAfter == null || notAfter.isBlank() ? "" : "  |  Bitiş: " + notAfter);
        }
    }
}
