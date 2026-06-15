package dev.eveys.gibesu.excel;

import dev.eveys.gibesu.config.AppConfig;
import dev.eveys.gibesu.model.ChargingRow;
import dev.eveys.gibesu.util.PlateNormalizer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ExcelReader {
    private static final List<String> PLATE_ALIASES = List.of("plaka", "plakano", "plaka no", "plate", "vehicle plate", "arac plaka", "araç plaka");
    private static final List<String> KWH_ALIASES = List.of("kwh", "kw/h", "enerji", "tuketim", "tüketim", "energy", "toplam kwh", "şarj miktarı", "sarj miktari");
    private static final List<String> AMOUNT_ALIASES = List.of("tl", "tutar", "toplam tutar", "gelir", "amount", "price", "ücret", "ucret", "toplam gelir");

    public List<ChargingRow> read(Path input, AppConfig.Excel config) throws IOException {
        try (var in = Files.newInputStream(input); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = selectSheet(workbook, config.sheetName);
            int headerIndex = Math.max(config.headerRow, 1) - 1;
            Row header = sheet.getRow(headerIndex);
            if (header == null) {
                throw new IllegalArgumentException("Excel header row bulunamadi: " + config.headerRow);
            }

            Map<String, Integer> headerMap = indexHeaders(header);
            int plateCol = resolveColumn(headerMap, config.plateColumn, PLATE_ALIASES, "plaka");
            int kwhCol = resolveColumn(headerMap, config.kwhColumn, KWH_ALIASES, "kWh");
            int amountCol = resolveColumn(headerMap, config.amountColumn, AMOUNT_ALIASES, "tutar");

            List<ChargingRow> rows = new ArrayList<>();
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("tr-TR"));
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int i = headerIndex + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String rawPlate = cellString(row.getCell(plateCol), formatter, evaluator);
                String plate = PlateNormalizer.normalize(rawPlate);
                if (plate.isBlank()) continue;

                BigDecimal kwh = cellDecimal(row.getCell(kwhCol), formatter, evaluator, "kWh", i + 1);
                BigDecimal amount = cellDecimal(row.getCell(amountCol), formatter, evaluator, "tutar", i + 1);
                if (kwh.signum() == 0 && amount.signum() == 0) continue;

                rows.add(new ChargingRow(plate, kwh, amount, i + 1));
            }
            return rows;
        }
    }

    private Sheet selectSheet(Workbook workbook, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet named = workbook.getSheet(sheetName);
            if (named == null) {
                throw new IllegalArgumentException("Excel sheet bulunamadi: " + sheetName);
            }
            return named;
        }
        return workbook.getSheetAt(0);
    }

    private Map<String, Integer> indexHeaders(Row header) {
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("tr-TR"));
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Cell cell : header) {
            String normalized = normalizeHeader(formatter.formatCellValue(cell));
            if (!normalized.isBlank()) map.put(normalized, cell.getColumnIndex());
        }
        return map;
    }

    private int resolveColumn(Map<String, Integer> headerMap, String configuredColumn, List<String> aliases, String label) {
        if (configuredColumn != null && !configuredColumn.isBlank()) {
            String key = normalizeHeader(configuredColumn);
            Integer index = headerMap.get(key);
            if (index == null) throw new IllegalArgumentException("Konfigurdeki " + label + " kolonu bulunamadi: " + configuredColumn);
            return index;
        }
        for (String alias : aliases) {
            Integer index = headerMap.get(normalizeHeader(alias));
            if (index != null) return index;
        }
        throw new IllegalArgumentException(label + " kolonu otomatik bulunamadi. Config icinde kolon adini belirtin. Headerlar: " + headerMap.keySet());
    }

    private String normalizeHeader(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.forLanguageTag("tr-TR"))
                .replace('ı', 'i')
                .replace('ş', 's')
                .replace('ğ', 'g')
                .replace('ü', 'u')
                .replace('ö', 'o')
                .replace('ç', 'c')
                .replaceAll("\\s+", " ");
    }

    private String cellString(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private BigDecimal cellDecimal(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, String label, int rowNum) {
        if (cell == null) return BigDecimal.ZERO;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.FORMULA && evaluator.evaluate(cell).getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(evaluator.evaluate(cell).getNumberValue());
            }
            String text = formatter.formatCellValue(cell, evaluator).trim();
            if (text.isBlank()) return BigDecimal.ZERO;
            // TR format: 1.234,56 -> 1234.56. EN format: 1234.56 korunur.
            text = text.replace("₺", "").replace("TL", "").replace("tl", "").trim();
            if (text.contains(",") && text.contains(".")) {
                text = text.replace(".", "").replace(",", ".");
            } else if (text.contains(",")) {
                text = text.replace(",", ".");
            }
            text = text.replaceAll("[^0-9.-]", "");
            if (text.isBlank()) return BigDecimal.ZERO;
            return new BigDecimal(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("Satir " + rowNum + " icin " + label + " sayiya cevrilemedi: " + formatter.formatCellValue(cell, evaluator), e);
        }
    }
}
