package dev.eveys.gibesu.desktop;

import dev.eveys.gibesu.audit.AuditWriter;
import dev.eveys.gibesu.config.AppConfig;
import dev.eveys.gibesu.excel.ExcelReader;
import dev.eveys.gibesu.gib.GibClient;
import dev.eveys.gibesu.gib.ReportPackager;
import dev.eveys.gibesu.model.ReportAggregator;
import dev.eveys.gibesu.model.ReportData;
import dev.eveys.gibesu.sign.SignerFactory;
import dev.eveys.gibesu.verify.XmlSignatureVerifier;
import dev.eveys.gibesu.xml.EsuXmlBuilder;
import dev.eveys.gibesu.xml.XmlValidator;
import org.w3c.dom.Document;
import javax.xml.parsers.DocumentBuilderFactory;

import java.math.BigDecimal;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;

public class DesktopWorkflow {
    public GenerateResult generate(AppConfig config, Path excelPath, String period, Path outDir, boolean validate) throws Exception {
        Files.createDirectories(outDir);
        var rows = new ExcelReader().read(excelPath, config.excel);
        var summaries = new ReportAggregator().aggregate(rows);
        var yearMonth = YearMonth.parse(period);
        var data = new ReportData(
                config.company.vkn,
                config.company.unvan,
                config.company.epdkLisansNo,
                yearMonth,
                summaries
        );

        var builder = new EsuXmlBuilder(config.report.namespaceUri, config.report.decimalScale);
        Document xml = builder.build(data);
        Path unsignedXml = outDir.resolve("esu-rapor-" + period + "-unsigned.xml");
        try (var out = Files.newOutputStream(unsignedXml)) {
            builder.write(xml, out);
        }

        Path auditCsv = outDir.resolve("esu-rapor-" + period + "-audit.csv");
        Path auditSummary = outDir.resolve("esu-rapor-" + period + "-audit-summary.txt");
        var auditWriter = new AuditWriter(config.report.decimalScale);
        auditWriter.writeCsv(summaries, auditCsv);
        auditWriter.writeSummary(rows, summaries, yearMonth, auditSummary);

        if (validate) {
            Path xsdPath = resolveXsdPath(config);
            if (xsdPath != null) {
                new XmlValidator().validate(xml, xsdPath);
            }
        }

        BigDecimal totalKwh = summaries.stream().map(s -> s.totalKwh()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = summaries.stream().map(s -> s.totalAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String raporNo = readFirstText(xml, "http://earsiv.efatura.gov.tr", "raporNo");
        return new GenerateResult(rows.size(), summaries.size(), totalKwh, totalAmount, raporNo, unsignedXml, auditCsv, auditSummary);
    }

    public Path sign(AppConfig config, Path unsignedXml, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        Path signedXml = outDir.resolve(defaultSignedFileName(unsignedXml));
        return new SignerFactory().create(config.signing).sign(unsignedXml, signedXml);
    }

    public XmlSignatureVerifier.VerificationResult verify(Path signedXml) throws Exception {
        return new XmlSignatureVerifier().verify(signedXml);
    }

    public void validateXml(AppConfig config, Path xmlPath) throws Exception {
        Path xsdPath = resolveXsdPath(config);
        if (xsdPath == null) {
            return;
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(xmlPath.toFile());
        new XmlValidator().validate(document, xsdPath);
    }

    public ReportPackager.PackageResult packageSignedXml(Path signedXml, Path outDir) throws Exception {
        return new ReportPackager().packageSignedXml(signedXml, outDir);
    }

    public GibClient.SendResult send(AppConfig config, Path zipPath, Path outDir, boolean dryRun, String trustStore, String trustStorePassword) throws Exception {
        applyTrustStore(trustStore, trustStorePassword);
        return new GibClient(config.client, config.signing).sendZipPackage(zipPath, outDir, dryRun);
    }

    public GibClient.SendResult status(AppConfig config, String paketId, Path outDir, boolean dryRun, String trustStore, String trustStorePassword) throws Exception {
        applyTrustStore(trustStore, trustStorePassword);
        return new GibClient(config.client, config.signing).getBatchStatus(paketId, outDir, dryRun);
    }

    private static void applyTrustStore(String trustStore, String trustStorePassword) {
        if (trustStore != null && !trustStore.isBlank()) {
            System.setProperty("javax.net.ssl.trustStore", trustStore.trim());
        } else {
            System.clearProperty("javax.net.ssl.trustStore");
        }
        if (trustStorePassword != null && !trustStorePassword.isBlank()) {
            System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword.trim());
        } else {
            System.clearProperty("javax.net.ssl.trustStorePassword");
        }
    }

    private Path resolveXsdPath(AppConfig config) throws Exception {
        if (config != null && config.report != null && config.report.xsdPath != null && !config.report.xsdPath.isBlank()) {
            Path configured = Path.of(config.report.xsdPath);
            if (Files.exists(configured)) {
                return configured;
            }
        }

        Path projectLocal = Path.of("gib-esu-paket", "Esu_GIB_Paket_V3", "esuRapor.xsd");
        if (Files.exists(projectLocal)) {
            return projectLocal;
        }

        String resource = "/gib-esu-paket/Esu_GIB_Paket_V3/esuRapor.xsd";
        try (InputStream in = DesktopWorkflow.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path temp = Files.createTempFile("gib-esuRapor-", ".xsd");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp;
        }
    }

    private static String readFirstText(Document doc, String namespaceUri, String localName) {
        var nodes = doc.getElementsByTagNameNS(namespaceUri, localName);
        if (nodes == null || nodes.getLength() == 0) return "";
        var item = nodes.item(0);
        return item == null ? "" : item.getTextContent();
    }

    private static String defaultSignedFileName(Path unsignedXml) {
        String fileName = unsignedXml.getFileName() == null ? "signed.xml" : unsignedXml.getFileName().toString();
        if (fileName.endsWith("-unsigned.xml")) {
            return fileName.replace("-unsigned.xml", "-signed.xml");
        }
        if (fileName.endsWith(".xml")) {
            return fileName.substring(0, fileName.length() - 4) + "-signed.xml";
        }
        return fileName + "-signed.xml";
    }

    public record GenerateResult(
            int rowCount,
            int plateCount,
            BigDecimal totalKwh,
            BigDecimal totalAmount,
            String raporNo,
            Path unsignedXml,
            Path auditCsv,
            Path auditSummary
    ) {}
}
