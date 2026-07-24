package com.storeprofit.system.warehouse;

import java.time.LocalDateTime;
import java.util.List;

public record WarehouseItemRequisitionPolicyRequest(
    String scopeMode,
    List<String> regionCodes,
    List<String> storeIds,
    String campaignName,
    LocalDateTime startsAt,
    LocalDateTime endsAt
) {
}
