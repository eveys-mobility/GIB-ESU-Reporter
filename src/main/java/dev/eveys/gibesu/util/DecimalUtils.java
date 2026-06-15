package dev.eveys.gibesu.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DecimalUtils {
    private DecimalUtils() {}

    public static BigDecimal scale(BigDecimal value, int scale) {
        if (value == null) return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
