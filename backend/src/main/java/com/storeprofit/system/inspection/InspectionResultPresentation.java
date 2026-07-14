package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record InspectionResultPresentation(
    BigDecimal originalPassScore,
    BigDecimal displayFullScore,
    BigDecimal displayScore,
    BigDecimal displayPassScore,
    BigDecimal displayMaterialScore,
    BigDecimal displayHygieneScore,
    BigDecimal displayServiceScore,
    boolean displayPassed,
    String displayResultCode,
    String repairStatus,
    boolean repaired,
    BigDecimal repairedScore,
    BigDecimal repairedFullScore,
    BigDecimal repairedPassScore,
    BigDecimal repairedMaterialScore,
    BigDecimal repairedHygieneScore,
    BigDecimal repairedServiceScore,
    Boolean repairedPassed,
    String repairedResultCode,
    String repairReason,
    String originalStandardVersion,
    Long repairAuditId,
    Long repairedBy,
    LocalDateTime repairedAt,
    BigDecimal referenceScore200
) {
}
