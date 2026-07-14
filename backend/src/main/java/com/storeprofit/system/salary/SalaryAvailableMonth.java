package com.storeprofit.system.salary;

public record SalaryAvailableMonth(
    String month,
    int recordCount,
    String latestStatus
) {}
