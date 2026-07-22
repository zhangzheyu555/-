package com.storeprofit.system.salary;

import java.math.BigDecimal;

/**
 * 工资页的门店经营核对指标。
 *
 * <p>提成池和店铺基金必须按门店分别计算后再汇总，不是员工工资分项。
 */
public record SalaryBusinessMetricsResponse(
    BigDecimal revenue,
    BigDecimal effectiveHours,
    BigDecimal hourlyRevenue,
    BigDecimal perCapitaOutput,
    BigDecimal commissionPool,
    BigDecimal commissionTotal,
    BigDecimal storeFund
) {}
