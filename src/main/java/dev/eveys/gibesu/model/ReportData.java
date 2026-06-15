package dev.eveys.gibesu.model;

import java.time.YearMonth;
import java.util.List;

public record ReportData(
        String vkn,
        String companyName,
        String epdkLicenseNo,
        YearMonth period,
        List<PlateSummary> summaries
) { }
