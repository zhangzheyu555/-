package com.storeprofit.system.employee;

/** 员工档案新增/修改请求；日期一律 yyyy-MM-dd 字符串，生日为「月.日」原文。 */
public record EmployeeUpsertRequest(
    String storeId,
    String name,
    String phone,
    String position,
    String employmentType,
    String status,
    String hireDate,
    String birthday,
    String idCardNo,
    String healthCertIssueDate,
    String healthCertExpireDate,
    String contractSignText,
    String regularDate,
    String trainerDate,
    String shiftLeaderDate,
    String managerDate,
    String remark
) {
}
