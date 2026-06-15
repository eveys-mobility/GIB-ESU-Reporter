package dev.eveys.gibesu.cli;

import dev.eveys.gibesu.audit.AuditWriter;
import dev.eveys.gibesu.config.AppConfig;
import dev.eveys.gibesu.excel.ExcelReader;
import dev.eveys.gibesu.gib.GibClient;
import dev.eveys.gibesu.gib.ReportPackager;
import dev.eveys.gibesu.model.ReportAggregator;
import dev.eveys.gibesu.model.ReportData;
import dev.eveys.gibesu.sign.SignerFactory;
import dev.eveys.gibesu.xml.EsuXmlBuilder;
import dev.eveys.gibesu.xml.XmlValidator;
import dev.eveys.gibesu.verify.XmlSignatureVerifier;
import org.w3c.dom.Document;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.concurrent.Callable;

@Command(name = "eveys-gib-esu-reporter", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Excel dosyasindan GIB EŞÜ/e-Arşiv aylik rapor XML uretir; audit, XSD dogrulama, imza ve gonderim altyapisi sunar.",
        subcommands = {Main.GenerateCommand.class, Main.ValidateCommand.class, Main.VerifySignatureCommand.class, Main.SignCommand.class, Main.PackageCommand.class, Main.SendCommand.class, Main.StatusCommand.class})
