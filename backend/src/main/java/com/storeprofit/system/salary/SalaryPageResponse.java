package com.storeprofit.system.salary;

import java.util.List;

public record SalaryPageResponse(
    List<SalaryRecordResponse> rows,
    int total,
    int page,
    int size,
    int totalPages,
    SalarySummaryResponse summary
) {}
