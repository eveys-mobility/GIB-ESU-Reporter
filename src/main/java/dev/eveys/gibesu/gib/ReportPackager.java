package dev.eveys.gibesu.gib;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReportPackager {
    private static final String EARSIV_NS = "http://earsiv.efatura.gov.tr";

    public PackageResult packageSignedXml(Path signedXml, Path outDirOrZip) throws Exception {
        String raporNo = readRaporNo(signedXml);
        if (raporNo == null || raporNo.isBlank()) {
            throw new IllegalArgumentException("Imzali XML icinde earsiv:raporNo bulunamadi: " + signedXml);
        }

        Path zipPath = resolveZipPath(raporNo, outDirOrZip);
        Files.createDirectories(zipPath.toAbsolutePath().getParent());
        String xmlEntryName = raporNo + ".xml";

        try (OutputStream out = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry(xmlEntryName);
            zip.putNextEntry(entry);
            Files.copy(signedXml, zip);
            zip.closeEntry();
        }

        Path base64Path = zipPath.resolveSibling(zipPath.getFileName().toString() + ".base64.txt");
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(zipPath));
        Files.writeString(base64Path, base64);

        return new PackageResult(raporNo, xmlEntryName, zipPath, base64Path, Files.size(zipPath));
    }

    private Path resolveZipPath(String raporNo, Path outDirOrZip) throws IOException {
        if (outDirOrZip == null) return Path.of(raporNo + ".zip");
        String name = outDirOrZip.getFileName() == null ? "" : outDirOrZip.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) return outDirOrZip;
        Files.createDirectories(outDirOrZip);
        return outDirOrZip.resolve(raporNo + ".zip");
    }

    private String readRaporNo(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = factory.newDocumentBuilder().parse(xml.toFile());
        var nodes = doc.getElementsByTagNameNS(EARSIV_NS, "raporNo");
        if (nodes == null || nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent();
    }

    public record PackageResult(String raporNo, String xmlEntryName, Path zipPath, Path base64Path, long zipSizeBytes) {}
}
