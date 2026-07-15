package com.storeprofit.system.inspection;

import jakarta.validation.constraints.NotBlank;

public record InspectionRectificationReviewRequest(
    @NotBlank(message = "请选择复核结论") String decision,
    @NotBlank(message = "请填写复核备注") String note
) {
}
