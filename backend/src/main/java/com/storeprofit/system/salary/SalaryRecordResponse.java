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
  /**
   * Return a copy with all monetary amount fields set to null,
   * used for role-based field-level masking (e.g. STORE_MANAGER).
   * Non-monetary fields (employee info, status, attendance) are preserved.
   */
  public SalaryRecordResponse masked() {
    return new SalaryRecordResponse(
        id, storeId, storeCode, storeName, brandId, brandName, month,
        employeeId, employeeName, position, attendance,
        null, // gross
        normalHours, otHours, workHours, vacationLeft, vacationNote,
        null, // base
        null, // social
        null, // post
        null, // meal
        null, // fullAttendance
        null, // commission
        null, // overtime
        null, // seniority
        null, // lateNight
        null, // subsidy
        null, // performance
        null, // deductUniform
        null, // returnUniform
        status, submittedBy, reviewedBy, reviewedAt, reviewNote, paidAt, version
    );
  }
}
