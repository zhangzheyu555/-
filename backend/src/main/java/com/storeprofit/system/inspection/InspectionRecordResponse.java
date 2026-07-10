package com.storeprofit.system.inspection;

import java.math.BigDecimal;

public record InspectionRecordResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String inspectionDate,
    String inspector,
    String brand,
    BigDecimal fullScore,
    BigDecimal score,
    boolean passed,
    String deductionsJson,
    String redlinesJson,
    String photosJson,
    String note
) {
}
