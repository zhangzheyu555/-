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
    String remark
) {
}
