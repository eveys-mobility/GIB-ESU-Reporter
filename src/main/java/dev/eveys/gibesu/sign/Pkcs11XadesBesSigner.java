package dev.eveys.gibesu.sign;

import dev.eveys.gibesu.config.AppConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
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
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * AKIS/PKCS#11 mali muhur ile XAdES-BES benzeri imza uretir.
 *
 * Uretilen imza su ek XAdES alanlarini icerir:
 * - xades:QualifyingProperties
 * - xades:SignedProperties
 * - xades:SigningTime
 * - xades:SigningCertificate
 * - xades:CertDigest
 * - xades:IssuerSerial
 *
 * GIB canli gonderiminden once resmi EŞÜ XSD/WSDL ve GIB test ortami ile kabul testi yapilmalidir.
 */
public class Pkcs11XadesBesSigner implements Signer {
    private static final String EARSIV_NS = "http://earsiv.efatura.gov.tr";
    private static final String DS_NS = XMLSignature.XMLNS;
    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String XADES_SIGNED_PROPERTIES_TYPE = "http://uri.etsi.org/01903#SignedProperties";
    private static final String SHA256_ALG = DigestMethod.SHA256;

    private final AppConfig.Signing signing;

    public Pkcs11XadesBesSigner(AppConfig.Signing signing) {
        this.signing = signing;
    }

    @Override
    public Path sign(Path unsignedXml, Path signedXml) throws Exception {
        validateConfig();
        String pin = System.getProperty(signing.pinEnv);
        if (pin == null || pin.isBlank()) {
            pin = System.getenv(signing.pinEnv);
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN env bulunamadi: " + signing.pinEnv + ". Terminalde read -s " + signing.pinEnv + " ile girin; PIN'i chate yazmayin.");
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
        String uuid = UUID.randomUUID().toString();
        String signatureId = "Signature-" + uuid;
        String signedPropertiesId = "SignedProperties-" + uuid;
        String objectId = "XadesObject-" + uuid;

        Element qualifyingProperties = buildQualifyingProperties(document, cert, signatureId, signedPropertiesId);
        Element signedPropertiesElement = findChildElement(qualifyingProperties, XADES_NS, "SignedProperties");
        if (signedPropertiesElement == null) {
            throw new IllegalStateException("XAdES SignedProperties olusturulamadi.");
        }
        signedPropertiesElement.setIdAttribute("Id", true);

        /*
         * Electroop/ESYA akışında kök belge referansı için Transform listesinde yalnızca
         * ENVELOPED imza transform'u kullanılıyor. Önceki sürümlerde buraya eklediğimiz
         * Exclusive C14N transform'u GİB/ESYA tarafında 154 "İmza doğrulanamadı" sonucuna
         * sebep olabildiği için burada ESYA çıktısına daha yakın davranıyoruz.
         */
        Reference documentReference = signatureFactory.newReference(
                "",
                signatureFactory.newDigestMethod(SHA256_ALG, null),
                List.of(signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null,
                "Reference-Document-" + uuid
        );

        /*
         * ESYA XAdES-BES üretiminde SignedProperties referansı Type ile eklenir;
         * ayrı bir transform şartı yoktur. Java XMLDSig, node-set digest'ini kendi
         * canonicalization akışıyla üretir.
         */
        Reference signedPropertiesReference = signatureFactory.newReference(
                "#" + signedPropertiesId,
                signatureFactory.newDigestMethod(SHA256_ALG, null),
                null,
                XADES_SIGNED_PROPERTIES_TYPE,
                "Reference-SignedProperties-" + uuid
        );

        String signatureMethodUri = signatureMethodFor(privateKey);
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(signatureMethodUri, null),
                List.of(documentReference, signedPropertiesReference)
        );

        KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
        X509Data x509Data = keyInfoFactory.newX509Data(List.of(cert));
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(x509Data), "KeyInfo-" + uuid);

        XMLObject xadesObject = signatureFactory.newXMLObject(
                List.of((XMLStructure) new DOMStructure(qualifyingProperties)),
                objectId,
                null,
                null
        );

        DOMSignContext signContext = new DOMSignContext(privateKey, signParent);
        // GIB resmi EŞÜ örneklerinde ds elemanları default namespace ile, prefixsiz kullanılmış.
        // İç XML imzasını bu forma yaklaştırmak için XMLDSig namespace prefixini boş bırakıyoruz.
        signContext.setDefaultNamespacePrefix("");
        signContext.putNamespacePrefix(DS_NS, "");
        signContext.putNamespacePrefix(XADES_NS, "xades");
        signContext.setIdAttributeNS(signedPropertiesElement, null, "Id");
        signContext.setProperty("org.jcp.xml.dsig.internal.dom.SignatureProvider", provider);

        XMLSignature signature = signatureFactory.newXMLSignature(
                signedInfo,
                keyInfo,
                List.of(xadesObject),
                signatureId,
                null
        );
        signature.sign(signContext);

        Files.createDirectories(signedXml.toAbsolutePath().getParent());
        writeXml(document, signedXml);

        System.out.println("PKCS#11 provider: " + provider.getName());
        System.out.println("Kullanilan sertifika alias: " + alias);
        System.out.println("Private key algoritmasi: " + privateKey.getAlgorithm());
        System.out.println("XML SignatureMethod: " + signatureMethodUri);
        System.out.println("XAdES SignedProperties Id: " + signedPropertiesId);
        System.out.println("Sertifika subject: " + cert.getSubjectX500Principal().getName());
        System.out.println("Sertifika bitis: " + cert.getNotAfter());
        System.out.println("Not: Bu cikti XAdES-BES yapisini uretir; EC anahtarda ECDSA-SHA384 kullanir; belge referansi ESYA uyumlu ENVELOPED transform ile atilir; XMLDSig elemanlari GIB orneklerindeki gibi default namespace/prefixsiz uretilir; GIB test ortami ile dogrulayin.");
        return signedXml;
    }

