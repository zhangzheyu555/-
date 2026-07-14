package com.storeprofit.system.inspection;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

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
    String note,
    Long standardVersionId,
    String standardVersion,
    BigDecimal materialScore,
    BigDecimal hygieneScore,
    BigDecimal serviceScore,
    String resultCode,
    List<InspectionItemResultRequest> itemResults
) {
  public InspectionRecordRequest {
    itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
  }

  public InspectionRecordRequest(
      String storeId,
      String inspectionDate,
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
    this(storeId, inspectionDate, inspector, brand, fullScore, score, passed, deductionsJson,
        redlinesJson, photosJson, note, null, null, null, null, null, null, List.of());
  }
}
