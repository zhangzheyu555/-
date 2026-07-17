package com.storeprofit.system.employee;

import java.util.List;

/** Excel 导入报告。 */
public record EmployeeImportReport(
    int created,
    int updated,
    int skipped,
    List<String> createdStores,
    List<String> problems
) {
}
