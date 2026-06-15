package dev.eveys.gibesu.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ChargingRow(
        String plate,
        BigDecimal kwh,
        BigDecimal amount,
        int sourceRowNumber
) {
    public ChargingRow {
        Objects.requireNonNull(plate, "plate");
        Objects.requireNonNull(kwh, "kwh");
        Objects.requireNonNull(amount, "amount");
    }
}
