package dev.eveys.gibesu.gib;

import dev.eveys.gibesu.config.AppConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * GIB EArsivWsPort SOAP istemcisi.
 *
 * Bu sinif Electroop tarafinda gorulen sendDocumentFile / getBatchStatus
 * envelope yapisini temiz bir implementasyon olarak uretir. SSL dogrulamasi
 * kapatilmaz; prod/test endpoint config'ten gelir.
 */
public class GibClient {
    private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String WEB_NS = "http://earsiv.vedop3.ggm.gov.org/";

    private final AppConfig.Client config;
    private final AppConfig.Signing signing;

    public GibClient(AppConfig.Client config) {
        this(config, null);
    }

    public GibClient(AppConfig.Client config, AppConfig.Signing signing) {
        this.config = config == null ? new AppConfig.Client() : config;
        this.signing = signing == null ? new AppConfig.Signing() : signing;
    }

    public SendResult sendZipPackage(Path zipPath, Path outDir, boolean dryRun) throws Exception {
        if (!Files.exists(zipPath)) {
            throw new IllegalArgumentException("ZIP dosyasi bulunamadi: " + zipPath.toAbsolutePath());
        }
        String fileName = zipPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("--zip dosyasi .zip ile bitmeli: " + fileName);
        }
        String paketId = fileName.substring(0, fileName.length() - 4);
        String binaryData = Base64.getEncoder().encodeToString(Files.readAllBytes(zipPath));
        String soap = buildSendDocumentFileEnvelope(paketId, binaryData);
        String endpoint = endpoint();
        soap = new SoapWsSecuritySigner(signing).sign(soap);

        Files.createDirectories(outDir);
        Path requestPath = outDir.resolve(paketId + "-sendDocumentFile-request.xml");
        Files.writeString(requestPath, soap, StandardCharsets.UTF_8);

        if (dryRun) {
            return new SendResult(endpoint, paketId, requestPath, null, -1, "DRY_RUN");
        }

