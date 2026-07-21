package com.storeprofit.system.salary;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SalaryAttendanceRequest(
    @NotBlank String storeId,
    @NotBlank String employeeId,
    @NotBlank String month,
    @NotNull @DecimalMin("0") BigDecimal attendanceDays,
    @NotNull @DecimalMin("0") BigDecimal overtimeHours,
    @DecimalMin("0") BigDecimal normalHours
) {
}
