package dev.eveys.gibesu.gib;

import dev.eveys.gibesu.config.AppConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * GIB okcesu endpoint'i SOAP Header icinde WS-Security bekler.
 * Bu sinif request'in SOAP Body ve Timestamp kisimlarini AKIS/PKCS#11 mali muhur
 * sertifikasi ile imzalayarak wsse:Security header'i uretir.
 */
public class SoapWsSecuritySigner {
    private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    private static final String WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String BASE64_ENCODING = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";
    private static final String X509_V3 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3";

    private final AppConfig.Signing signing;

    public SoapWsSecuritySigner(AppConfig.Signing signing) {
        this.signing = signing == null ? new AppConfig.Signing() : signing;
    }

    public String sign(String soapXml) throws Exception {
        if (isBlank(signing.pkcs11Library)) {
            throw new IllegalArgumentException("WS-Security icin signing.pkcs11Library bos olamaz.");
        }
        if (isBlank(signing.pinEnv)) {
            throw new IllegalArgumentException("WS-Security icin signing.pinEnv bos olamaz. Ornek: MALI_MUHUR_PIN");
        }
        String pin = System.getProperty(signing.pinEnv);
        if (pin == null || pin.isBlank()) {
            pin = System.getenv(signing.pinEnv);
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN env bulunamadi: " + signing.pinEnv + ". Terminalde read -s " + signing.pinEnv + " ile girip export edin; PIN'i chate yazmayin.");
        }

        TokenMaterial token = loadTokenMaterial(pin);
        Document document = readXml(soapXml);
        addWsSecurityAndSign(document, token.privateKey(), token.certificate(), token.provider());
        return writeXml(document);
    }

