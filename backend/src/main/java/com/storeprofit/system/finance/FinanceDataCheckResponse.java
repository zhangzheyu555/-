package com.storeprofit.system.finance;

public record FinanceDataCheckResponse(
    String id,
    String source,
    String issue,
    String storeId,
    String storeName,
    String month,
    String status
) {
}
