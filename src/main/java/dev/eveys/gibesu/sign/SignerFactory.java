package dev.eveys.gibesu.sign;

import dev.eveys.gibesu.config.AppConfig;

public class SignerFactory {
    public Signer create(AppConfig.Signing signing) {
        String mode = signing == null || signing.mode == null ? "none" : signing.mode.trim().toLowerCase();
        return switch (mode) {
            case "none" -> new NoopSigner();
            case "pkcs11-xml-dsig", "pkcs11-test" -> new Pkcs11XmlDsigSigner(signing);
            case "pkcs11-xades", "pkcs11-xades-bes", "xades-bes", "xades" -> new Pkcs11XadesBesSigner(signing);
            case "esya-ma3" -> new XadesBesSigner();
            default -> throw new IllegalArgumentException("Bilinmeyen signing.mode: " + mode);
        };
    }
}
