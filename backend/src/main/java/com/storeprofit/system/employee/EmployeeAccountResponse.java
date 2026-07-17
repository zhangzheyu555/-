package com.storeprofit.system.employee;

/** 开号结果：初始密码只在本响应中一次性返回，不落日志。 */
public record EmployeeAccountResponse(
    String employeeId,
    String employeeName,
    String username,
    String initialPassword
) {
}
