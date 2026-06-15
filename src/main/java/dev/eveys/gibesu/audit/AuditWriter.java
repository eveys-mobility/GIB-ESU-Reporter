package dev.eveys.gibesu.audit;

import dev.eveys.gibesu.model.ChargingRow;
import dev.eveys.gibesu.model.PlateSummary;
import dev.eveys.gibesu.util.DecimalUtils;
import dev.eveys.gibesu.util.PlateNormalizer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AuditWriter {
    private final int decimalScale;

    public AuditWriter(int decimalScale) {
        this.decimalScale = decimalScale;
    }

    public void writeCsv(List<PlateSummary> summaries, Path output) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("plaka;toplam_kwh;toplam_tutar;islem_sayisi;plaka_kontrolu");
            writer.newLine();
            for (PlateSummary s : summaries.stream().sorted(Comparator.comparing(PlateSummary::plate)).toList()) {
                writer.write(csv(s.plate()));
                writer.write(';');
                writer.write(DecimalUtils.scale(s.totalKwh(), decimalScale).toPlainString());
                writer.write(';');
                writer.write(DecimalUtils.scale(s.totalAmount(), decimalScale).toPlainString());
                writer.write(';');
                writer.write(Integer.toString(s.sessionCount()));
                writer.write(';');
                writer.write(plateStatus(s.plate()));
                writer.newLine();
            }
        }
    }

    public void writeSummary(List<ChargingRow> rows, List<PlateSummary> summaries, YearMonth period, Path output) throws IOException {
        BigDecimal totalKwh = summaries.stream().map(PlateSummary::totalKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = summaries.stream().map(PlateSummary::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long suspiciousPlateCount = summaries.stream().filter(s -> !PlateNormalizer.looksLikeTurkishPlate(s.plate())).count();
        long multiSessionPlateCount = summaries.stream().filter(s -> s.sessionCount() > 1).count();
        int maxSessionCount = summaries.stream().mapToInt(PlateSummary::sessionCount).max().orElse(0);

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("Eveys GIB ESU Rapor Audit Ozeti");
            writer.newLine();
            writer.write("Donem: " + period);
            writer.newLine();
            writer.write("Okunan satir: " + rows.size());
            writer.newLine();
            writer.write("Plaka bazli kayit: " + summaries.size());
            writer.newLine();
            writer.write("Birden fazla islem iceren plaka sayisi: " + multiSessionPlateCount);
            writer.newLine();
            writer.write("Maksimum islem sayisi / plaka: " + maxSessionCount);
            writer.newLine();
            writer.write("Supheli/yabanci plaka format sayisi: " + suspiciousPlateCount);
            writer.newLine();
            writer.write("Toplam kWh: " + DecimalUtils.scale(totalKwh, decimalScale).toPlainString());
            writer.newLine();
            writer.write("Toplam tutar: " + DecimalUtils.scale(totalAmount, decimalScale).toPlainString());
            writer.newLine();
            writer.newLine();
            writer.write("Not: Plaka kontrolu gevsek TR format kontroludur. GIB XSD geldiginde kesin format kurallari ayrica uygulanmalidir.");
            writer.newLine();
        }
    }

    private String plateStatus(String plate) {
        if (plate == null || plate.isBlank()) return "BOS";
        return PlateNormalizer.looksLikeTurkishPlate(plate) ? "TR_FORMAT_OK" : "SUPHELI_VEYA_YABANCI";
    }

    private String csv(String value) {
        if (value == null) return "";
        String cleaned = value.replace("\r", " ").replace("\n", " ");
        if (cleaned.contains(";") || cleaned.contains("\"") || cleaned.contains(",")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }
}
