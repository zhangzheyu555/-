package com.storeprofit.system.warehouse;

import jakarta.validation.Valid;
import java.util.List;

public record WarehouseRequisitionReviewRequest(
    boolean approved,
    List<@Valid WarehouseRequisitionReviewLineRequest> lines,
    String note,
    WarehouseRequisitionHandlingMode handlingMode
) {
  public WarehouseRequisitionReviewRequest(
      boolean approved,
      List<WarehouseRequisitionReviewLineRequest> lines,
      String note
  ) {
    this(approved, lines, note, null);
  }
}