        HttpResponse<String> response = postSoap(endpoint, "urn:sendDocumentFile", soap);
        Path responsePath = outDir.resolve(paketId + "-sendDocumentFile-response.xml");
        Files.writeString(responsePath, response.body() == null ? "" : response.body(), StandardCharsets.UTF_8);
        String returnText = extractReturn(response.body(), "sendDocumentFileResponse");
        return new SendResult(endpoint, paketId, requestPath, responsePath, response.statusCode(), returnText);
    }

    public SendResult getBatchStatus(String paketId, Path outDir, boolean dryRun) throws Exception {
        if (paketId == null || paketId.isBlank()) {
            throw new IllegalArgumentException("paketId bos olamaz");
        }
        String safePaketId = paketId.trim();
        String soap = buildGetBatchStatusEnvelope(safePaketId);
        String endpoint = endpoint();
        soap = new SoapWsSecuritySigner(signing).sign(soap);

        Files.createDirectories(outDir);
        Path requestPath = outDir.resolve(safePaketId + "-getBatchStatus-request.xml");
        Files.writeString(requestPath, soap, StandardCharsets.UTF_8);

        if (dryRun) {
            return new SendResult(endpoint, safePaketId, requestPath, null, -1, "DRY_RUN");
        }

        HttpResponse<String> response = postSoap(endpoint, "urn:getBatchStatus", soap);
        Path responsePath = outDir.resolve(safePaketId + "-getBatchStatus-response.xml");
        Files.writeString(responsePath, response.body() == null ? "" : response.body(), StandardCharsets.UTF_8);
        String returnText = extractReturn(response.body(), "getBatchStatusResponse");
        return new SendResult(endpoint, safePaketId, requestPath, responsePath, response.statusCode(), returnText);
    }

    /** Backward-compatible placeholder used by early Main versions. */
    public String sendSignedReport(Path signedXml) {
        throw new UnsupportedOperationException("0.5.0 ile send komutu imzali XML degil ZIP paketi bekler: --zip out/<raporNo>.zip");
    }

    private String endpoint() {
        if ("prod".equalsIgnoreCase(config.environment)) {
            return notBlank(config.prodEndpoint, "prodEndpoint");
        }
        return notBlank(config.testEndpoint, "testEndpoint");
    }

    private String notBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("client." + name + " bos olamaz");
        }
        return value.trim();
    }

    private HttpResponse<String> postSoap(String endpoint, String soapAction, String soap) throws Exception {
        URI endpointUri = URI.create(endpoint);
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.connectTimeoutSeconds)));
        var sslContext = GibSslTrustStore.sslContextForEndpoint(endpointUri);
        if (sslContext != null) {
            clientBuilder.sslContext(sslContext);
        }
        HttpClient client = clientBuilder.build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpointUri)
                .timeout(Duration.ofSeconds(Math.max(1, config.readTimeoutSeconds)))
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(soap, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static String buildSendDocumentFileEnvelope(String paketId, String binaryData) {
        String fileName = escapeXml(paketId) + ".zip";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\" xmlns:web=\"" + WEB_NS + "\">" +
                "<soap:Header/>" +
                "<soap:Body>" +
                "<web:sendDocumentFile>" +
                "<Attachment>" +
                "<fileName>" + fileName + "</fileName>" +
                "<binaryData>" + binaryData + "</binaryData>" +
                "</Attachment>" +
                "</web:sendDocumentFile>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }

    public static String buildGetBatchStatusEnvelope(String paketId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\" xmlns:ear=\"" + WEB_NS + "\">" +
                "<soap:Header/>" +
                "<soap:Body>" +
                "<ear:getBatchStatus>" +
                "<paketId>" + escapeXml(paketId) + "</paketId>" +
                "</ear:getBatchStatus>" +
                "</soap:Body>" +
                "</soap:Envelope>";
    }

    private static String extractReturn(String responseXml, String responseLocalName) {
        if (responseXml == null || responseXml.isBlank()) {
            return "";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(responseXml)));
            String fault = extractSoapFault(doc);
            if (!fault.isBlank()) {
                return fault;
            }
            NodeList responses = doc.getElementsByTagNameNS(WEB_NS, responseLocalName);
            if (responses.getLength() == 0) {
                // Namespace farki olursa localName ile yedek ara.
                responses = doc.getElementsByTagName(responseLocalName);
            }
            if (responses.getLength() == 0) {
                return "Response element bulunamadi: " + responseLocalName;
            }
            Node response = responses.item(0);
            NodeList children = response.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if ("return".equals(child.getLocalName()) || "return".equals(child.getNodeName())) {
                    return child.getTextContent() == null ? "" : child.getTextContent().trim();
                }
            }
            return "Return element bulunamadi";
        } catch (Exception e) {
            return "Response parse edilemedi: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private static String extractSoapFault(Document doc) {
        NodeList faults = doc.getElementsByTagNameNS(SOAP_NS, "Fault");
        if (faults.getLength() == 0) {
            faults = doc.getElementsByTagName("Fault");
        }
        if (faults.getLength() == 0) {
            return "";
        }

        Node fault = faults.item(0);
        String reason = firstDescendantText(fault, "Reason", "Text");
        String detailCode = firstDescendantText(fault, "EArsivFault", "code");
        String detailMessage = firstDescendantText(fault, "EArsivFault", "message");
        if (detailMessage.isBlank()) {
            detailMessage = reason;
        }
        if (detailCode.isBlank()) {
            return "SOAP Fault: " + detailMessage;
        }
        return "SOAP Fault " + detailCode + ": " + detailMessage;
    }

    private static String firstDescendantText(Node root, String parentLocalName, String childLocalName) {
        if (root == null) {
            return "";
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (localNameEquals(n, parentLocalName)) {
                String directChild = firstDirectChildText(n, childLocalName);
                if (!directChild.isBlank()) {
                    return directChild;
                }
            }
            String nested = firstDescendantText(n, parentLocalName, childLocalName);
            if (!nested.isBlank()) {
                return nested;
            }
        }
        return "";
    }

    private static String firstDirectChildText(Node root, String childLocalName) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (localNameEquals(n, childLocalName)) {
                return n.getTextContent() == null ? "" : n.getTextContent().trim();
            }
        }
        return "";
    }

    private static boolean localNameEquals(Node node, String expected) {
        if (node == null || expected == null) {
            return false;
        }
        String localName = node.getLocalName();
        return expected.equals(localName) || expected.equals(node.getNodeName());
    }

    private static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public record SendResult(
            String endpoint,
            String paketId,
            Path requestPath,
            Path responsePath,
            int httpStatus,
            String returnText
    ) {}
}
