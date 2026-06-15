package dev.eveys.gibesu.xml;

import dev.eveys.gibesu.model.PlateSummary;
import dev.eveys.gibesu.model.ReportData;
import dev.eveys.gibesu.util.DecimalUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * GIB EŞÜ raporu XML üreticisi.
 *
 * Electroop jar içindeki açık JAXB modelinden çıkarılan alan yapısı:
 * - root: earsiv:eArsivRaporu
 * - namespace: http://earsiv.efatura.gov.tr
 * - baslik: versiyon, mukellef, hazirlayan, raporNo, dönem/bölüm tarihleri, bolumNo
 * - esuRapor: UUID, plakaNo, hizmetMiktari[@unitCode="kw/h"], toplamTutar, paraBirimi
 *
 * Not: XSD dosyası projeye eklendiğinde bu çıktıyı mutlaka XSD ile doğrulayın.
 */
public class EsuXmlBuilder {
    public static final String DEFAULT_EARSIV_NAMESPACE = "http://earsiv.efatura.gov.tr";
    public static final String XSI_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    private final String namespaceUri;
    private final int decimalScale;

    public EsuXmlBuilder(String namespaceUri, int decimalScale) {
        this.namespaceUri = namespaceUri == null || namespaceUri.isBlank()
                ? DEFAULT_EARSIV_NAMESPACE
                : namespaceUri.trim();
        this.decimalScale = decimalScale;
    }

    public Document build(ReportData data) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().newDocument();

        Element root = doc.createElementNS(namespaceUri, "earsiv:eArsivRaporu");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:earsiv", namespaceUri);
        // GIB resmi EŞÜ örneklerinde XMLDSig namespace default namespace olarak tanımlı.
        // e-Arşiv elemanları earsiv: prefix ile kaldığı için bu mevcut veri elemanlarını etkilemez,
        // imza alt ağacının <Signature> biçiminde üretilmesine imkan verir.
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", XMLSignature.XMLNS);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI_NAMESPACE);
        root.setAttributeNS(XSI_NAMESPACE, "xsi:schemaLocation", namespaceUri + " EsuRapor.xsd");
        doc.appendChild(root);

        LocalDate firstDay = data.period().atDay(1);
        LocalDate lastDay = data.period().atEndOfMonth();
        String raporNo = UUID.randomUUID().toString();

        Element baslik = append(doc, root, "baslik");
        appendText(doc, baslik, "versiyon", "1.0");

        Element mukellef = append(doc, baslik, "mukellef");
        appendText(doc, mukellef, "vkn", data.vkn());

        Element hazirlayan = append(doc, baslik, "hazirlayan");
        appendText(doc, hazirlayan, "vkn", data.vkn());

        appendText(doc, baslik, "raporNo", raporNo);
        appendText(doc, baslik, "donemBaslangicTarihi", firstDay.toString());
        appendText(doc, baslik, "donemBitisTarihi", lastDay.toString());
        appendText(doc, baslik, "bolumBaslangicTarihi", firstDay.toString());
        appendText(doc, baslik, "bolumBitisTarihi", lastDay.toString());
        appendText(doc, baslik, "bolumNo", "1");

        for (PlateSummary s : data.summaries()) {
            Element esuRapor = append(doc, root, "esuRapor");
            appendText(doc, esuRapor, "UUID", UUID.randomUUID().toString());
            appendText(doc, esuRapor, "plakaNo", s.plate());

            Element hizmetMiktari = append(doc, esuRapor, "hizmetMiktari");
            hizmetMiktari.setAttribute("unitCode", "kw/h");
            hizmetMiktari.setTextContent(fmt(s.totalKwh()));

            appendText(doc, esuRapor, "toplamTutar", fmt(s.totalAmount()));
            appendText(doc, esuRapor, "paraBirimi", "TRY");
        }
        return doc;
    }

    public void write(Document doc, OutputStream outputStream) throws Exception {
        var transformerFactory = TransformerFactory.newInstance();
        try {
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException ignored) { }
        var transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(outputStream));
    }

    private Element append(Document doc, Element parent, String localName) {
        Element e = doc.createElementNS(namespaceUri, "earsiv:" + localName);
        parent.appendChild(e);
        return e;
    }

    private void appendText(Document doc, Element parent, String localName, String value) {
        Element e = append(doc, parent, localName);
        e.setTextContent(value == null ? "" : value);
    }

    private String fmt(BigDecimal value) {
        return DecimalUtils.scale(value, decimalScale).toPlainString();
    }
}