    private void addWsSecurityAndSign(Document document, PrivateKey privateKey, X509Certificate cert, Provider provider) throws Exception {
        Element envelope = document.getDocumentElement();
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsse", WSSE_NS);
        envelope.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:wsu", WSU_NS);

        Element header = firstChildElementNS(envelope, SOAP_NS, "Header");
        if (header == null) {
            header = document.createElementNS(SOAP_NS, "soap:Header");
            Element body = firstChildElementNS(envelope, SOAP_NS, "Body");
            if (body == null) {
                envelope.insertBefore(header, envelope.getFirstChild());
            } else {
                envelope.insertBefore(header, body);
            }
        }
        // Bos Header varsa temizle; Security child eklenecek.
        Element security = document.createElementNS(WSSE_NS, "wsse:Security");
        security.setAttributeNS(SOAP_NS, "soap:mustUnderstand", "false");
        header.appendChild(security);

        String uid = UUID.randomUUID().toString().replace("-", "");
        String bodyId = "id-" + uid;
        String tsId = "TS-" + uid;
        String bstId = "X509-" + uid;

        Element timestamp = document.createElementNS(WSU_NS, "wsu:Timestamp");
        timestamp.setAttributeNS(WSU_NS, "wsu:Id", tsId);
        Element created = document.createElementNS(WSU_NS, "wsu:Created");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        created.setTextContent(now.toString());
        Element expires = document.createElementNS(WSU_NS, "wsu:Expires");
        expires.setTextContent(now.plus(5, ChronoUnit.MINUTES).toString());
        timestamp.appendChild(created);
        timestamp.appendChild(expires);
        security.appendChild(timestamp);

        Element bst = document.createElementNS(WSSE_NS, "wsse:BinarySecurityToken");
        bst.setAttributeNS(WSU_NS, "wsu:Id", bstId);
        bst.setAttribute("EncodingType", BASE64_ENCODING);
        bst.setAttribute("ValueType", X509_V3);
        bst.setTextContent(Base64.getEncoder().encodeToString(cert.getEncoded()));
        security.appendChild(bst);

        Element body = firstChildElementNS(envelope, SOAP_NS, "Body");
        if (body == null) {
            throw new IllegalStateException("SOAP Body bulunamadi");
        }
        body.setAttributeNS(WSU_NS, "wsu:Id", bodyId);

        body.setIdAttributeNS(WSU_NS, "Id", true);
        timestamp.setIdAttributeNS(WSU_NS, "Id", true);
        bst.setIdAttributeNS(WSU_NS, "Id", true);

        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        DigestMethod digestMethod = fac.newDigestMethod(DigestMethod.SHA256, null);
        CanonicalizationMethod excC14n = fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null);
        Transform excTransform = fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null);

        Reference bodyRef = fac.newReference("#" + bodyId, digestMethod, List.of(excTransform), null, "Reference-Body-" + uid);
        Reference tsRef = fac.newReference("#" + tsId, digestMethod, List.of(excTransform), null, "Reference-Timestamp-" + uid);
        String signatureMethodUri = signatureMethodFor(privateKey);
        SignedInfo signedInfo = fac.newSignedInfo(excC14n, fac.newSignatureMethod(signatureMethodUri, null), List.of(bodyRef, tsRef));

        KeyInfo keyInfo = securityTokenReferenceKeyInfo(document, fac.getKeyInfoFactory(), bstId);
        DOMSignContext signContext = new DOMSignContext(privateKey, security);
        signContext.setDefaultNamespacePrefix("ds");
        signContext.setProperty("org.jcp.xml.dsig.internal.dom.SignatureProvider", provider);
        fac.newXMLSignature(signedInfo, keyInfo, null, "Signature-" + uid, null).sign(signContext);

        System.out.println("WS-Security SOAP imzasi eklendi.");
        System.out.println("SOAP SignatureMethod: " + signatureMethodUri);
        System.out.println("SOAP sertifika subject: " + cert.getSubjectX500Principal().getName());
    }

    private KeyInfo securityTokenReferenceKeyInfo(Document document, KeyInfoFactory keyInfoFactory, String bstId) {
        Element str = document.createElementNS(WSSE_NS, "wsse:SecurityTokenReference");
        Element ref = document.createElementNS(WSSE_NS, "wsse:Reference");
        ref.setAttribute("URI", "#" + bstId);
        ref.setAttribute("ValueType", X509_V3);
        str.appendChild(ref);
        return keyInfoFactory.newKeyInfo(List.of(new DOMStructure(str)));
    }

    private TokenMaterial loadTokenMaterial(String pin) throws Exception {
        Provider provider = configurePkcs11Provider();
        KeyStore keyStore = KeyStore.getInstance("PKCS11", provider);
        keyStore.load(null, pin.toCharArray());
        String alias = resolveAlias(keyStore);
        Key key = keyStore.getKey(alias, null);
        if (!(key instanceof PrivateKey privateKey)) {
            throw new IllegalStateException("Alias private key degil: " + alias);
        }
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
        if (cert == null) {
            throw new IllegalStateException("Alias icin sertifika bulunamadi: " + alias);
        }
        System.out.println("SOAP WS-Security sertifika alias: " + alias);
        return new TokenMaterial(provider, privateKey, cert);
    }

    private Provider configurePkcs11Provider() throws Exception {
        int slotListIndex = signing.slotListIndex == null ? 0 : signing.slotListIndex;
        String providerName = "AKIS_EVEYS_WSS";
        String cfg = "name=" + providerName + "\n" +
                "library=" + signing.pkcs11Library + "\n" +
                "slotListIndex=" + slotListIndex + "\n";
        Path cfgPath = Files.createTempFile("akis-pkcs11-wss-", ".cfg");
        Files.writeString(cfgPath, cfg);
        Provider baseProvider = Security.getProvider("SunPKCS11");
        if (baseProvider == null) {
            throw new IllegalStateException("SunPKCS11 provider bulunamadi. JDK jdk.crypto.cryptoki modulunu icermeli.");
        }
        Provider configured = baseProvider.configure(cfgPath.toString());
        Security.addProvider(configured);
        return configured;
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

    private String signatureMethodFor(PrivateKey privateKey) {
        String algorithm = privateKey.getAlgorithm();
        if (algorithm == null) return SignatureMethod.RSA_SHA256;
        String normalized = algorithm.trim().toUpperCase();
        if (normalized.contains("EC") || normalized.contains("ECDSA")) {
            // Electroop/WSS4J akisi EC mali muhurlerde ECDSA-SHA384 kullaniyor.
            return "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384";
        }
        if (normalized.contains("RSA")) {
            return SignatureMethod.RSA_SHA256;
        }
        throw new IllegalArgumentException("Desteklenmeyen private key algoritmasi: " + algorithm);
    }

    private Document readXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private String writeXml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private Element firstChildElementNS(Element parent, String namespace, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && namespace.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record TokenMaterial(Provider provider, PrivateKey privateKey, X509Certificate certificate) {}
}
