package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Server-authoritative workbench context for warehouse transfers.
 *
 * <p>The client must use the explicit mode, routes and action flags here instead of deriving a
 * transfer direction from a warehouse type or a parent warehouse. Availability is a read-time
 * hint only; all mutating operations still reserve and validate stock transactionally.</p>
 */
public record WarehouseTransferContextResponse(
    WarehouseRef currentWarehouse,
    String mode,
    String workbenchLabel,
    List<Route> routes,
    Todos todos
) {
  public record WarehouseRef(long id, String code, String name) {
  }

  public record Route(
      WarehouseRef sourceWarehouse,
      WarehouseRef targetWarehouse,
      String formAction,
      String workbenchLabel,
      Actions actions,
      List<Material> materials
  ) {
  }

  public record Actions(
      boolean canCreate,
      boolean canSubmit,
      boolean canApprove,
      boolean canReject,
      boolean canShip,
      boolean canReceive,
      boolean canCancel
  ) {
    public boolean hasAnyAction() {
      return canCreate || canSubmit || canApprove || canReject || canShip || canReceive || canCancel;
    }
  }

  public record Material(
      long itemId,
      String itemName,
      String itemCode,
      String unit,
      BigDecimal availableQuantity,
      String shortageMessage
  ) {
  }

  public record Todos(
      int draft,
      int pendingApproval,
      int pendingShipment,
      int pendingReceipt,
      int completed
  ) {
  }
}