    private Element buildQualifyingProperties(Document document, X509Certificate cert, String signatureId, String signedPropertiesId) throws Exception {
        Element qp = document.createElementNS(XADES_NS, "xades:QualifyingProperties");
        qp.setAttribute("Target", "#" + signatureId);
        qp.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xades", XADES_NS);
        
        Element signedProperties = document.createElementNS(XADES_NS, "xades:SignedProperties");
        signedProperties.setAttribute("Id", signedPropertiesId);
        qp.appendChild(signedProperties);

        Element signedSignatureProperties = document.createElementNS(XADES_NS, "xades:SignedSignatureProperties");
        signedProperties.appendChild(signedSignatureProperties);

        Element signingTime = document.createElementNS(XADES_NS, "xades:SigningTime");
        signingTime.setTextContent(OffsetDateTime.now(ZoneId.of("Europe/Istanbul")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        signedSignatureProperties.appendChild(signingTime);

        Element signingCertificate = document.createElementNS(XADES_NS, "xades:SigningCertificate");
        signedSignatureProperties.appendChild(signingCertificate);

        Element certElement = document.createElementNS(XADES_NS, "xades:Cert");
        signingCertificate.appendChild(certElement);

        Element certDigest = document.createElementNS(XADES_NS, "xades:CertDigest");
        certElement.appendChild(certDigest);

        Element digestMethod = document.createElementNS(DS_NS, "DigestMethod");
        digestMethod.setAttribute("Algorithm", SHA256_ALG);
        certDigest.appendChild(digestMethod);

        Element digestValue = document.createElementNS(DS_NS, "DigestValue");
        digestValue.setTextContent(base64Sha256(cert.getEncoded()));
        certDigest.appendChild(digestValue);

        Element issuerSerial = document.createElementNS(XADES_NS, "xades:IssuerSerial");
        certElement.appendChild(issuerSerial);

        Element x509IssuerName = document.createElementNS(DS_NS, "X509IssuerName");
        x509IssuerName.setTextContent(cert.getIssuerX500Principal().getName());
        issuerSerial.appendChild(x509IssuerName);

        Element x509SerialNumber = document.createElementNS(DS_NS, "X509SerialNumber");
        BigInteger serial = cert.getSerialNumber();
        x509SerialNumber.setTextContent(serial.toString());
        issuerSerial.appendChild(x509SerialNumber);

        Element signerRole = document.createElementNS(XADES_NS, "xades:SignerRole");
        signedSignatureProperties.appendChild(signerRole);

        Element claimedRoles = document.createElementNS(XADES_NS, "xades:ClaimedRoles");
        signerRole.appendChild(claimedRoles);

        Element claimedRole = document.createElementNS(XADES_NS, "xades:ClaimedRole");
        claimedRole.setTextContent("Şarj Ağı İşletmecisi");
        claimedRoles.appendChild(claimedRole);

        return qp;
    }

    private String base64Sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(md.digest(bytes));
    }

    private Element findChildElement(Element parent, String namespaceUri, String localName) {
        var nodes = parent.getElementsByTagNameNS(namespaceUri, localName);
        if (nodes == null || nodes.getLength() == 0) return null;
        return (Element) nodes.item(0);
    }

    private void validateConfig() {
        if (signing == null) {
            throw new IllegalArgumentException("signing config bos.");
        }
        if (isBlank(signing.pkcs11Library)) {
            throw new IllegalArgumentException("signing.pkcs11Library bos. Ornek: /usr/local/lib/libakisp11.dylib");
        }
        if (isBlank(signing.pinEnv)) {
            throw new IllegalArgumentException("signing.pinEnv bos. Ornek: MALI_MUHUR_PIN");
        }
    }

    private String signatureMethodFor(PrivateKey privateKey) {
        String algorithm = privateKey.getAlgorithm();
        if (algorithm == null) {
            return SignatureMethod.RSA_SHA256;
        }
        String normalized = algorithm.trim().toUpperCase();
        if (normalized.contains("EC") || normalized.contains("ECDSA")) {
            return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384";
        }
        if (normalized.contains("RSA")) {
            return SignatureMethod.RSA_SHA256;
        }
        throw new IllegalArgumentException("Desteklenmeyen private key algoritmasi: " + algorithm + ". RSA veya EC/ECDSA bekleniyordu.");
    }

    private Provider configurePkcs11Provider() throws Exception {
        int slotListIndex = signing.slotListIndex == null ? 0 : signing.slotListIndex;
        return Pkcs11Support.configureProvider("AKIS_EVEYS_XADES", signing.pkcs11Library, slotListIndex, "akis-pkcs11-xades-");
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
        /*
         * XMLDSig/XAdES imzası atıldıktan sonra XML'i pretty-print etmek imzayı bozabilir.
         * Çünkü doğrulayan taraf dosyayı tekrar parse ettiğinde pretty-print sırasında eklenen
         * whitespace text node'ları canonicalized belge digest'ini değiştirebilir.
         * Bu nedenle imzalı XML kesinlikle yeniden girintilenmeden yazılır.
         */
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
        transformer.transform(new DOMSource(document), new StreamResult(path.toFile()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
