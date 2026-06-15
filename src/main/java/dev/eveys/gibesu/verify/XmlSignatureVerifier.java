package dev.eveys.gibesu.verify;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Yerel XMLDSig/XAdES imza dogrulama yardimcisi.
 * GIB 154 hatasinda sorunun dosyanin kendi imzasinda mi yoksa GIB profil/ESYA uyumunda mi
 * oldugunu ayirmak icin kullanilir.
 */
public class XmlSignatureVerifier {
    private static final String DS_NS = XMLSignature.XMLNS;

    public VerificationResult verify(Path signedXml) throws Exception {
        Document document = readXml(signedXml);
        markIdAttributes(document.getDocumentElement());

        NodeList signatures = document.getElementsByTagNameNS(DS_NS, "Signature");
        if (signatures == null || signatures.getLength() == 0) {
            return new VerificationResult(false, "Signature elementi bulunamadi.", List.of(), null, null, null, null);
        }

        Element signatureElement = (Element) signatures.item(0);
        DOMValidateContext context = new DOMValidateContext(new X509KeySelector(), signatureElement);
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        markIdAttributesInContext(document.getDocumentElement(), context);

        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        XMLSignature signature = factory.unmarshalXMLSignature(context);

        boolean coreValid = signature.validate(context);
        boolean signatureValueValid = signature.getSignatureValue().validate(context);

        List<ReferenceResult> referenceResults = new ArrayList<>();
        for (Object refObj : signature.getSignedInfo().getReferences()) {
            Reference ref = (Reference) refObj;
            boolean valid = ref.validate(context);
            referenceResults.add(new ReferenceResult(
                    ref.getId(),
                    ref.getURI(),
                    ref.getType(),
                    digestAlgorithm(ref),
                    transformAlgorithms(ref),
                    valid
            ));
        }

        String signatureMethod = signature.getSignedInfo().getSignatureMethod() == null ? null : signature.getSignedInfo().getSignatureMethod().getAlgorithm();
        String canonicalizationMethod = signature.getSignedInfo().getCanonicalizationMethod() == null ? null : signature.getSignedInfo().getCanonicalizationMethod().getAlgorithm();
        X509Certificate cert = extractCertificate(signature.getKeyInfo());
        String certSubject = cert == null ? null : cert.getSubjectX500Principal().getName();
        String certNotAfter = cert == null ? null : cert.getNotAfter().toString();

        String message = coreValid ? "XMLDSig yerel dogrulama BASARILI." : "XMLDSig yerel dogrulama BASARISIZ.";
        if (!signatureValueValid) {
            message += " SignatureValue dogrulanamadi.";
        }
        return new VerificationResult(coreValid, message, referenceResults, signatureMethod, canonicalizationMethod, certSubject, certNotAfter);
    }

    private String digestAlgorithm(Reference ref) {
        return ref.getDigestMethod() == null ? null : ref.getDigestMethod().getAlgorithm();
    }

    private List<String> transformAlgorithms(Reference ref) {
        List<String> algorithms = new ArrayList<>();
        if (ref.getTransforms() != null) {
            for (Object transformObj : ref.getTransforms()) {
                AlgorithmMethod method = (AlgorithmMethod) transformObj;
                algorithms.add(method.getAlgorithm());
            }
        }
        return algorithms;
    }

    private Document readXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private void markIdAttributes(Element element) {
        if (element == null) return;
        for (String attr : List.of("Id", "ID", "id")) {
            if (element.hasAttribute(attr)) {
                try {
                    element.setIdAttribute(attr, true);
                } catch (Exception ignored) {
                    // Element DOM tarafinda ID olarak isaretlenemiyorsa devam ediyoruz.
                }
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                markIdAttributes(childElement);
            }
        }
    }

    private void markIdAttributesInContext(Element element, DOMValidateContext context) {
        if (element == null) return;
        for (String attr : List.of("Id", "ID", "id")) {
            if (element.hasAttribute(attr)) {
                try {
                    context.setIdAttributeNS(element, null, attr);
                } catch (Exception ignored) {
                    // Devam.
                }
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                markIdAttributesInContext(childElement, context);
            }
        }
    }

    private X509Certificate extractCertificate(KeyInfo keyInfo) {
        if (keyInfo == null) return null;
        for (Object contentObj : keyInfo.getContent()) {
            XMLStructure structure = (XMLStructure) contentObj;
            if (structure instanceof X509Data x509Data) {
                for (Object data : x509Data.getContent()) {
                    if (data instanceof X509Certificate certificate) {
                        return certificate;
                    }
                }
            }
        }
        return null;
    }

    static class X509KeySelector extends KeySelector {
        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method, XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo == null) {
                throw new KeySelectorException("KeyInfo bulunamadi.");
            }
            for (Object contentObj : keyInfo.getContent()) {
                XMLStructure structure = (XMLStructure) contentObj;
                if (structure instanceof X509Data x509Data) {
                    for (Object data : x509Data.getContent()) {
                        if (data instanceof X509Certificate certificate) {
                            PublicKey publicKey = certificate.getPublicKey();
                            return () -> publicKey;
                        }
                    }
                }
            }
            throw new KeySelectorException("KeyInfo icinde X509Certificate bulunamadi.");
        }
    }

    public record VerificationResult(
            boolean coreValid,
            String message,
            List<ReferenceResult> references,
            String signatureMethod,
            String canonicalizationMethod,
            String certificateSubject,
            String certificateNotAfter
    ) {}

    public record ReferenceResult(
            String id,
            String uri,
            String type,
            String digestMethod,
            List<String> transforms,
            boolean valid
    ) {}
}
