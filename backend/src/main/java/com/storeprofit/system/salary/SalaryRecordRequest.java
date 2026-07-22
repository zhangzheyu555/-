package com.storeprofit.system.salary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SalaryRecordRequest(
    String storeId,
    @NotBlank(message = "工资月份不能为空") String month,
    String employeeId,
    @NotBlank(message = "员工姓名不能为空") String employeeName,
    String position,
    String attendance,
    BigDecimal gross,
    BigDecimal normalHours,
    BigDecimal otHours,
    BigDecimal workHours,
    @DecimalMin(value = "0.0", message = "假期余额不能小于0")
    @DecimalMax(value = "365.0", message = "假期余额不能超过365天")
    BigDecimal vacationLeft,
    @Size(max = 255, message = "休息日期备注不能超过255个字") String vacationNote,
    BigDecimal base,
    BigDecimal social,
    BigDecimal post,
    BigDecimal meal,
    BigDecimal fullAttendance,
    BigDecimal commission,
    BigDecimal overtime,
    @DecimalMin(value = "0.0", message = "工龄工资不能小于0")
    BigDecimal seniority,
    @DecimalMin(value = "0.0", message = "员工福利（生日）不能小于0")
    BigDecimal birthdayBenefit,
    @DecimalMin(value = "0.0", message = "深夜加班金额不能小于0")
    BigDecimal lateNight,
    BigDecimal subsidy,
    BigDecimal performance,
    BigDecimal deductUniform,
    BigDecimal returnUniform
) {
}
