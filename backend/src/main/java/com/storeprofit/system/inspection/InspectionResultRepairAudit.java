package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record InspectionResultRepairAudit(
    long id,
    Long originalStandardVersionId,
    String originalStandardVersion,
    BigDecimal originalFullScore,
    BigDecimal originalPassScore,
    BigDecimal originalScore,
    BigDecimal originalMaterialScore,
    BigDecimal originalHygieneScore,
    BigDecimal originalServiceScore,
    String originalResultCode,
    boolean originalPassed,
    Long repairedStandardVersionId,
    String repairedStandardVersion,
    BigDecimal repairedFullScore,
    BigDecimal repairedPassScore,
    BigDecimal repairedScore,
    BigDecimal repairedMaterialScore,
    BigDecimal repairedHygieneScore,
    BigDecimal repairedServiceScore,
    String repairedResultCode,
    Boolean repairedPassed,
    String repairStatus,
    String repairReason,
    int snapshotItemCount,
    int expectedItemCount,
    Long repairedBy,
    LocalDateTime repairedAt
) {
}
