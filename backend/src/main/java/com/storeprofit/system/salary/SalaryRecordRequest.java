package com.storeprofit.system.salary;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record SalaryRecordRequest(
    String storeId,
    @NotBlank String month,
    String employeeId,
    @NotBlank String employeeName,
    String position,
    String attendance,
    BigDecimal gross,
    BigDecimal normalHours,
    BigDecimal otHours,
    BigDecimal workHours,
    BigDecimal vacationLeft,
    String vacationNote,
    BigDecimal base,
    BigDecimal social,
    BigDecimal post,
    BigDecimal meal,
    BigDecimal fullAttendance,
    BigDecimal commission,
    BigDecimal overtime,
    BigDecimal seniority,
    BigDecimal lateNight,
    BigDecimal subsidy,
    BigDecimal performance,
    BigDecimal deductUniform,
    BigDecimal returnUniform
) {
}
