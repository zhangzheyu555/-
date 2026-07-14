package com.storeprofit.system.inspection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Manual remapping changes only the matched clause; its score still comes from the clause. */
public record InspectionDetectionAdjustmentRequest(
    @NotNull Long targetClauseId,
    @NotBlank String reason
) {
}
