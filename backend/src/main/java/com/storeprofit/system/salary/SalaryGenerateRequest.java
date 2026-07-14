package com.storeprofit.system.salary;

import jakarta.validation.constraints.NotBlank;

public record SalaryGenerateRequest(
    String storeId,
    @NotBlank String month
) {
}
