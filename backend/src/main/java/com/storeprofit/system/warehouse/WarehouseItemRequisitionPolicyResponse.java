package com.storeprofit.system.warehouse;

import java.time.LocalDateTime;
import java.util.List;

public record WarehouseItemRequisitionPolicyResponse(
    String scopeMode,
    List<String> regionCodes,
    List<String> storeIds,
    String campaignName,
    LocalDateTime startsAt,
    LocalDateTime endsAt,
    boolean configured
) {
  public static WarehouseItemRequisitionPolicyResponse legacyAllStores() {
    return new WarehouseItemRequisitionPolicyResponse(
        "ALL",
        List.of(),
        List.of(),
        null,
        null,
        null,
        false
    );
  }
}
