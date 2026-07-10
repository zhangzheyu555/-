package com.storeprofit.system.warehouse;

public record WarehouseItemDepartmentResponse(
    Long id,
    String departmentName,
    String departmentCode,
    String departmentGroup,
    String purchaseMethod,
    String supplierName
) {
}