public class Main implements Callable<Integer> {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "generate", description = "Excel dosyasini okuyup plaka bazli XML raporu olusturur.")
    static class GenerateCommand implements Callable<Integer> {
        @Option(names = "--config", required = true, description = "application.yml yolu")
        Path configPath;

        @Option(names = "--input", required = true, description = "Excel .xlsx dosyasi")
        Path input;

        @Option(names = "--period", required = true, description = "Rapor donemi, ornek: 2026-05")
        String period;

        @Option(names = "--out", defaultValue = "out", description = "Cikti klasoru")
        Path outDir;

        @Option(names = "--validate", description = "XML olustuktan sonra XSD dogrulama yap")
        boolean validate;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(configPath);
            ensureOutDir(outDir);

            var rows = new ExcelReader().read(input, config.excel);
            var summaries = new ReportAggregator().aggregate(rows);
            var data = new ReportData(
                    config.company.vkn,
                    config.company.unvan,
                    config.company.epdkLisansNo,
                    YearMonth.parse(period),
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
            auditWriter.writeSummary(rows, summaries, YearMonth.parse(period), auditSummary);

            if (validate) {
                new XmlValidator().validate(xml, Path.of(config.report.xsdPath));
            }

            String raporNo = readFirstText(xml, "http://earsiv.efatura.gov.tr", "raporNo");
            System.out.println("Okunan satir: " + rows.size());
            System.out.println("Plaka bazli kayit: " + summaries.size());
            if (!raporNo.isBlank()) {
                System.out.println("GIB raporNo / paketId: " + raporNo);
            }
            System.out.println("XML olusturuldu: " + unsignedXml.toAbsolutePath());
            System.out.println("Audit CSV olusturuldu: " + auditCsv.toAbsolutePath());
            System.out.println("Audit ozet olusturuldu: " + auditSummary.toAbsolutePath());
            if (!validate) {
                System.out.println("Not: XSD dogrulama yapilmadi. Canli gonderimden once --validate kullanin.");
            }
            return 0;
        }
    }

    @Command(name = "validate", description = "XML dosyasini XSD ile dogrular.")
    static class ValidateCommand implements Callable<Integer> {
        @Option(names = "--xml", required = true) Path xml;
        @Option(names = "--xsd", required = true) Path xsd;

        @Override
        public Integer call() throws Exception {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(xml.toFile());
            new XmlValidator().validate(doc, xsd);
            System.out.println("XML XSD dogrulamasi basarili: " + xml.toAbsolutePath());
            return 0;
        }
    }


    @Command(name = "verify-signature", aliases = {"verify"}, description = "Imzali XML icindeki XMLDSig/XAdES imzasini yerelde dogrular; Reference bazli sonucu basar.")
    static class VerifySignatureCommand implements Callable<Integer> {
        @Option(names = {"--input", "--xml"}, required = true, description = "Imzali XML dosyasi") Path signedXml;

        @Override
        public Integer call() throws Exception {
            var result = new XmlSignatureVerifier().verify(signedXml);
            System.out.println("XML imza dosyasi: " + signedXml.toAbsolutePath());
            System.out.println("Yerel dogrulama: " + (result.coreValid() ? "BASARILI" : "BASARISIZ"));
            System.out.println("Mesaj: " + result.message());
            if (result.signatureMethod() != null) System.out.println("SignatureMethod: " + result.signatureMethod());
            if (result.canonicalizationMethod() != null) System.out.println("CanonicalizationMethod: " + result.canonicalizationMethod());
            if (result.certificateSubject() != null) System.out.println("Sertifika subject: " + result.certificateSubject());
            if (result.certificateNotAfter() != null) System.out.println("Sertifika bitis: " + result.certificateNotAfter());
            System.out.println("Reference sonuclari:");
            for (var ref : result.references()) {
                System.out.println("- id=" + ref.id()
                        + " uri=" + ref.uri()
                        + " type=" + ref.type()
                        + " valid=" + ref.valid()
                        + " digest=" + ref.digestMethod()
                        + " transforms=" + ref.transforms());
            }
            return result.coreValid() ? 0 : 2;
        }
    }

    @Command(name = "sign", description = "XML imza katmanini calistirir. signing.mode=none ise dosyayi kopyalar; pkcs11-xml-dsig teknik test, pkcs11-xades-bes XAdES-BES yapili imza uretir.")
    static class SignCommand implements Callable<Integer> {
        @Option(names = "--config", required = true) Path configPath;
        @Option(names = {"--input", "--xml"}, required = true, description = "Imzalanacak unsigned XML dosyasi") Path unsignedXml;
        @Option(names = "--out", required = true, description = "Cikti klasoru veya signed XML dosya yolu") Path out;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(configPath);
            Path signedXml = resolveSignedOutput(unsignedXml, out);
            ensureOutDir(signedXml.getParent());
            var signer = new SignerFactory().create(config.signing);
            Path result = signer.sign(unsignedXml, signedXml);
            System.out.println("Imza cikti dosyasi: " + result.toAbsolutePath());
            return 0;
        }
    }


    @Command(name = "package", description = "Imzali XML'i GIB akisi icin raporNo.xml olarak zipler ve base64 dosyasi uretir.")
    static class PackageCommand implements Callable<Integer> {
        @Option(names = {"--input", "--signed-xml"}, required = true, description = "Imzali XML dosyasi") Path signedXml;
        @Option(names = "--out", required = true, description = "Cikti klasoru veya .zip dosya yolu") Path out;

        @Override
        public Integer call() throws Exception {
            var result = new ReportPackager().packageSignedXml(signedXml, out);
            System.out.println("GIB raporNo / paketId: " + result.raporNo());
            System.out.println("ZIP icindeki XML adi: " + result.xmlEntryName());
            System.out.println("ZIP dosyasi: " + result.zipPath().toAbsolutePath());
            System.out.println("ZIP boyutu: " + result.zipSizeBytes() + " bytes");
            System.out.println("Base64 dosyasi: " + result.base64Path().toAbsolutePath());
            System.out.println("Not: SOAP sendDocumentFile icin ZIP'in base64 icerigi kullanilacak.");
            return 0;
        }
    }

    @Command(name = "send", description = "ZIP paketini GIB sendDocumentFile SOAP servisine gonderir veya --dry-run ile SOAP istegini dosyaya yazar.")
    static class SendCommand implements Callable<Integer> {
        @Option(names = "--config", required = true, description = "application.yml yolu") Path configPath;
        @Option(names = "--zip", required = true, description = "package komutunun urettigi raporNo.zip dosyasi") Path zipPath;
        @Option(names = "--out", defaultValue = "out", description = "SOAP request/response kayit klasoru") Path outDir;
        @Option(names = "--dry-run", description = "GIB'e gondermeden SOAP request dosyasini uret") boolean dryRun;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(configPath);
            ensureOutDir(outDir);
            var result = new GibClient(config.client, config.signing).sendZipPackage(zipPath, outDir, dryRun);
            System.out.println("Endpoint: " + result.endpoint());
            System.out.println("Paket ID: " + result.paketId());
            System.out.println("SOAP request: " + result.requestPath().toAbsolutePath());
            if (result.responsePath() != null) {
                System.out.println("SOAP response: " + result.responsePath().toAbsolutePath());
            }
            if (dryRun) {
                System.out.println("Dry-run: GIB'e gonderim yapilmadi.");
            } else {
                System.out.println("HTTP status: " + result.httpStatus());
                System.out.println("GIB return: " + result.returnText());
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Paket durumunu getBatchStatus SOAP metodu ile sorgular. Not: GIB tarafinda SOAP imzasi istenirse sonraki surumde ayrica eklenecek.")
    static class StatusCommand implements Callable<Integer> {
        @Option(names = "--config", required = true, description = "application.yml yolu") Path configPath;
        @Option(names = {"--paket-id", "--rapor-no"}, required = true, description = "RaporNo / paketId") String paketId;
        @Option(names = "--out", defaultValue = "out", description = "SOAP request/response kayit klasoru") Path outDir;
        @Option(names = "--dry-run", description = "GIB'e gondermeden SOAP request dosyasini uret") boolean dryRun;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(configPath);
            ensureOutDir(outDir);
            var result = new GibClient(config.client, config.signing).getBatchStatus(paketId, outDir, dryRun);
            System.out.println("Endpoint: " + result.endpoint());
            System.out.println("Paket ID: " + result.paketId());
            System.out.println("SOAP request: " + result.requestPath().toAbsolutePath());
            if (result.responsePath() != null) {
                System.out.println("SOAP response: " + result.responsePath().toAbsolutePath());
            }
            if (dryRun) {
                System.out.println("Dry-run: GIB'e sorgu gonderilmedi.");
            } else {
                System.out.println("HTTP status: " + result.httpStatus());
                System.out.println("GIB return: " + result.returnText());
            }
            return 0;
        }
    }

    private static String readFirstText(Document doc, String namespaceUri, String localName) {
        var nodes = doc.getElementsByTagNameNS(namespaceUri, localName);
        if (nodes == null || nodes.getLength() == 0) return "";
        var item = nodes.item(0);
        return item == null ? "" : item.getTextContent();
    }


    private static Path resolveSignedOutput(Path unsignedXml, Path out) throws IOException {
        if (out == null) {
            return unsignedXml.resolveSibling(defaultSignedFileName(unsignedXml));
        }
        boolean looksLikeXmlFile = out.getFileName() != null && out.getFileName().toString().toLowerCase().endsWith(".xml");
        if (looksLikeXmlFile) {
            return out;
        }
        Files.createDirectories(out);
        return out.resolve(defaultSignedFileName(unsignedXml));
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

    private static void ensureOutDir(Path outDir) throws IOException {
        if (outDir != null) Files.createDirectories(outDir);
    }
}
