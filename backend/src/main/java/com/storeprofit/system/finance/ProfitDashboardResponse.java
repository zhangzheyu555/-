package com.storeprofit.system.finance;

import com.storeprofit.system.organization.BrandResponse;
import java.util.List;

public record ProfitDashboardResponse(
    List<String> months,
    List<BrandResponse> brands,
    ProfitSummaryResponse summary,
    List<ProfitEntryResponse> entries,
    List<ProfitTrendPoint> trend
) {
}
