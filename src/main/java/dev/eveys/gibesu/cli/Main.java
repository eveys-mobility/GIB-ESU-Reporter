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
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "eveys-gib-esu-reporter", mixinStandardHelpOptions = true, versionProvider = Main.AppVersionProvider.class,
        description = "Excel dosyasindan GIB EŞÜ/e-Arşiv aylik rapor XML uretir; audit, XSD dogrulama, imza ve gonderim altyapisi sunar.",
        subcommands = {Main.GenerateCommand.class, Main.MonthlyCommand.class, Main.ValidateCommand.class, Main.VerifySignatureCommand.class, Main.SignCommand.class, Main.PackageCommand.class, Main.SendCommand.class, Main.StatusCommand.class})
public class Main implements Callable<Integer> {
    public static class AppVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"eveys-gib-esu-reporter " + projectVersion()};
        }

        private String projectVersion() {
            try (InputStream in = Main.class.getResourceAsStream("/META-INF/maven/dev.eveys/eveys-gib-esu-reporter/pom.properties")) {
                if (in == null) {
                    return "dev";
                }
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version", "dev");
            } catch (IOException e) {
                return "dev";
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Command(name = "monthly", aliases = {"run-monthly"}, mixinStandardHelpOptions = true, description = "Aylik Excel dosyasini bastan sona isler: generate, validate, sign, verify, package; --send ile GIB'e gonderir ve status sorgular.")
    static class MonthlyCommand implements Callable<Integer> {
        @Option(names = "--config", required = true, description = "application.yml yolu")
        Path configPath;

        @Option(names = "--input", required = true, description = "Aylik Excel .xlsx dosyasi")
        Path input;

        @Option(names = "--period", required = true, description = "Rapor donemi, ornek: 2026-05")
        String period;

        @Option(names = "--out", defaultValue = "out", description = "Cikti klasoru")
        Path outDir;

        @Option(names = "--send", description = "Paketi GIB endpoint'ine gonder ve ardindan status sorgula")
        boolean send;

        @Option(names = "--dry-run", description = "SOAP request dosyalarini uret, GIB'e gonderme")
        boolean dryRun;

        @Option(names = "--skip-validate", description = "XSD dogrulamasini atla")
        boolean skipValidate;

        @Option(names = "--no-status", description = "Gonderimden sonra status sorgusunu calistirma")
        boolean noStatus;

        @Option(names = "--status-retries", defaultValue = "3", description = "Status success30 donmezse toplam deneme sayisi")
        int statusRetries;

        @Option(names = "--status-wait-seconds", defaultValue = "10", description = "Status denemeleri arasinda beklenecek saniye")
        int statusWaitSeconds;

        @Option(names = "--confirm-prod", description = "Prod gonderim icin CANLI GONDER yazilmalidir")
        String confirmProd;

        @Override
        public Integer call() throws Exception {
            AppConfig config = AppConfig.load(configPath);
            ensureOutDir(outDir);
            ensurePinAvailable(config.signing);
            requireProdConfirmation(config, send, dryRun, confirmProd);

            YearMonth yearMonth = YearMonth.parse(period);
            System.out.println("Aylik akis basladi.");
            System.out.println("Ortam: " + environmentName(config));
            System.out.println("Donem: " + yearMonth);
            System.out.println("Excel: " + input.toAbsolutePath());
            System.out.println("Cikti klasoru: " + outDir.toAbsolutePath());

            var rows = new ExcelReader().read(input, config.excel);
            var summaries = new ReportAggregator().aggregate(rows);
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

            if (!skipValidate) {
                new XmlValidator().validate(xml, Path.of(config.report.xsdPath));
                System.out.println("XML XSD dogrulamasi basarili.");
            }

            String raporNo = readFirstText(xml, "http://earsiv.efatura.gov.tr", "raporNo");
            System.out.println("Okunan satir: " + rows.size());
            System.out.println("Plaka bazli kayit: " + summaries.size());
            System.out.println("GIB raporNo / paketId: " + raporNo);
            System.out.println("Unsigned XML: " + unsignedXml.toAbsolutePath());
            System.out.println("Audit CSV: " + auditCsv.toAbsolutePath());
            System.out.println("Audit ozet: " + auditSummary.toAbsolutePath());

            Path signedXml = outDir.resolve(defaultSignedFileName(unsignedXml));
            Path signedResult = new SignerFactory().create(config.signing).sign(unsignedXml, signedXml);
            System.out.println("Signed XML: " + signedResult.toAbsolutePath());

            var verification = new XmlSignatureVerifier().verify(signedResult);
            System.out.println("Yerel imza dogrulama: " + (verification.coreValid() ? "BASARILI" : "BASARISIZ"));
            System.out.println("Imza dogrulama mesaji: " + verification.message());
            if (!verification.coreValid()) {
                return 2;
            }

            var packageResult = new ReportPackager().packageSignedXml(signedResult, outDir);
            System.out.println("ZIP dosyasi: " + packageResult.zipPath().toAbsolutePath());
            System.out.println("ZIP icindeki XML adi: " + packageResult.xmlEntryName());
            System.out.println("Base64 dosyasi: " + packageResult.base64Path().toAbsolutePath());

            if (!send) {
                System.out.println("Gonderim yapilmadi. GIB'e gondermek icin komuta --send ekleyin.");
                return 0;
            }

            var client = new GibClient(config.client, config.signing);
            var sendResult = client.sendZipPackage(packageResult.zipPath(), outDir, dryRun);
            printSendResult("Send", sendResult, dryRun);
            if (!dryRun && !isHttpSuccess(sendResult.httpStatus())) {
                return 3;
            }

            if (!dryRun && !noStatus) {
                var statusResult = queryStatusWithRetry(client, packageResult.raporNo(), outDir, statusRetries, statusWaitSeconds);
                if (statusResult == null || !isHttpSuccess(statusResult.httpStatus()) || !isSuccess30(statusResult.returnText())) {
                    return 4;
                }
            }

            System.out.println("Aylik akis tamamlandi.");
            return 0;
        }
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

    private static void ensurePinAvailable(AppConfig.Signing signing) {
        String pinEnv = signing == null || signing.pinEnv == null || signing.pinEnv.isBlank()
                ? "MALI_MUHUR_PIN"
                : signing.pinEnv.trim();
        String pin = System.getProperty(pinEnv);
        if (pin == null || pin.isBlank()) {
            pin = System.getenv(pinEnv);
        }
        if (pin != null && !pin.isBlank()) {
            return;
        }

        Console console = System.console();
        if (console == null) {
            return;
        }
        char[] password = console.readPassword("%s: ", "Mali muhur PIN");
        if (password == null || password.length == 0) {
            return;
        }
        System.setProperty(pinEnv, new String(password));
        Arrays.fill(password, '\0');
    }

    private static void requireProdConfirmation(AppConfig config, boolean send, boolean dryRun, String confirmProd) {
        if (!send || dryRun || !isProd(config)) {
            return;
        }
        if (!"CANLI GONDER".equals(confirmProd)) {
            throw new IllegalArgumentException("Prod gonderim icin --confirm-prod \"CANLI GONDER\" parametresi gereklidir.");
        }
    }

    private static boolean isProd(AppConfig config) {
        return config != null
                && config.client != null
                && "prod".equalsIgnoreCase(config.client.environment);
    }

    private static String environmentName(AppConfig config) {
        if (config == null || config.client == null || config.client.environment == null || config.client.environment.isBlank()) {
            return "test";
        }
        return config.client.environment.trim();
    }

    private static boolean isHttpSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isSuccess30(String text) {
        return text != null && text.toLowerCase().contains("success30");
    }

    private static void printSendResult(String label, GibClient.SendResult result, boolean dryRun) {
        System.out.println(label + " endpoint: " + result.endpoint());
        System.out.println(label + " paket ID: " + result.paketId());
        System.out.println(label + " SOAP request: " + result.requestPath().toAbsolutePath());
        if (result.responsePath() != null) {
            System.out.println(label + " SOAP response: " + result.responsePath().toAbsolutePath());
        }
        if (dryRun) {
            System.out.println(label + " dry-run: GIB'e gonderim yapilmadi.");
        } else {
            System.out.println(label + " HTTP status: " + result.httpStatus());
            System.out.println(label + " GIB return: " + result.returnText());
        }
    }

    private static GibClient.SendResult queryStatusWithRetry(GibClient client, String paketId, Path outDir, int retries, int waitSeconds) throws Exception {
        int attempts = Math.max(1, retries);
        int wait = Math.max(0, waitSeconds);
        GibClient.SendResult lastResult = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1 && wait > 0) {
                System.out.println("Status tekrar denemesi icin bekleniyor: " + wait + " saniye");
                Thread.sleep(wait * 1000L);
            }
            System.out.println("Status denemesi: " + attempt + "/" + attempts);
            lastResult = client.getBatchStatus(paketId, outDir, false);
            printSendResult("Status", lastResult, false);
            if (isHttpSuccess(lastResult.httpStatus()) && isSuccess30(lastResult.returnText())) {
                return lastResult;
            }
        }
        return lastResult;
    }
}
