package com.storeprofit.system.warehouse;

public record WarehouseItemDepartmentRequest(
    String departmentName,
    String departmentCode,
    String departmentGroup,
    String purchaseMethod,
    String supplierName
) {
}
