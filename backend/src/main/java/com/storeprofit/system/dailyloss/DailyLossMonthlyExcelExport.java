package com.storeprofit.system.dailyloss;

/** In-memory response body and safe download name for a monthly daily-loss workbook. */
public record DailyLossMonthlyExcelExport(byte[] content, String fileName, int summaryRowCount,
                                          int detailRowCount) {
}
