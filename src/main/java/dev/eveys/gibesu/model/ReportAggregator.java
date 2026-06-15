package dev.eveys.gibesu.model;

import java.math.BigDecimal;
import java.util.*;

public class ReportAggregator {
    public List<PlateSummary> aggregate(List<ChargingRow> rows) {
        Map<String, MutableSummary> grouped = new TreeMap<>();
        for (ChargingRow row : rows) {
            MutableSummary summary = grouped.computeIfAbsent(row.plate(), k -> new MutableSummary());
            summary.totalKwh = summary.totalKwh.add(row.kwh());
            summary.totalAmount = summary.totalAmount.add(row.amount());
            summary.sessionCount++;
        }
        List<PlateSummary> result = new ArrayList<>();
        grouped.forEach((plate, s) -> result.add(new PlateSummary(plate, s.totalKwh, s.totalAmount, s.sessionCount)));
        return result;
    }

    private static class MutableSummary {
        BigDecimal totalKwh = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int sessionCount = 0;
    }
}
