package dev.eveys.gibesu.model;

import java.math.BigDecimal;

public record PlateSummary(
        String plate,
        BigDecimal totalKwh,
        BigDecimal totalAmount,
        int sessionCount
) { }
