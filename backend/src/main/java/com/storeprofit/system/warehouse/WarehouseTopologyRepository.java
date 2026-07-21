package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for the explicit multi-warehouse topology added to the existing warehouse module. */
@Repository
public class WarehouseTopologyRepository {
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final JdbcTemplate jdbcTemplate;

  public WarehouseTopologyRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<FacilityRow> facilities(long tenantId) {
    return jdbcTemplate.query("""
        select w.id, w.code, w.name, w.warehouse_type, w.region_code,
               w.parent_warehouse_id, p.name as parent_warehouse_name,
               w.external_purchase_allowed, w.store_supply_allowed, w.enabled
        from warehouse_facility w
        left join warehouse_facility p
          on p.tenant_id = w.tenant_id and p.id = w.parent_warehouse_id
        where w.tenant_id = ?
        order by case w.warehouse_type when 'CENTRAL' then 0 else 1 end, w.id
        """, this::mapFacility, tenantId);
  }

  public Optional<FacilityRow> facility(long tenantId, long warehouseId) {
    return facilities(tenantId).stream().filter(row -> row.id() == warehouseId).findFirst();
  }

  /**
   * Deliberately returns only existence so authorization can distinguish a foreign-tenant ID
   * from an unknown ID without loading or exposing the foreign warehouse's attributes.
   */
  public boolean facilityExistsForOtherTenant(long tenantId, long warehouseId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from warehouse_facility
        where id = ? and tenant_id <> ?
        """, Integer.class, warehouseId, tenantId);
    return count != null && count > 0;
  }

  public Optional<FacilityRow> facilityByCode(long tenantId, String code) {
    String normalized = code == null ? "" : code.trim();
    return facilities(tenantId).stream().filter(row -> row.code().equals(normalized)).findFirst();
  }

  public Optional<FacilityRow> supplyWarehouseForRegion(long tenantId, String regionCode) {
    String normalized = regionCode == null ? "" : regionCode.trim().toUpperCase();
    return facilities(tenantId).stream()
        .filter(FacilityRow::enabled)
        .filter(FacilityRow::storeSupplyAllowed)
        .filter(row -> row.regionCode().equals(normalized))
        .findFirst();
  }

  public Optional<StoreSupplyRow> storeSupplyWarehouse(long tenantId, String storeId) {
    if (storeId == null || storeId.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select s.id as store_id, s.name as store_name, s.status as store_status,
               s.region_code, w.id as warehouse_id, w.code as warehouse_code,
               w.name as warehouse_name, w.warehouse_type, w.region_code as warehouse_region,
               w.parent_warehouse_id, w.external_purchase_allowed,
               w.store_supply_allowed, w.enabled
        from store_branch s
        left join warehouse_facility w
          on w.tenant_id = s.tenant_id and w.id = s.supply_warehouse_id
        where s.tenant_id = ? and s.id = ?
        limit 1
        """, this::mapStoreSupply, tenantId, storeId.trim()).stream().findFirst();
  }

  public boolean routeEnabled(long tenantId, long sourceWarehouseId, long targetWarehouseId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_transfer_route
        where tenant_id = ? and source_warehouse_id = ? and target_warehouse_id = ? and enabled = 1
        """, Integer.class, tenantId, sourceWarehouseId, targetWarehouseId);
    return count != null && count > 0;
  }

  /**
   * Read-time material availability for a transfer source warehouse. This deliberately does not
   * lock inventory: the result is only a workbench hint and the approval transaction locks and
   * rechecks the same inventory rows before reserving stock.
   */
  public List<TransferMaterialRow> transferMaterials(long tenantId, long sourceWarehouseId) {
    return jdbcTemplate.query("""
        select i.id as item_id, i.code, i.name, coalesce(i.stock_unit, i.unit, '件') as unit,
               coalesce(inventory.on_hand_quantity, 0) - coalesce(inventory.reserved_quantity, 0)
                 as available_quantity
        from warehouse_item i
        left join warehouse_inventory inventory
          on inventory.tenant_id = i.tenant_id
         and inventory.warehouse_id = ?
         and inventory.item_id = i.id
        where i.tenant_id = ? and i.active = 1
        order by i.sort_order, i.code, i.id
        """, this::mapTransferMaterial, sourceWarehouseId, tenantId);
  }

  public List<WarehouseTransferResponse> transfers(long tenantId) {
    List<TransferHeaderRow> headers = jdbcTemplate.query("""
        select t.id, t.transfer_no, t.status, t.source_warehouse_id, source.name as source_name,
               t.target_warehouse_id, target.name as target_name, t.total_amount,
               requester.display_name as requested_by, reviewer.display_name as approved_by,
               shipper.display_name as shipped_by, receiver.display_name as received_by,
               canceller.display_name as cancelled_by, t.created_at, t.submitted_at,
               t.reviewed_at, t.shipped_at, t.received_at, t.cancelled_at,
               t.note, t.review_note, t.version
        from warehouse_transfer_order t
        join warehouse_facility source
          on source.tenant_id = t.tenant_id and source.id = t.source_warehouse_id
        join warehouse_facility target
          on target.tenant_id = t.tenant_id and target.id = t.target_warehouse_id
        left join auth_user requester on requester.tenant_id = t.tenant_id and requester.id = t.requested_by
        left join auth_user reviewer on reviewer.tenant_id = t.tenant_id and reviewer.id = t.reviewed_by
        left join auth_user shipper on shipper.tenant_id = t.tenant_id and shipper.id = t.shipped_by
        left join auth_user receiver on receiver.tenant_id = t.tenant_id and receiver.id = t.received_by
        left join auth_user canceller on canceller.tenant_id = t.tenant_id and canceller.id = t.cancelled_by
        where t.tenant_id = ?
        order by t.created_at desc, t.id desc
        """, this::mapTransferHeader, tenantId);
    // Batch-fetch all lines in a single query instead of N+1 per header
    java.util.Map<String, java.util.List<WarehouseTransferLineResponse>> linesByTransfer =
        batchTransferLines(tenantId);
    ArrayList<WarehouseTransferResponse> rows = new ArrayList<>();
    for (TransferHeaderRow header : headers) {
      rows.add(toResponse(header,
          linesByTransfer.getOrDefault(header.id(), java.util.List.of())));
    }
    return List.copyOf(rows);
  }

  /**
   * Returns ALL transfer lines for a tenant in a single query, keyed by transfer_order_id.
   * Eliminates the N+1 query problem in {@link #transfers(long)}.
   */
  private java.util.Map<String, java.util.List<WarehouseTransferLineResponse>> batchTransferLines(
      long tenantId
  ) {
    List<TransferLineRow> rows = jdbcTemplate.query("""
        select l.transfer_order_id, l.id, l.item_id, i.name as item_name,
               coalesce(i.stock_unit, i.unit, '件') as unit,
               l.requested_quantity, l.approved_quantity, l.reserved_quantity,
               l.shipped_quantity, l.received_quantity, l.in_transit_quantity,
               l.unit_cost, l.amount, l.note
        from warehouse_transfer_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ?
        order by l.transfer_order_id, l.id
        """, this::mapTransferLineRow, tenantId);
    java.util.Map<String, java.util.List<WarehouseTransferLineResponse>> result =
        new java.util.LinkedHashMap<>();
    for (TransferLineRow row : rows) {
      result.computeIfAbsent(row.transferOrderId, k -> new java.util.ArrayList<>())
          .add(row.line);
    }
    return result;
  }

  public Optional<WarehouseTransferResponse> transfer(long tenantId, String transferId) {
    return transfers(tenantId).stream().filter(row -> row.id().equals(transferId)).findFirst();
  }

  public Optional<WarehouseTransferResponse> transferForUpdate(long tenantId, String transferId) {
    List<String> ids = jdbcTemplate.query(
        "select id from warehouse_transfer_order where tenant_id = ? and id = ? for update",
        (rs, rowNum) -> rs.getString(1), tenantId, transferId);
    return ids.isEmpty() ? Optional.empty() : transfer(tenantId, transferId);
  }

  public Optional<WarehouseTransferResponse> transferByCreateKey(long tenantId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select id from warehouse_transfer_order
        where tenant_id = ? and idempotency_key = ? limit 1
        """, (rs, rowNum) -> rs.getString(1), tenantId, idempotencyKey.trim()).stream()
        .findFirst()
        .flatMap(id -> transfer(tenantId, id));
  }

  public boolean insertTransfer(
      long tenantId,
      String id,
      String transferNo,
      long sourceWarehouseId,
      long targetWarehouseId,
      String idempotencyKey,
      String note,
      long requestedBy
  ) {
    try {
      jdbcTemplate.update("""
        insert into warehouse_transfer_order(
          id, tenant_id, transfer_no, source_warehouse_id, target_warehouse_id,
          status, idempotency_key, total_amount, version, note, requested_by, created_at
        ) values (?, ?, ?, ?, ?, 'DRAFT', ?, 0, 0, ?, ?, current_timestamp)
        """, id, tenantId, transferNo, sourceWarehouseId, targetWarehouseId,
          idempotencyKey, blankToNull(note), requestedBy);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  public void insertTransferLine(
      long tenantId,
      String transferId,
      long itemId,
      BigDecimal requestedQuantity,
      String note
  ) {
    jdbcTemplate.update("""
        insert into warehouse_transfer_line(
          tenant_id, transfer_order_id, item_id, requested_quantity, note, created_at
        ) values (?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, transferId, itemId, amount(requestedQuantity), blankToNull(note));
  }

  public int markSubmitted(long tenantId, String transferId, long expectedVersion) {
    return jdbcTemplate.update("""
        update warehouse_transfer_order
        set status = 'SUBMITTED', submitted_at = current_timestamp,
            version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'DRAFT' and version = ?
        """, tenantId, transferId, expectedVersion);
  }

  public void updateTransferLineApproval(
      long tenantId,
      long lineId,
      BigDecimal approvedQuantity,
      BigDecimal unitCost
  ) {
    BigDecimal quantity = amount(approvedQuantity);
    BigDecimal cost = cost(unitCost);
    jdbcTemplate.update("""
        update warehouse_transfer_line
        set approved_quantity = ?, reserved_quantity = ?, unit_cost = ?, amount = ?,
            version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, quantity, quantity, cost, quantity.multiply(cost).setScale(2, RoundingMode.HALF_UP),
        tenantId, lineId);
  }

  public int markReviewed(
      long tenantId,
      String transferId,
      long expectedVersion,
      boolean approved,
      BigDecimal totalAmount,
      long reviewedBy,
      String note
  ) {
    return jdbcTemplate.update("""
        update warehouse_transfer_order
        set status = ?, total_amount = ?, reviewed_by = ?, review_note = ?,
            reviewed_at = current_timestamp, version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'SUBMITTED' and version = ?
        """, approved ? "APPROVED" : "REJECTED", amount(totalAmount), reviewedBy,
        blankToNull(note), tenantId, transferId, expectedVersion);
  }

  public void updateTransferLineShipped(
      long tenantId,
      long lineId,
      BigDecimal shippedQuantity,
      BigDecimal unitCost
  ) {
    BigDecimal quantity = amount(shippedQuantity);
    BigDecimal lineCost = cost(unitCost);
    jdbcTemplate.update("""
        update warehouse_transfer_line
        set reserved_quantity = 0, shipped_quantity = ?, in_transit_quantity = ?,
            unit_cost = ?, amount = ?, version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, quantity, quantity, lineCost,
        quantity.multiply(lineCost).setScale(2, RoundingMode.HALF_UP), tenantId, lineId);
  }

  public int markShipped(long tenantId, String transferId, long expectedVersion, long shippedBy) {
    return jdbcTemplate.update("""
        update warehouse_transfer_order
        set status = 'SHIPPED', shipped_by = ?, shipped_at = current_timestamp,
            version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'APPROVED' and version = ?
        """, shippedBy, tenantId, transferId, expectedVersion);
  }

  public int addTransferLineReceived(long tenantId, long lineId, BigDecimal receivedQuantity) {
    BigDecimal quantity = amount(receivedQuantity);
    return jdbcTemplate.update("""
        update warehouse_transfer_line
        set received_quantity = received_quantity + ?,
            in_transit_quantity = in_transit_quantity - ?,
            version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ?
          and in_transit_quantity >= ?
        """, quantity, quantity, tenantId, lineId, quantity);
  }

  public int markReceived(
      long tenantId,
      String transferId,
      long expectedVersion,
      boolean complete,
      long receivedBy
  ) {
    return jdbcTemplate.update("""
        update warehouse_transfer_order
        set status = ?, received_by = ?,
            received_at = case when ? = 1 then current_timestamp else received_at end,
            version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ?
          and status in ('SHIPPED', 'PARTIALLY_RECEIVED') and version = ?
        """, complete ? "RECEIVED" : "PARTIALLY_RECEIVED", receivedBy,
        complete, tenantId, transferId, expectedVersion);
  }

  public int markCancelled(
      long tenantId,
      String transferId,
      long expectedVersion,
      long cancelledBy,
      String note
  ) {
    return jdbcTemplate.update("""
        update warehouse_transfer_order
        set status = 'CANCELLED', cancelled_by = ?, cancelled_at = current_timestamp,
            note = coalesce(?, note), version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status in ('DRAFT', 'SUBMITTED', 'APPROVED')
          and version = ?
        """, cancelledBy, blankToNull(note), tenantId, transferId, expectedVersion);
  }

  public boolean actionExists(long tenantId, String actionType, String idempotencyKey) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_transfer_action
        where tenant_id = ? and action_type = ? and idempotency_key = ?
        """, Integer.class, tenantId, actionType, idempotencyKey);
    return count != null && count > 0;
  }

  public Optional<String> actionTransferId(long tenantId, String actionType, String idempotencyKey) {
    return jdbcTemplate.query("""
        select transfer_order_id from warehouse_transfer_action
        where tenant_id = ? and action_type = ? and idempotency_key = ?
        limit 1
        """, (rs, rowNum) -> rs.getString(1), tenantId, actionType, idempotencyKey)
        .stream().findFirst();
  }

  public boolean insertAction(
      long tenantId,
      String transferId,
      String actionType,
      String idempotencyKey,
      long resultVersion,
      long operatorId
  ) {
    try {
      jdbcTemplate.update("""
          insert into warehouse_transfer_action(
            tenant_id, transfer_order_id, action_type, idempotency_key,
            result_version, operator_id, created_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp)
          """, tenantId, transferId, actionType, idempotencyKey, resultVersion, operatorId);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  public InventoryRow lockInventory(long tenantId, long warehouseId, long itemId) {
    jdbcTemplate.update("""
        insert into warehouse_inventory(
          tenant_id, warehouse_id, item_id, on_hand_quantity, reserved_quantity,
          in_transit_quantity, unit_cost, min_stock_quantity, alert_enabled,
          expiry_alert_days, version, created_at
        )
        select ?, ?, i.id, 0, 0, 0, 0,
               i.min_stock_quantity, i.alert_enabled, i.expiry_alert_days, 0, current_timestamp
        from warehouse_item i
        where i.tenant_id = ? and i.id = ?
        on duplicate key update warehouse_id = values(warehouse_id)
        """, tenantId, warehouseId, tenantId, itemId);
    return jdbcTemplate.query("""
        select warehouse_id, item_id, on_hand_quantity, reserved_quantity,
               in_transit_quantity, unit_cost, version
        from warehouse_inventory
        where tenant_id = ? and warehouse_id = ? and item_id = ?
        for update
        """, this::mapInventory, tenantId, warehouseId, itemId).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("warehouse inventory row was not created"));
  }

  public boolean updateInventory(
      long tenantId,
      InventoryRow current,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal inTransit,
      BigDecimal unitCost
  ) {
    return jdbcTemplate.update("""
        update warehouse_inventory
        set on_hand_quantity = ?, reserved_quantity = ?, in_transit_quantity = ?,
            unit_cost = ?, version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and warehouse_id = ? and item_id = ? and version = ?
        """, amount(onHand), amount(reserved), amount(inTransit), cost(unitCost),
        tenantId, current.warehouseId(), current.itemId(), current.version()) == 1;
  }

  public List<BatchRow> positiveBatchesForUpdate(long tenantId, long warehouseId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, received_date, expiry_date, quantity,
               reserved_quantity, unit_cost, version
        from warehouse_stock_batch
        where tenant_id = ? and warehouse_id = ? and item_id = ? and quantity > 0
        order by received_date, created_at, id
        for update
        """, this::mapBatch, tenantId, warehouseId, itemId);
  }

  public Optional<BatchRow> batchForUpdate(long tenantId, long warehouseId, long batchId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, received_date, expiry_date, quantity,
               reserved_quantity, unit_cost, version
        from warehouse_stock_batch
        where tenant_id = ? and warehouse_id = ? and id = ?
        for update
        """, this::mapBatch, tenantId, warehouseId, batchId).stream().findFirst();
  }

  public boolean updateBatchQuantity(
      long tenantId,
      BatchRow batch,
      BigDecimal quantity,
      BigDecimal reservedQuantity
  ) {
    return jdbcTemplate.update("""
        update warehouse_stock_batch
        set quantity = ?, reserved_quantity = ?, version = version + 1, updated_at = current_timestamp
        where tenant_id = ? and id = ? and version = ?
        """, amount(quantity), amount(reservedQuantity), tenantId, batch.id(), batch.version()) == 1;
  }

  public void upsertReceivedBatch(
      long tenantId,
      long warehouseId,
      long itemId,
      String batchNo,
      BigDecimal quantity,
      BigDecimal unitCost,
      String note
  ) {
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date, expiry_date,
          quantity, reserved_quantity, version, unit_cost, note, created_at
        ) values (?, ?, ?, ?, current_date, null, ?, 0, 0, ?, ?, current_timestamp)
        on duplicate key update
          quantity = quantity + values(quantity),
          unit_cost = values(unit_cost),
          version = version + 1,
          updated_at = current_timestamp
        """, tenantId, warehouseId, itemId, batchNo, amount(quantity), cost(unitCost), blankToNull(note));
  }

  public void insertMovement(
      long tenantId,
      long warehouseId,
      long itemId,
      Long batchId,
      String movementType,
      BigDecimal quantityDelta,
      BigDecimal reservedDelta,
      BigDecimal inTransitDelta,
      BigDecimal unitCost,
      String sourceType,
      String sourceId,
      String storeId,
      String note,
      long operatorId
  ) {
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(
          tenant_id, warehouse_id, item_id, batch_id, movement_type, quantity_delta,
          reserved_quantity_delta, in_transit_quantity_delta, unit_cost,
          source_type, source_id, store_id, note, operator_id, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, warehouseId, itemId, batchId, movementType, amount(quantityDelta),
        amount(reservedDelta), amount(inTransitDelta), cost(unitCost), blankToNull(sourceType),
        blankToNull(sourceId), blankToNull(storeId), blankToNull(note), operatorId);
  }

  private List<WarehouseTransferLineResponse> transferLines(long tenantId, String transferId) {
    return jdbcTemplate.query("""
        select l.id, l.item_id, i.name as item_name, coalesce(i.stock_unit, i.unit, '件') as unit,
               l.requested_quantity, l.approved_quantity, l.reserved_quantity,
               l.shipped_quantity, l.received_quantity, l.in_transit_quantity,
               l.unit_cost, l.amount, l.note
        from warehouse_transfer_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.transfer_order_id = ?
        order by l.id
        """, this::mapTransferLine, tenantId, transferId);
  }

  private WarehouseTransferResponse toResponse(
      TransferHeaderRow row,
      List<WarehouseTransferLineResponse> lines
  ) {
    return new WarehouseTransferResponse(
        row.id(), row.transferNo(), row.status(), statusLabel(row.status()),
        row.sourceWarehouseId(), row.sourceWarehouseName(), row.targetWarehouseId(),
        row.targetWarehouseName(), row.totalAmount(), row.requestedBy(), row.approvedBy(),
        row.shippedBy(), row.receivedBy(), row.cancelledBy(), row.createdAt(), row.submittedAt(),
        row.reviewedAt(), row.shippedAt(), row.receivedAt(), row.cancelledAt(), row.note(),
        row.reviewNote(), row.version(), lines
    );
  }

  private FacilityRow mapFacility(ResultSet rs, int rowNum) throws SQLException {
    return new FacilityRow(
        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
        rs.getString("warehouse_type"), rs.getString("region_code"),
        rs.getObject("parent_warehouse_id", Long.class), rs.getString("parent_warehouse_name"),
        rs.getBoolean("external_purchase_allowed"), rs.getBoolean("store_supply_allowed"),
        rs.getBoolean("enabled")
    );
  }

  private TransferMaterialRow mapTransferMaterial(ResultSet rs, int rowNum) throws SQLException {
    return new TransferMaterialRow(
        rs.getLong("item_id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("available_quantity"))
    );
  }

  private StoreSupplyRow mapStoreSupply(ResultSet rs, int rowNum) throws SQLException {
    Long warehouseId = rs.getObject("warehouse_id", Long.class);
    FacilityRow facility = warehouseId == null ? null : new FacilityRow(
        warehouseId, rs.getString("warehouse_code"), rs.getString("warehouse_name"),
        rs.getString("warehouse_type"), rs.getString("warehouse_region"),
        rs.getObject("parent_warehouse_id", Long.class), null,
        rs.getBoolean("external_purchase_allowed"), rs.getBoolean("store_supply_allowed"),
        rs.getBoolean("enabled")
    );
    return new StoreSupplyRow(
        rs.getString("store_id"), rs.getString("store_name"), rs.getString("store_status"),
        rs.getString("region_code"), facility
    );
  }

  private TransferLineRow mapTransferLineRow(ResultSet rs, int rowNum) throws SQLException {
    return new TransferLineRow(
        rs.getString("transfer_order_id"), mapTransferLine(rs, rowNum));
  }

  private TransferHeaderRow mapTransferHeader(ResultSet rs, int rowNum) throws SQLException {
    return new TransferHeaderRow(
        rs.getString("id"), rs.getString("transfer_no"), rs.getString("status"),
        rs.getLong("source_warehouse_id"), rs.getString("source_name"),
        rs.getLong("target_warehouse_id"), rs.getString("target_name"),
        amount(rs.getBigDecimal("total_amount")), rs.getString("requested_by"),
        rs.getString("approved_by"), rs.getString("shipped_by"), rs.getString("received_by"),
        rs.getString("cancelled_by"), format(rs.getObject("created_at", LocalDateTime.class)),
        format(rs.getObject("submitted_at", LocalDateTime.class)),
        format(rs.getObject("reviewed_at", LocalDateTime.class)),
        format(rs.getObject("shipped_at", LocalDateTime.class)),
        format(rs.getObject("received_at", LocalDateTime.class)),
        format(rs.getObject("cancelled_at", LocalDateTime.class)),
        rs.getString("note"), rs.getString("review_note"), rs.getLong("version")
    );
  }

  private WarehouseTransferLineResponse mapTransferLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseTransferLineResponse(
        rs.getLong("id"), rs.getLong("item_id"), rs.getString("item_name"), rs.getString("unit"),
        amount(rs.getBigDecimal("requested_quantity")), amount(rs.getBigDecimal("approved_quantity")),
        amount(rs.getBigDecimal("reserved_quantity")), amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("received_quantity")), amount(rs.getBigDecimal("in_transit_quantity")),
        cost(rs.getBigDecimal("unit_cost")), amount(rs.getBigDecimal("amount")), rs.getString("note")
    );
  }

  private InventoryRow mapInventory(ResultSet rs, int rowNum) throws SQLException {
    return new InventoryRow(
        rs.getLong("warehouse_id"), rs.getLong("item_id"),
        amount(rs.getBigDecimal("on_hand_quantity")), amount(rs.getBigDecimal("reserved_quantity")),
        amount(rs.getBigDecimal("in_transit_quantity")), cost(rs.getBigDecimal("unit_cost")),
        rs.getLong("version")
    );
  }

  private BatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
    return new BatchRow(
        rs.getLong("id"), rs.getLong("item_id"), rs.getString("batch_no"),
        rs.getString("received_date"), rs.getString("expiry_date"),
        amount(rs.getBigDecimal("quantity")), amount(rs.getBigDecimal("reserved_quantity")),
        cost(rs.getBigDecimal("unit_cost")), rs.getLong("version")
    );
  }

  private String statusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "草稿";
      case "SUBMITTED" -> "已提交";
      case "APPROVED" -> "荆州已审批";
      case "REJECTED" -> "已驳回";
      case "SHIPPED" -> "运输中";
      case "PARTIALLY_RECEIVED" -> "部分收货";
      case "RECEIVED" -> "已完成";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal cost(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
        : value.setScale(4, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String format(LocalDateTime value) {
    return value == null ? null : value.format(DATE_TIME);
  }

  public record FacilityRow(
      long id,
      String code,
      String name,
      String type,
      String regionCode,
      Long parentWarehouseId,
      String parentWarehouseName,
      boolean externalPurchaseAllowed,
      boolean storeSupplyAllowed,
      boolean enabled
  ) {
  }

  public record StoreSupplyRow(
      String storeId,
      String storeName,
      String status,
      String regionCode,
      FacilityRow warehouse
  ) {
  }

  public record TransferMaterialRow(
      long itemId,
      String itemCode,
      String itemName,
      String unit,
      BigDecimal availableQuantity
  ) {
  }

  private record TransferLineRow(
      String transferOrderId,
      WarehouseTransferLineResponse line
  ) {
  }

  public record InventoryRow(
      long warehouseId,
      long itemId,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal inTransit,
      BigDecimal unitCost,
      long version
  ) {
    public BigDecimal available() {
      return onHand.subtract(reserved).setScale(2, RoundingMode.HALF_UP);
    }
  }

  public record BatchRow(
      long id,
      long itemId,
      String batchNo,
      String receivedDate,
      String expiryDate,
      BigDecimal quantity,
      BigDecimal reservedQuantity,
      BigDecimal unitCost,
      long version
  ) {
  }

  private record TransferHeaderRow(
      String id,
      String transferNo,
      String status,
      long sourceWarehouseId,
      String sourceWarehouseName,
      long targetWarehouseId,
      String targetWarehouseName,
      BigDecimal totalAmount,
      String requestedBy,
      String approvedBy,
      String shippedBy,
      String receivedBy,
      String cancelledBy,
      String createdAt,
      String submittedAt,
      String reviewedAt,
      String shippedAt,
      String receivedAt,
      String cancelledAt,
      String note,
      String reviewNote,
      long version
  ) {
  }
}
