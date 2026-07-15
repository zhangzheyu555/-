package com.storeprofit.system.inspection;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record InspectionRectificationSubmitRequest(
    @NotBlank(message = "请填写整改说明") String note,
    List<Long> attachmentIds
) {
}
