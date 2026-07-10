package com.storeprofit.system.storemanager;

import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreScope;
import java.math.BigDecimal;
import java.util.List;

public record StoreManagerInspectionPageResponse(
    String roleName,
    String dataSource,
    String updatedAt,
    StoreScope store,
    StoreManagerInspectionSummary summary,
    List<InspectionRecordResponse> records
) {
  public record StoreManagerInspectionSummary(
      int totalCount,
      int monthCount,
      BigDecimal averageScore,
      int redlineCount
  ) {
  }
}
