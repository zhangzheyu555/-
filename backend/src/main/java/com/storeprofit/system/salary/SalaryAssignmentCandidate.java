package com.storeprofit.system.salary;

/** 工资页“添加人员”仅需的最小员工信息，避免暴露身份证等档案字段。 */
public record SalaryAssignmentCandidate(
    String employeeId,
    String employeeName,
    String position,
    String sourceStoreId,
    String sourceStoreName
) {
}
