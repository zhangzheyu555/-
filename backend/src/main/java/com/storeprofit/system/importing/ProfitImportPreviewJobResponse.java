package com.storeprofit.system.importing;

import java.math.BigDecimal;
import java.util.List;

public record ProfitImportPreviewJobResponse(
    String jobId,
    String status,
    String stage,
    int progress,
    int parsedRows,
    int validRows,
    int errorRows,
    BigDecimal salesTotal,
    List<String> fieldMappings,
    List<ProfitImportRow> rows,
    List<String> errors,
    String selectedMonth,
    List<String> detectedMonths,
    boolean monthConflict,
    long elapsedMs,
    String targetStoreId,
    String targetStoreName,
    String targetMonth
) {
}
