package dev.eveys.gibesu.util;

import java.text.Normalizer;
import java.util.Locale;

public final class PlateNormalizer {
    private PlateNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        s = s.replace('İ', 'I');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^A-Z0-9]", "");
        return s;
    }

    public static boolean looksLikeTurkishPlate(String normalizedPlate) {
        if (normalizedPlate == null) return false;
        // TR plaka icin gevsek kontrol: 2 hane il kodu + harf/rakam devam.
        return normalizedPlate.matches("^[0-9]{2}[A-Z0-9]{3,8}$");
    }
}
