package com.storeprofit.system.inspection;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record InspectionRecordRequest(
    @NotBlank String storeId,
    @NotBlank String inspectionDate,
    String inspector,
    String brand,
    BigDecimal fullScore,
    BigDecimal score,
    Boolean passed,
    String deductionsJson,
    String redlinesJson,
    String photosJson,
    String note
) {
}
