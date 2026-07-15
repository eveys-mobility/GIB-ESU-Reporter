package dev.eveys.gibesu.sign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Pkcs11Support {
    private static final String SUN_PKCS11_PREFIX = "SunPKCS11-";

    private Pkcs11Support() {
    }

    public static Provider configureProvider(String providerName, String pkcs11Library, int slotListIndex, String tempFilePrefix) throws Exception {
        if (isBlank(pkcs11Library)) {
            throw new IllegalArgumentException("signing.pkcs11Library bos. Ornek: /usr/local/lib/libakisp11.dylib");
        }

        Path libraryPath = Path.of(pkcs11Library).toAbsolutePath().normalize();
        if (!Files.exists(libraryPath)) {
            throw new IllegalArgumentException("PKCS#11 kutuphanesi bulunamadi: " + libraryPath);
        }
        if (!Files.isReadable(libraryPath)) {
            throw new IllegalArgumentException("PKCS#11 kutuphanesi okunamiyor: " + libraryPath);
        }

        Provider baseProvider = Security.getProvider("SunPKCS11");
        if (baseProvider == null) {
            throw new IllegalStateException("SunPKCS11 provider bulunamadi. JDK jdk.crypto.cryptoki modulunu icermeli. JDK 17 veya 21 LTS ile tekrar deneyin.");
        }

        String safeProviderName = sanitizeProviderName(providerName);
        String cfg = "name=" + safeProviderName + "\n" +
                "library=" + libraryPath + "\n" +
                "slotListIndex=" + slotListIndex + "\n";
        Path cfgPath = Files.createTempFile(tempFilePrefix, ".cfg");
        Files.writeString(cfgPath, cfg, StandardCharsets.UTF_8);

        try {
            Provider configured = baseProvider.configure(cfgPath.toString());
            Security.addProvider(configured);
            return configured;
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 provider ayarlanamadi. Kutuphane: " + libraryPath + ", slotListIndex: " + slotListIndex + ". AKIS/KamuSM surucusunun kurulu oldugunu, mali muhurun takili oldugunu ve Java mimarisiyle kutuphane mimarisinin uyustugunu kontrol edin.", e);
        }
    }

    public static KeyStore loadKeyStore(Provider provider, char[] pin) throws Exception {
        KeyStore keyStore = newPkcs11KeyStore(provider);
        try {
            keyStore.load(null, pin);
            return keyStore;
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 token oturumu acilamadi. PIN dogru mu, mali muhur takili mi ve slotListIndex dogru mu kontrol edin.", e);
        }
    }

    private static KeyStore newPkcs11KeyStore(Provider provider) throws Exception {
        List<String> candidates = new ArrayList<>();
        candidates.add("PKCS11");
        String suffix = configuredProviderSuffix(provider);
        if (!suffix.isBlank()) {
            candidates.add("PKCS11-" + suffix);
        }

        Exception firstFailure = null;
        for (String type : candidates) {
            try {
                return KeyStore.getInstance(type, provider);
            } catch (Exception e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        try {
            return KeyStore.getInstance("PKCS11");
        } catch (Exception e) {
            if (firstFailure == null) {
                firstFailure = e;
            }
        }

        throw new IllegalStateException("PKCS#11 KeyStore acilamadi. Provider: " + provider.getName() +
                ". Provider KeyStore servisleri: " + describeKeyStoreServices(provider) +
                ". JDK 17 veya 21 LTS ile calistirmayi deneyin; Java komutu icin gerekirse --add-modules jdk.crypto.cryptoki kullanin.", firstFailure);
    }

    private static String describeKeyStoreServices(Provider provider) {
        List<String> services = provider.getServices().stream()
                .filter(service -> "KeyStore".equalsIgnoreCase(service.getType()))
                .map(service -> service.getType() + "." + service.getAlgorithm())
                .sorted()
                .toList();
        return services.isEmpty() ? "(yok)" : String.join(", ", services);
    }

    private static String configuredProviderSuffix(Provider provider) {
        if (provider == null || provider.getName() == null) {
            return "";
        }
        String name = provider.getName();
        if (name.startsWith(SUN_PKCS11_PREFIX)) {
            return name.substring(SUN_PKCS11_PREFIX.length());
        }
        return name;
    }

    private static String sanitizeProviderName(String providerName) {
        String value = isBlank(providerName) ? "AKIS_EVEYS" : providerName.trim();
        value = value.replaceAll("[^A-Za-z0-9_]", "_");
        return value.toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
