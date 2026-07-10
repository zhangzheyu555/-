package com.storeprofit.system.salary;

import java.math.BigDecimal;

public record SalaryRecordResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String month,
    String employeeId,
    String employeeName,
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
    BigDecimal returnUniform,
    String status,
    Long submittedBy,
    Long reviewedBy,
    java.time.LocalDateTime reviewedAt,
    String reviewNote,
    java.time.LocalDateTime paidAt,
    int version
) {
}
