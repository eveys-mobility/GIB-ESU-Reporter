package dev.eveys.gibesu.xml;

import org.w3c.dom.Document;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GİB EŞÜ XSD doğrulayıcı.
 *
 * esuRapor.xsd; XAdES.xsd, XAdESv141.xsd ve xmldsig-core-schema.xsd
 * dosyalarını import eder. Java güvenlik ayarları dış schema erişimini
 * kapattığında bu importlar file access hatasına düşebilir. Bu nedenle
 * importlar classpath içindeki gömülü schema dosyalarından çözülür.
 */
public class XmlValidator {
    private static final String SCHEMA_RESOURCE_BASE = "/gib-esu-paket/Esu_GIB_Paket_V3/";

    public void validate(Document document, Path xsdPath) throws Exception {
        if (xsdPath == null || !Files.exists(xsdPath)) {
            throw new IllegalArgumentException("XSD dosyasi bulunamadi: " + xsdPath);
        }

        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver(new ClasspathSchemaResolver());

        var schema = factory.newSchema(new StreamSource(xsdPath.toFile()));
        var validator = schema.newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.setResourceResolver(new ClasspathSchemaResolver());
        validator.validate(new DOMSource(document));
    }

    private static class ClasspathSchemaResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            if (systemId == null || systemId.isBlank()) {
                return null;
            }
            String fileName = Path.of(systemId).getFileName().toString();
            InputStream in = XmlValidator.class.getResourceAsStream(SCHEMA_RESOURCE_BASE + fileName);
            if (in == null) {
                return null;
            }
            return new SimpleLSInput(publicId, systemId, in);
        }
    }

    private static class SimpleLSInput implements LSInput {
        private String publicId;
        private String systemId;
        private InputStream byteStream;

        SimpleLSInput(String publicId, String systemId, InputStream byteStream) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.byteStream = byteStream;
        }

        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) {}
        @Override public InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(InputStream byteStream) { this.byteStream = byteStream; }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) {}
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { this.systemId = systemId; }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) { this.publicId = publicId; }
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String baseURI) {}
        @Override public String getEncoding() { return "UTF-8"; }
        @Override public void setEncoding(String encoding) {}
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean certifiedText) {}
    }
}
