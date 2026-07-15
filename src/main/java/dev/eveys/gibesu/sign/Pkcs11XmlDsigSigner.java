package dev.eveys.gibesu.sign;

import dev.eveys.gibesu.config.AppConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * AKIS/PKCS#11 mali muhur ile teknik XMLDSig imza testi.
 *
 * Bu sinif XAdES-BES QualifyingProperties uretmez. GIB canli kabul icin son asamada
 * resmi XAdES-BES profil dogrulamasi yapilmalidir. Buna ragmen PKCS#11 baglantisini,
 * mali muhur private key erisimini ve XML imza akisini test etmek icin faydalidir.
 */
public class Pkcs11XmlDsigSigner implements Signer {
    private static final String EARSIV_NS = "http://earsiv.efatura.gov.tr";

    private final AppConfig.Signing signing;

    public Pkcs11XmlDsigSigner(AppConfig.Signing signing) {
        this.signing = signing;
    }

    @Override
    public Path sign(Path unsignedXml, Path signedXml) throws Exception {
        if (signing == null) {
            throw new IllegalArgumentException("signing config bos.");
        }
        if (isBlank(signing.pkcs11Library)) {
            throw new IllegalArgumentException("signing.pkcs11Library bos. Ornek: /usr/local/lib/libakisp11.dylib");
        }
        if (isBlank(signing.pinEnv)) {
            throw new IllegalArgumentException("signing.pinEnv bos. Ornek: MALI_MUHUR_PIN");
        }
        String pin = System.getProperty(signing.pinEnv);
        if (pin == null || pin.isBlank()) {
            pin = System.getenv(signing.pinEnv);
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN env bulunamadi: " + signing.pinEnv + ". Terminalde export " + signing.pinEnv + "='PIN' seklinde verin; PIN'i chate yazmayin.");
        }

        Provider provider = configurePkcs11Provider();
        KeyStore keyStore = Pkcs11Support.loadKeyStore(provider, pin.toCharArray());

        String alias = resolveAlias(keyStore);
        Key key = keyStore.getKey(alias, null);
        if (!(key instanceof PrivateKey privateKey)) {
            throw new IllegalStateException("Alias private key degil: " + alias);
        }
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
        if (cert == null) {
            throw new IllegalStateException("Alias icin sertifika bulunamadi: " + alias);
        }

        Document document = readXml(unsignedXml);
        Node signParent = findSignatureParent(document);

        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = signatureFactory.newReference(
                "",
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(
                        signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        signatureFactory.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null)
                ),
                null,
                null
        );

        String signatureMethodUri = signatureMethodFor(privateKey);
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(signatureMethodUri, null),
                List.of(reference)
        );

        KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
        X509Data x509Data = keyInfoFactory.newX509Data(List.of(cert));
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(x509Data));

        DOMSignContext signContext = new DOMSignContext(privateKey, signParent);
        signContext.setDefaultNamespacePrefix("ds");
        // Hardware-token anahtarlarinda (SunPKCS11 P11Key) JSR-105 bazen varsayilan
        // provider secimiyle yanlis algoritma/provider kombinasyonuna dusuyor. Bu property
        // imza operasyonunu dogrudan AKIS PKCS#11 provider uzerinden yaptirir.
        signContext.setProperty("org.jcp.xml.dsig.internal.dom.SignatureProvider", provider);

        XMLSignature signature = signatureFactory.newXMLSignature(signedInfo, keyInfo);
        signature.sign(signContext);

        Files.createDirectories(signedXml.toAbsolutePath().getParent());
        writeXml(document, signedXml);

        System.out.println("PKCS#11 provider: " + provider.getName());
        System.out.println("Kullanilan sertifika alias: " + alias);
        System.out.println("Private key algoritmasi: " + privateKey.getAlgorithm());
        System.out.println("XML SignatureMethod: " + signatureMethodUri);
        System.out.println("Sertifika subject: " + cert.getSubjectX500Principal().getName());
        System.out.println("Sertifika bitis: " + cert.getNotAfter());
        System.out.println("Not: Bu cikti teknik XMLDSig imza testidir; GIB canli kabul icin XAdES-BES profili ayrica dogrulanmalidir.");
        return signedXml;
    }


    private String signatureMethodFor(PrivateKey privateKey) {
        String algorithm = privateKey.getAlgorithm();
        if (algorithm == null) {
            return SignatureMethod.RSA_SHA256;
        }
        String normalized = algorithm.trim().toUpperCase();
        if (normalized.contains("EC") || normalized.contains("ECDSA")) {
            return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";
        }
        if (normalized.contains("RSA")) {
            return SignatureMethod.RSA_SHA256;
        }
        throw new IllegalArgumentException("Desteklenmeyen private key algoritmasi: " + algorithm + ". RSA veya EC/ECDSA bekleniyordu.");
    }

    private Provider configurePkcs11Provider() throws Exception {
        int slotListIndex = signing.slotListIndex == null ? 0 : signing.slotListIndex;
        return Pkcs11Support.configureProvider("AKIS_EVEYS", signing.pkcs11Library, slotListIndex, "akis-pkcs11-");
    }

    private String resolveAlias(KeyStore keyStore) throws Exception {
        List<String> privateKeyAliases = new ArrayList<>();
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                privateKeyAliases.add(alias);
            }
        }
        if (!isBlank(signing.keyAlias)) {
            for (String alias : privateKeyAliases) {
                if (alias.equals(signing.keyAlias) || alias.equalsIgnoreCase(signing.keyAlias)) {
                    return alias;
                }
            }
            throw new IllegalArgumentException("signing.keyAlias token icinde bulunamadi: " + signing.keyAlias + ". Mevcut aliaslar: " + privateKeyAliases);
        }
        if (privateKeyAliases.size() == 1) {
            return privateKeyAliases.get(0);
        }
        throw new IllegalArgumentException("Birden fazla private key alias var. config/application.yml icinde signing.keyAlias alanini secin. Mevcut aliaslar: " + privateKeyAliases);
    }

    private Document readXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private Node findSignatureParent(Document document) {
        var baslikNodes = document.getElementsByTagNameNS(EARSIV_NS, "baslik");
        if (baslikNodes != null && baslikNodes.getLength() > 0) {
            return baslikNodes.item(0);
        }
        return document.getDocumentElement();
    }

    private void writeXml(Document document, Path path) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
        transformer.transform(new DOMSource(document), new StreamResult(path.toFile()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
