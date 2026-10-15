package dev.eveys.gibesu.sign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AuthProvider;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Pkcs11Support {
    private static final String SUN_PKCS11_PREFIX = "SunPKCS11-";
    private static final Pattern TOKEN_ERROR_CODE = Pattern.compile("\\bCKR_[A-Z0-9_]+\\b");
    /*
     * Ayni kutuphane/slot icin JVM boyunca tek SunPKCS11 provider kullanilir.
     * Her imzada yeni provider acmak (XAdES + send + 5 status = 7 provider) kartta
     * kapanmayan oturumlar biriktiriyor ve birkac imzadan sonra C_SignInit
     * "Initialization failed" ile dusuyordu.
     */
    private static final Map<String, Provider> PROVIDERS = new HashMap<>();

    private Pkcs11Support() {
    }

    public static synchronized Provider configureProvider(String providerName, String pkcs11Library, int slotListIndex, String tempFilePrefix) throws Exception {
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

        String cacheKey = libraryPath + "#" + slotListIndex;
        Provider cached = PROVIDERS.get(cacheKey);
        if (cached != null) {
            return cached;
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
            PROVIDERS.put(cacheKey, configured);
            return configured;
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 provider ayarlanamadi. Kutuphane: " + libraryPath + ", slotListIndex: " + slotListIndex + ". AKIS/KamuSM surucusunun kurulu oldugunu, mali muhurun takili oldugunu ve Java mimarisiyle kutuphane mimarisinin uyustugunu kontrol edin.", e);
        } finally {
            Files.deleteIfExists(cfgPath);
        }
    }

    /**
     * Kart oturumu bozuldugunda (or. kart baska bir uygulama tarafindan resetlendiginde)
     * onbellekteki provider'lari birakir; sonraki configureProvider cagrisi yeni oturum acar.
     */
    public static synchronized void resetProviders() {
        for (Provider provider : PROVIDERS.values()) {
            try {
                if (provider instanceof AuthProvider authProvider) {
                    authProvider.logout();
                }
            } catch (Exception ignored) {
                // Oturum zaten dusmus olabilir; yeni provider ile devam edilir.
            }
            if (Security.getProvider(provider.getName()) == provider) {
                Security.removeProvider(provider.getName());
            }
        }
        PROVIDERS.clear();
    }

    /** Hata zincirindeki PKCS#11 kodlarini (CKR_...) kullaniciya gosterilecek sekilde toplar. */
    public static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(String.valueOf(error.getMessage()));
        for (Throwable cause = error.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && !text.toString().contains(message)) {
                text.append(" <- ").append(message);
            }
        }
        return text.toString();
    }

    public static KeyStore loadKeyStore(Provider provider, char[] pin) throws Exception {
        KeyStore keyStore = newPkcs11KeyStore(provider);
        try {
            keyStore.load(null, pin);
            return keyStore;
        } catch (Exception e) {
            throw tokenLoginFailure(e);
        }
    }

    static IllegalStateException tokenLoginFailure(Exception cause) {
        List<String> codes = new ArrayList<>();
        for (Throwable current = cause; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            Matcher matcher = TOKEN_ERROR_CODE.matcher(message.toUpperCase(Locale.ROOT));
            while (matcher.find()) {
                if (!codes.contains(matcher.group())) codes.add(matcher.group());
            }
        }

        String message;
        if (codes.contains("CKR_PIN_LOCKED")) {
            message = "Mali muhur PIN'i kilitli (CKR_PIN_LOCKED). Tekrar PIN denemeyin. " +
                    "Kamu SM Online Islemler > Mali Muhur Islemleri > PIN Olusturma/Kilit Cozme " +
                    "adimlarini tamamlayin: https://onlineislemler.kamusm.gov.tr . " +
                    "AKIS uzerinden PUK ile islem yapmayin.";
        } else if (codes.contains("CKR_PIN_INCORRECT")) {
            message = "Mali muhur PIN'i hatali (CKR_PIN_INCORRECT). Emin olmadan tekrar denemeyin; " +
                    "hatali denemeler kartin PIN'ini kilitleyebilir.";
        } else if (codes.contains("CKR_PIN_LEN_RANGE") || codes.contains("CKR_PIN_INVALID")) {
            message = "Mali muhur PIN bicimi veya uzunlugu gecersiz. PIN'i kontrol edin. " + String.join(", ", codes);
        } else if (codes.contains("CKR_PIN_EXPIRED")) {
            message = "Mali muhur PIN'inin yenilenmesi gerekiyor (CKR_PIN_EXPIRED). " +
                    "Kamu SM Online Islemler uzerinden PIN islemlerini tamamlayin.";
        } else if (codes.contains("CKR_TOKEN_NOT_PRESENT") || codes.contains("CKR_DEVICE_REMOVED")) {
            message = "Mali muhur kartina ulasilamiyor. Kart ve okuyucu baglantisini kontrol edin. " + String.join(", ", codes);
        } else {
            message = "PKCS#11 token oturumu acilamadi. PIN dogru mu, mali muhur takili mi ve slotListIndex dogru mu kontrol edin.";
            if (!codes.isEmpty()) message += " Token hata kodu: " + String.join(", ", codes);
        }
        return new IllegalStateException(message, cause);
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
