package com.storeprofit.system.employee;

import java.math.BigDecimal;

public record EmployeeResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String name,
    String phone,
    String role,
    String position,
    String employmentType,
    BigDecimal baseSalary,
    String status,
    String hireDate,
    String remark,
    String birthday,
    String idCardNo,
    String healthCertIssueDate,
    String healthCertExpireDate,
    String contractSignText,
    String regularDate,
    String trainerDate,
    String shiftLeaderDate,
    String managerDate,
    Long authUserId,
    String accountUsername,
    Boolean accountEnabled,
    BigDecimal hourlyRate
) {

  /** 非 BOSS 角色看到的身份证脱敏视图：保留前 6 后 2。 */
  public EmployeeResponse withMaskedIdCard() {
    if (idCardNo == null || idCardNo.length() <= 8) {
      return this;
    }
    String masked = idCardNo.substring(0, 6)
        + "*".repeat(idCardNo.length() - 8)
        + idCardNo.substring(idCardNo.length() - 2);
    return new EmployeeResponse(
        id, storeId, storeCode, storeName, brandId, brandName, name, phone,
        role, position, employmentType, baseSalary, status, hireDate, remark,
        birthday, masked, healthCertIssueDate, healthCertExpireDate,
        contractSignText, regularDate, trainerDate, shiftLeaderDate, managerDate,
        authUserId, accountUsername, accountEnabled, hourlyRate);
  }
}
