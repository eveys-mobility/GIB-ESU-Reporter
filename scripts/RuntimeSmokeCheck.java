package dev.eveys.gibesu.desktop;

import dev.eveys.gibesu.config.AppConfig;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.zip.ZipFile;

/** Runs on the packaged runtime with synthetic data, no token and no network requests. */
public class RuntimeSmokeCheck {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(work);
        check(Security.getProvider("SunPKCS11") != null, "SunPKCS11 provider missing");
        Class.forName("dev.eveys.gibesu.desktop.DesktopApp", false, RuntimeSmokeCheck.class.getClassLoader());
        Class.forName("dev.eveys.gibesu.sign.Pkcs11XadesBesSigner");
        Class.forName("dev.eveys.gibesu.gib.SoapWsSecuritySigner");
        HttpClient.newHttpClient();

        Path out = DesktopPaths.defaultOutputDirectory();
        check(out.isAbsolute() && out.startsWith(Path.of(System.getProperty("user.home")).toAbsolutePath()),
                "Default output directory must be inside user.home");
        check(out.equals(DesktopPaths.resolveOutputDirectory("out-desktop")), "Relative output path mismatch");

        AppConfig config = new AppConfig();
        config.company.vkn = "1234567890";
        config.company.unvan = "Runtime Test";
        config.company.epdkLisansNo = "TEST";
        Path configFile = work.resolve("application.yml");
        config.save(configFile);
        config = AppConfig.load(configFile);

        Path excel = work.resolve("input.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Report");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Plaka");
            header.createCell(1).setCellValue("kWh");
            header.createCell(2).setCellValue("Tutar");
            for (int i = 1; i <= 2; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("34ABC123");
                row.createCell(1).setCellValue(10.5);
                row.createCell(2).setCellValue(75);
            }
            try (var stream = Files.newOutputStream(excel)) {
                workbook.write(stream);
            }
        }

        DesktopWorkflow workflow = new DesktopWorkflow();
        var generated = workflow.generate(config, excel, "2026-08", out, false);
        check(generated.rowCount() == 2 && generated.plateCount() == 1, "Excel aggregation failed");
        check(Files.size(generated.auditCsv()) > 0, "Audit CSV missing");
        check(Files.size(generated.auditSummary()) > 0, "Audit summary missing");

        var parser = DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        var document = parser.newDocumentBuilder().parse(generated.unsignedXml().toFile());
        var keys = KeyPairGenerator.getInstance("EC");
        keys.initialize(new ECGenParameterSpec("secp384r1"));
        var keyPair = keys.generateKeyPair();
        var signatures = XMLSignatureFactory.getInstance("DOM");
        var reference = signatures.newReference("", signatures.newDigestMethod(DigestMethod.SHA256, null),
                List.of(signatures.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
        var signedInfo = signatures.newSignedInfo(
                signatures.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                signatures.newSignatureMethod(SignatureMethod.ECDSA_SHA384, null), List.of(reference));
        var keyInfoFactory = signatures.getKeyInfoFactory();
        var keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newKeyValue(keyPair.getPublic())));
        var header = document.getElementsByTagNameNS(config.report.namespaceUri, "baslik").item(0);
        signatures.newXMLSignature(signedInfo, keyInfo).sign(new DOMSignContext(keyPair.getPrivate(), header));

        Path signed = out.resolve("runtime-signed.xml");
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(signed.toFile()));
        var savedDocument = parser.newDocumentBuilder().parse(signed.toFile());
        var signatureNode = savedDocument.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        var validation = new DOMValidateContext(keyPair.getPublic(), signatureNode);
        check(signatures.unmarshalXMLSignature(validation).validate(validation), "ECDSA XML signature invalid");
        workflow.validateXml(config, signed);

        var packaged = workflow.packageSignedXml(signed, out);
        try (ZipFile zip = new ZipFile(packaged.zipPath().toFile())) {
            check(zip.getEntry(generated.raporNo() + ".xml") != null, "Report missing from ZIP");
        }
        System.out.println("Runtime smoke check passed: config, Excel, output path, XML, ECDSA, XSD, ZIP, HTTP, PKCS11 provider.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
