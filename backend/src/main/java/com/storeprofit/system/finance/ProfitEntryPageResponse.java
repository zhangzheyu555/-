package com.storeprofit.system.finance;

import java.util.List;

public record ProfitEntryPageResponse(
    List<ProfitEntryResponse> rows,
    int total,
    int page,
    int size,
    int totalPages
) {}
