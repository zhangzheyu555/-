package com.storeprofit.system.salary;

import jakarta.validation.constraints.NotBlank;

/** 将其他门店的在职员工加入目标门店当月工资名单，不改员工档案归属。 */
public record SalaryAssignmentRequest(
    @NotBlank String storeId,
    @NotBlank String month,
    @NotBlank String employeeId
) {
}
