package com.storeprofit.system.warehouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private final JdbcTemplate jdbcTemplate;

  public WarehouseRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<WarehouseItemResponse> items(long tenantId) {
    return jdbcTemplate.query("""
        select i.id, i.code, i.name, i.category_id, coalesce(c.name, i.category) as category_name,
               i.category, i.image_url, i.unit, i.purchase_unit, i.stock_unit, i.ingredient_unit,
               i.unit_conversion_text, i.spec, i.warehouse_location, i.unit_price, i.shelf_life_days,
               i.cups_per_unit, i.daily_usage_estimate, i.min_stock_days, i.max_stock_days,
               i.min_stock_quantity, i.alert_enabled, i.expiry_alert_days, i.active,
               i.item_description, i.sort_order, i.item_attributes,
               coalesce(sum(case when b.quantity > 0 then b.quantity else 0 end), 0) as stock_quantity,
               min(case when b.quantity > 0 then b.expiry_date else null end) as nearest_expiry_date
        from warehouse_item i
        left join warehouse_item_category c on c.tenant_id = i.tenant_id and c.id = i.category_id
        left join warehouse_stock_batch b on b.tenant_id = i.tenant_id and b.item_id = i.id
        where i.tenant_id = ?
        group by i.id, i.code, i.name, i.category_id, c.name, i.category, i.image_url, i.unit,
                 i.purchase_unit, i.stock_unit, i.ingredient_unit, i.unit_conversion_text,
                 i.spec, i.warehouse_location, i.unit_price, i.shelf_life_days, i.cups_per_unit,
                 i.daily_usage_estimate, i.min_stock_days, i.max_stock_days, i.min_stock_quantity,
                 i.alert_enabled, i.expiry_alert_days, i.active, i.item_description, i.sort_order,
                 i.item_attributes
        order by i.active desc, i.sort_order, category_name, i.code
        """, this::mapItem, tenantId);
  }

  public Optional<WarehouseItemResponse> item(long tenantId, long itemId) {
    return items(tenantId).stream().filter(item -> item.id().equals(itemId)).findFirst();
  }

  public Map<Long, BigDecimal> storeInventoryQuantities(long tenantId, String storeId) {
    if (storeId == null || storeId.isBlank()) {
      return Map.of();
    }
    HashMap<Long, BigDecimal> quantities = new HashMap<>();
    jdbcTemplate.query("""
        select item_id, quantity
        from store_inventory
        where tenant_id = ? and store_id = ?
        """, rs -> {
          quantities.put(rs.getLong("item_id"), amount(rs.getBigDecimal("quantity")));
        },
        tenantId,
        storeId.trim()
    );
    return quantities;
  }

  public BigDecimal storeInventoryQuantity(long tenantId, String storeId, long itemId) {
    if (storeId == null || storeId.isBlank()) {
      return ZERO;
    }
    BigDecimal value = jdbcTemplate.query("""
        select quantity
        from store_inventory
        where tenant_id = ? and store_id = ? and item_id = ?
        limit 1
        """, (rs, rowNum) -> rs.getBigDecimal("quantity"), tenantId, storeId.trim(), itemId)
        .stream()
        .findFirst()
        .orElse(ZERO);
    return amount(value);
  }

  public void addStoreInventory(
      long tenantId,
      String storeId,
      long itemId,
      BigDecimal quantityDelta,
      String movementType,
      String sourceType,
      String sourceId,
      String note,
      Long createdBy
  ) {
    BigDecimal delta = amount(quantityDelta);
    jdbcTemplate.update("""
        insert into store_inventory(tenant_id, store_id, item_id, quantity, unit, updated_at)
        select ?, ?, ?, ?, coalesce(i.stock_unit, i.unit, '件'), current_timestamp
        from warehouse_item i
        where i.tenant_id = ? and i.id = ?
        on duplicate key update
          quantity = quantity + values(quantity),
          unit = values(unit),
          updated_at = current_timestamp
        """, tenantId, storeId, itemId, delta, tenantId, itemId);
    jdbcTemplate.update("""
        insert into store_inventory_movement(
          tenant_id, store_id, item_id, quantity_delta, movement_type, source_type, source_id, note, created_by, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        storeId,
        itemId,
        delta,
        movementType,
        blankToNull(sourceType),
        blankToNull(sourceId),
        blankToNull(note),
        createdBy
    );
  }

  public boolean itemExists(long tenantId, long itemId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        itemId
    );
    return count != null && count > 0;
  }

  public boolean activeItemExists(long tenantId, long itemId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item where tenant_id = ? and id = ? and active = 1",
        Integer.class,
        tenantId,
        itemId
    );
    return count != null && count > 0;
  }

  public long upsertItem(long tenantId, WarehouseItemRequest request) {
    String stockUnit = defaultText(request.stockUnit(), defaultText(request.unit(), "件"));
    String purchaseUnit = defaultText(request.purchaseUnit(), stockUnit);
    String ingredientUnit = defaultText(request.ingredientUnit(), stockUnit);
    String categoryName = request.categoryId() == null
        ? blankToNull(request.category())
        : itemCategoryName(tenantId, request.categoryId()).orElse(blankToNull(request.category()));
    jdbcTemplate.update("""
        insert into warehouse_item(
          id, tenant_id, code, name, category_id, category, image_url, unit, purchase_unit,
          stock_unit, ingredient_unit, unit_conversion_text, spec, warehouse_location, unit_price, shelf_life_days,
          cups_per_unit, daily_usage_estimate, min_stock_days, max_stock_days,
          min_stock_quantity, alert_enabled, expiry_alert_days, item_description, sort_order,
          item_attributes, active, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          code = values(code),
          name = values(name),
          category_id = values(category_id),
          category = values(category),
          image_url = values(image_url),
          unit = values(unit),
          purchase_unit = values(purchase_unit),
          stock_unit = values(stock_unit),
          ingredient_unit = values(ingredient_unit),
          unit_conversion_text = values(unit_conversion_text),
          spec = values(spec),
          warehouse_location = values(warehouse_location),
          unit_price = values(unit_price),
          shelf_life_days = values(shelf_life_days),
          cups_per_unit = values(cups_per_unit),
          daily_usage_estimate = values(daily_usage_estimate),
          min_stock_days = values(min_stock_days),
          max_stock_days = values(max_stock_days),
          min_stock_quantity = values(min_stock_quantity),
          alert_enabled = values(alert_enabled),
          expiry_alert_days = values(expiry_alert_days),
          item_description = values(item_description),
          sort_order = values(sort_order),
          item_attributes = values(item_attributes),
          active = values(active),
          updated_at = current_timestamp
        """,
        request.id(),
        tenantId,
        request.code().trim(),
        request.name().trim(),
        request.categoryId(),
        categoryName,
        blankToNull(request.imageUrl()),
        stockUnit,
        purchaseUnit,
        stockUnit,
        ingredientUnit,
        blankToNull(request.unitConversionText()),
        blankToNull(request.spec()),
        blankToNull(request.warehouseLocation()),
        amount(request.unitPrice()),
        request.shelfLifeDays(),
        amount(request.cupsPerUnit()),
        amount(request.dailyUsageEstimate()),
        positiveInt(request.minStockDays(), 7),
        positiveInt(request.maxStockDays(), 60),
        amount(request.minStockQuantity()),
        request.alertEnabled() == null || request.alertEnabled(),
        positiveInt(request.expiryAlertDays(), 3),
        blankToNull(request.itemDescription()),
        positiveInt(request.sortOrder(), 593),
        blankToNull(request.itemAttributes()),
        request.active() == null || request.active()
    );
    return itemIdByCode(tenantId, request.code().trim())
        .orElseThrow(() -> new IllegalStateException("warehouse item was not saved"));
  }

  public void replaceItemDepartments(long tenantId, long itemId, List<WarehouseItemDepartmentRequest> departments) {
    jdbcTemplate.update("delete from warehouse_item_department where tenant_id = ? and item_id = ?", tenantId, itemId);
    if (departments == null) {
      return;
    }
    for (WarehouseItemDepartmentRequest department : departments) {
      String name = blankToNull(department.departmentName());
      if (name == null) {
        continue;
      }
      jdbcTemplate.update("""
          insert into warehouse_item_department(
            tenant_id, item_id, department_name, department_code, department_group,
            purchase_method, supplier_name, created_at
          )
          values (?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          tenantId,
          itemId,
          name,
          blankToNull(department.departmentCode()),
          blankToNull(department.departmentGroup()),
          blankToNull(department.purchaseMethod()),
          blankToNull(department.supplierName())
      );
    }
  }

  public void setItemEnabled(long tenantId, long itemId, boolean enabled) {
    jdbcTemplate.update(
        "update warehouse_item set active = ?, updated_at = current_timestamp where tenant_id = ? and id = ?",
        enabled,
        tenantId,
        itemId
    );
  }

  public List<WarehouseItemCategoryResponse> itemCategories(long tenantId) {
    return jdbcTemplate.query("""
        select id, name, parent_id, sort_order, enabled
        from warehouse_item_category
        where tenant_id = ?
        order by coalesce(parent_id, 0), sort_order, name
        """, (rs, rowNum) -> new WarehouseItemCategoryResponse(
        rs.getLong("id"),
        rs.getString("name"),
        (Long) rs.getObject("parent_id"),
        rs.getInt("sort_order"),
        rs.getBoolean("enabled"),
        List.of()
    ), tenantId);
  }

  public Optional<String> itemCategoryName(long tenantId, Long categoryId) {
    if (categoryId == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(
          "select name from warehouse_item_category where tenant_id = ? and id = ?",
          String.class,
          tenantId,
          categoryId
      ));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public boolean itemCategoryExists(long tenantId, Long categoryId) {
    if (categoryId == null) {
      return false;
    }
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item_category where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        categoryId
    );
    return count != null && count > 0;
  }

  public boolean itemCategoryEnabled(long tenantId, Long categoryId) {
    if (categoryId == null) {
      return false;
    }
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item_category where tenant_id = ? and id = ? and enabled = 1",
        Integer.class,
        tenantId,
        categoryId
    );
    return count != null && count > 0;
  }

  public boolean itemCategoryNameExists(long tenantId, Long parentId, String name, Long excludeId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from warehouse_item_category
        where tenant_id = ?
          and ((parent_id is null and ? is null) or parent_id = ?)
          and name = ?
          and (? is null or id <> ?)
        """,
        Integer.class,
        tenantId,
        parentId,
        parentId,
        name,
        excludeId,
        excludeId
    );
    return count != null && count > 0;
  }

  public long upsertItemCategory(long tenantId, WarehouseItemCategoryRequest request) {
    if (request.id() == null) {
      jdbcTemplate.update("""
          insert into warehouse_item_category(tenant_id, name, parent_id, sort_order, enabled, created_at)
          values (?, ?, ?, ?, ?, current_timestamp)
          """,
          tenantId,
          request.name().trim(),
          request.parentId(),
          positiveInt(request.sortOrder(), 0),
          request.enabled() == null || request.enabled()
      );
      return jdbcTemplate.queryForObject("select max(id) from warehouse_item_category where tenant_id = ?", Long.class, tenantId);
    }
    jdbcTemplate.update("""
        update warehouse_item_category
        set name = ?,
            parent_id = ?,
            sort_order = ?,
            enabled = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        request.name().trim(),
        request.parentId(),
        positiveInt(request.sortOrder(), 0),
        request.enabled() == null || request.enabled(),
        tenantId,
        request.id()
    );
    return request.id();
  }

  public void setItemCategoryEnabled(long tenantId, long categoryId, boolean enabled) {
    jdbcTemplate.update(
        "update warehouse_item_category set enabled = ?, updated_at = current_timestamp where tenant_id = ? and id = ?",
        enabled,
        tenantId,
        categoryId
    );
  }

  public boolean subtractStoreInventoryIfEnough(
      long tenantId,
      String storeId,
      long itemId,
      BigDecimal quantity,
      String sourceType,
      String sourceId,
      String note,
      Long createdBy
  ) {
    return subtractStoreInventoryIfEnough(
        tenantId, storeId, itemId, quantity, "OUT", sourceType, sourceId, note, createdBy);
  }

  /**
   * Atomically deducts a store's on-hand quantity and records the named business movement.
   * Callers must supply a source-specific idempotency boundary before invoking this method.
   */
  public boolean subtractStoreInventoryIfEnough(
      long tenantId,
      String storeId,
      long itemId,
      BigDecimal quantity,
      String movementType,
      String sourceType,
      String sourceId,
      String note,
      Long createdBy
  ) {
    BigDecimal required = amount(quantity);
    String normalizedMovementType = blankToNull(movementType);
    if (normalizedMovementType == null) {
      normalizedMovementType = "OUT";
    }
    int changed = jdbcTemplate.update("""
        update store_inventory
        set quantity = quantity - ?, updated_at = current_timestamp
        where tenant_id = ? and store_id = ? and item_id = ? and quantity >= ?
        """, required, tenantId, storeId, itemId, required);
    if (changed != 1) {
      return false;
    }
    jdbcTemplate.update("""
        insert into store_inventory_movement(
          tenant_id, store_id, item_id, quantity_delta, movement_type,
          source_type, source_id, note, created_by, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, storeId, itemId, required.negate(), normalizedMovementType, blankToNull(sourceType),
        blankToNull(sourceId), blankToNull(note), createdBy);
    return true;
  }

  public List<WarehouseItemResponse> items(long tenantId, long warehouseId) {
    return jdbcTemplate.query("""
        select i.id, i.code, i.name, i.category_id, coalesce(c.name, i.category) as category_name,
               i.category, i.image_url, i.unit, i.purchase_unit, i.stock_unit, i.ingredient_unit,
               i.unit_conversion_text, i.spec, i.warehouse_location, i.unit_price, i.shelf_life_days,
               i.cups_per_unit, i.daily_usage_estimate, i.min_stock_days, i.max_stock_days,
               coalesce(inv.min_stock_quantity, i.min_stock_quantity) as min_stock_quantity,
               coalesce(inv.alert_enabled, i.alert_enabled) as alert_enabled,
               coalesce(inv.expiry_alert_days, i.expiry_alert_days) as expiry_alert_days, i.active,
               i.item_description, i.sort_order, i.item_attributes,
               coalesce(inv.on_hand_quantity - inv.reserved_quantity, 0) as stock_quantity,
               min(case when b.quantity > 0 then b.expiry_date else null end) as nearest_expiry_date
        from warehouse_item i
        left join warehouse_item_category c on c.tenant_id = i.tenant_id and c.id = i.category_id
        left join warehouse_inventory inv
          on inv.tenant_id = i.tenant_id and inv.item_id = i.id and inv.warehouse_id = ?
        left join warehouse_stock_batch b
          on b.tenant_id = i.tenant_id and b.item_id = i.id and b.warehouse_id = ?
        where i.tenant_id = ?
        group by i.id, i.code, i.name, i.category_id, c.name, i.category, i.image_url, i.unit,
                 i.purchase_unit, i.stock_unit, i.ingredient_unit, i.unit_conversion_text,
                 i.spec, i.warehouse_location, i.unit_price, i.shelf_life_days, i.cups_per_unit,
                 i.daily_usage_estimate, i.min_stock_days, i.max_stock_days,
                 i.min_stock_quantity, i.alert_enabled, i.expiry_alert_days,
                 inv.min_stock_quantity, inv.alert_enabled, inv.expiry_alert_days,
                 i.active, i.item_description, i.sort_order, i.item_attributes,
                 inv.on_hand_quantity, inv.reserved_quantity
        order by i.active desc, i.sort_order, category_name, i.code
        """, this::mapItem, warehouseId, warehouseId, tenantId);
  }

  public Optional<WarehouseItemResponse> item(long tenantId, long itemId, long warehouseId) {
    return items(tenantId, warehouseId).stream().filter(item -> item.id().equals(itemId)).findFirst();
  }

  public int itemCategoryItemCount(long tenantId, long categoryId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item where tenant_id = ? and category_id = ?",
        Integer.class,
        tenantId,
        categoryId
    );
    return count == null ? 0 : count;
  }

  public int itemCategoryChildCount(long tenantId, long categoryId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from warehouse_item_category where tenant_id = ? and parent_id = ?",
        Integer.class,
        tenantId,
        categoryId
    );
    return count == null ? 0 : count;
  }

  public void deleteItemCategory(long tenantId, long categoryId) {
    jdbcTemplate.update(
        "delete from warehouse_item_category where tenant_id = ? and id = ?",
        tenantId,
        categoryId
    );
  }

  public Optional<Long> itemIdByCode(long tenantId, String code) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(
          "select id from warehouse_item where tenant_id = ? and code = ?",
          Long.class,
          tenantId,
          code
      ));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public void updateAlertSettings(
      long tenantId,
      long itemId,
      BigDecimal minStockQuantity,
      boolean alertEnabled,
      Integer expiryAlertDays
  ) {
    jdbcTemplate.update("""
        update warehouse_item
        set min_stock_quantity = ?,
            alert_enabled = ?,
            expiry_alert_days = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        amount(minStockQuantity),
        alertEnabled,
        positiveInt(expiryAlertDays, 3),
        tenantId,
        itemId
    );
  }

  public void updateAlertSettings(
      long tenantId,
      long warehouseId,
      long itemId,
      BigDecimal minStockQuantity,
      boolean alertEnabled,
      Integer expiryAlertDays
  ) {
    jdbcTemplate.update("""
        insert into warehouse_inventory(
          tenant_id, warehouse_id, item_id, on_hand_quantity, reserved_quantity,
          in_transit_quantity, unit_cost, min_stock_quantity, alert_enabled,
          expiry_alert_days, version, created_at
        )
        select i.tenant_id, ?, i.id, 0, 0, 0, 0,
               i.min_stock_quantity, i.alert_enabled, i.expiry_alert_days, 0, current_timestamp
        from warehouse_item i
        where i.tenant_id = ? and i.id = ?
        on duplicate key update item_id = values(item_id)
        """, warehouseId, tenantId, itemId);
    jdbcTemplate.update("""
        update warehouse_inventory
        set min_stock_quantity = ?,
            alert_enabled = ?,
            expiry_alert_days = ?,
            updated_at = current_timestamp
        where tenant_id = ? and warehouse_id = ? and item_id = ?
        """,
        amount(minStockQuantity),
        alertEnabled,
        positiveInt(expiryAlertDays, 3),
        tenantId,
        warehouseId,
        itemId
    );
  }

  public void upsertBatch(long tenantId, WarehouseStockBatchRequest request) {
    if (hasWarehouseColumn("warehouse_stock_batch")) {
      upsertBatch(tenantId, centralWarehouseId(tenantId), request);
      return;
    }
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(
          tenant_id, item_id, batch_no, received_date, expiry_date, quantity, unit_cost, note, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          received_date = least(received_date, values(received_date)),
          expiry_date = coalesce(values(expiry_date), expiry_date),
          quantity = quantity + values(quantity),
          unit_cost = values(unit_cost), note = values(note), updated_at = current_timestamp
        """, tenantId, request.itemId(), request.batchNo().trim(), request.receivedDate(),
        blankToNull(request.expiryDate()), amount(request.quantity()), amount(request.unitCost()),
        blankToNull(request.note()));
  }

  public void upsertBatch(long tenantId, long warehouseId, WarehouseStockBatchRequest request) {
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(
          tenant_id, warehouse_id, item_id, batch_no, received_date, expiry_date, quantity,
          reserved_quantity, unit_cost, note, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, current_timestamp)
        on duplicate key update
          received_date = least(received_date, values(received_date)),
          expiry_date = coalesce(values(expiry_date), expiry_date),
          quantity = quantity + values(quantity),
          unit_cost = values(unit_cost),
          note = values(note),
          updated_at = current_timestamp
        """,
        tenantId,
        warehouseId,
        request.itemId(),
        request.batchNo().trim(),
        request.receivedDate(),
        blankToNull(request.expiryDate()),
        amount(request.quantity()),
        amount(request.unitCost()),
        blankToNull(request.note())
    );
  }

  public Optional<Long> batchId(long tenantId, long itemId, String batchNo) {
    if (hasWarehouseColumn("warehouse_stock_batch")) {
      return batchId(tenantId, centralWarehouseId(tenantId), itemId, batchNo);
    }
    if (batchNo == null || batchNo.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select id from warehouse_stock_batch
        where tenant_id = ? and item_id = ? and batch_no = ? limit 1
        """, (rs, rowNum) -> rs.getLong("id"), tenantId, itemId, batchNo.trim()).stream().findFirst();
  }

  public Optional<Long> batchId(long tenantId, long warehouseId, long itemId, String batchNo) {
    if (batchNo == null || batchNo.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select id
        from warehouse_stock_batch
        where tenant_id = ? and warehouse_id = ? and item_id = ? and batch_no = ?
        limit 1
        """,
        (rs, rowNum) -> rs.getLong("id"),
        tenantId,
        warehouseId,
        itemId,
        batchNo.trim()
    ).stream().findFirst();
  }

  public void insertMovement(
      long tenantId,
      long itemId,
      Long batchId,
      String movementType,
      BigDecimal quantityDelta,
      String sourceType,
      String sourceId,
      String storeId,
      String note,
      Long operatorId
  ) {
    if (hasWarehouseColumn("warehouse_stock_movement")) {
      insertMovement(tenantId, centralWarehouseId(tenantId), itemId, batchId, movementType,
          quantityDelta, sourceType, sourceId, storeId, note, operatorId);
      return;
    }
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(
          tenant_id, item_id, batch_id, movement_type, quantity_delta,
          source_type, source_id, store_id, note, operator_id, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, itemId, batchId, movementType, amount(quantityDelta), sourceType,
        sourceId, storeId, note, operatorId);
  }

  public void insertMovement(
      long tenantId,
      Long warehouseId,
      long itemId,
      Long batchId,
      String movementType,
      BigDecimal quantityDelta,
      String sourceType,
      String sourceId,
      String storeId,
      String note,
      Long operatorId
  ) {
    long resolvedWarehouseId = warehouseId == null ? centralWarehouseId(tenantId) : warehouseId;
    jdbcTemplate.update("""
        insert into warehouse_stock_movement(
          tenant_id, warehouse_id, item_id, batch_id, movement_type, quantity_delta,
          source_type, source_id, store_id, note, operator_id, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        resolvedWarehouseId,
        itemId,
        batchId,
        movementType,
        quantityDelta.setScale(2, RoundingMode.HALF_UP),
        sourceType,
        sourceId,
        storeId,
        note,
        operatorId
    );
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        storeId
    );
    return count != null && count > 0;
  }

  public Optional<String> storeName(long tenantId, String storeId) {
    return jdbcTemplate.query("""
        select name
        from store_branch
        where tenant_id = ? and id = ?
        limit 1
        """, (rs, rowNum) -> rs.getString("name"), tenantId, storeId).stream().findFirst();
  }

  public void insertRequisition(long tenantId, String id, String storeId, BigDecimal total, String note, Long submittedBy) {
    if (hasColumn("store_requisition", "supply_warehouse_id")) {
      insertRequisition(tenantId, id, storeId, centralWarehouseId(tenantId), total, note, submittedBy, null);
      return;
    }
    jdbcTemplate.update("""
        insert into store_requisition(
          id, tenant_id, store_id, status, total_amount, note, submitted_by, submitted_at
        ) values (?, ?, ?, 'SUBMITTED', ?, ?, ?, current_timestamp)
        """, id, tenantId, storeId, amount(total), blankToNull(note), submittedBy);
  }

  public void insertRequisition(
      long tenantId, String id, String storeId, long supplyWarehouseId, BigDecimal total,
      String note, Long submittedBy, String idempotencyKey
  ) {
    jdbcTemplate.update("""
        insert into store_requisition(
          id, tenant_id, store_id, supply_warehouse_id, status, total_amount, note,
          submitted_by, submitted_at, idempotency_key, version
        )
        values (?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, current_timestamp, ?, 0)
        """,
        id,
        tenantId,
        storeId,
        supplyWarehouseId,
        total.setScale(2, RoundingMode.HALF_UP),
        blankToNull(note),
        submittedBy,
        blankToNull(idempotencyKey)
    );
  }

  public Optional<WarehouseRequisitionResponse> requisitionForUpdate(long tenantId, String requisitionId) {
    List<String> ids = jdbcTemplate.query(
        "select id from store_requisition where tenant_id = ? and id = ? for update",
        (rs, rowNum) -> rs.getString("id"),
        tenantId,
        requisitionId
    );
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    return requisition(tenantId, requisitionId);
  }

  public void insertRequisitionLine(
      long tenantId,
      String requisitionId,
      long itemId,
      BigDecimal requestedQuantity,
      BigDecimal unitPrice,
      String warningText,
      String note
  ) {
    BigDecimal requested = amount(requestedQuantity);
    BigDecimal price = amount(unitPrice);
    jdbcTemplate.update("""
        insert into store_requisition_line(
          tenant_id, requisition_id, item_id, requested_quantity, shipped_quantity,
          unit_price, amount, warning_text, note
        )
        values (?, ?, ?, ?, 0, ?, ?, ?, ?)
        """,
        tenantId,
        requisitionId,
        itemId,
        requested,
        price,
        requested.multiply(price).setScale(2, RoundingMode.HALF_UP),
        blankToNull(warningText),
        blankToNull(note)
    );
  }

  public List<WarehouseRequisitionResponse> requisitions(long tenantId, String storeId) {
    return requisitions(tenantId, storeId, null);
  }

  public List<WarehouseRequisitionResponse> requisitions(long tenantId, String storeId, Long warehouseId) {
    boolean facilityAware = hasColumn("store_requisition", "supply_warehouse_id");
    String sql = """
        select r.id, r.store_id, s.name as store_name, r.status, r.total_amount, r.note,
        """ + (facilityAware
            ? " r.supply_warehouse_id as warehouse_id, facility.name as warehouse_name,\n"
            : " cast(null as bigint) as warehouse_id, cast(null as varchar(160)) as warehouse_name,\n") + """
               sub.display_name as submitted_by, rev.display_name as reviewed_by, ship.display_name as shipped_by,
               rec.display_name as received_by, r.submitted_at, r.reviewed_at, r.shipped_at, r.received_at
        from store_requisition r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        """ + (facilityAware
            ? " left join warehouse_facility facility on facility.tenant_id = r.tenant_id and facility.id = r.supply_warehouse_id\n"
            : "") + """
        left join auth_user sub on sub.tenant_id = r.tenant_id and sub.id = r.submitted_by
        left join auth_user rev on rev.tenant_id = r.tenant_id and rev.id = r.reviewed_by
        left join auth_user ship on ship.tenant_id = r.tenant_id and ship.id = r.shipped_by
        left join auth_user rec on rec.tenant_id = r.tenant_id and rec.id = r.received_by
        where r.tenant_id = ?
          and (? is null or r.store_id = ?)
        """ + (warehouseId == null ? "" : " and r.supply_warehouse_id = ?\n") + """
        order by r.submitted_at desc
        limit 80
        """;
    List<WarehouseRequisitionHeaderRow> headers = warehouseId == null
        ? jdbcTemplate.query(sql, this::mapHeader, tenantId, blankToNull(storeId), blankToNull(storeId))
        : jdbcTemplate.query(sql, this::mapHeader, tenantId, blankToNull(storeId), blankToNull(storeId), warehouseId);
    ArrayList<WarehouseRequisitionResponse> rows = new ArrayList<>();
    for (WarehouseRequisitionHeaderRow header : headers) {
      rows.add(requisitionResponse(tenantId, header));
    }
    return rows;
  }

  public Optional<WarehouseRequisitionResponse> requisition(long tenantId, String requisitionId) {
    boolean facilityAware = hasColumn("store_requisition", "supply_warehouse_id");
    String sql = """
        select r.id, r.store_id, s.name as store_name, r.status, r.total_amount, r.note,
        """ + (facilityAware
            ? " r.supply_warehouse_id as warehouse_id, facility.name as warehouse_name,\n"
            : " cast(null as bigint) as warehouse_id, cast(null as varchar(160)) as warehouse_name,\n") + """
               sub.display_name as submitted_by, rev.display_name as reviewed_by, ship.display_name as shipped_by,
               rec.display_name as received_by, r.submitted_at, r.reviewed_at, r.shipped_at, r.received_at
        from store_requisition r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        """ + (facilityAware
            ? " left join warehouse_facility facility on facility.tenant_id = r.tenant_id and facility.id = r.supply_warehouse_id\n"
            : "") + """
        left join auth_user sub on sub.tenant_id = r.tenant_id and sub.id = r.submitted_by
        left join auth_user rev on rev.tenant_id = r.tenant_id and rev.id = r.reviewed_by
        left join auth_user ship on ship.tenant_id = r.tenant_id and ship.id = r.shipped_by
        left join auth_user rec on rec.tenant_id = r.tenant_id and rec.id = r.received_by
        where r.tenant_id = ? and r.id = ?
        limit 1
        """;
    return jdbcTemplate.query(sql, this::mapHeader, tenantId, requisitionId).stream()
        .findFirst()
        .map(header -> requisitionResponse(tenantId, header));
  }

  private WarehouseRequisitionResponse requisitionResponse(long tenantId, WarehouseRequisitionHeaderRow header) {
    return new WarehouseRequisitionResponse(
        header.id(),
        header.storeId(),
        header.storeName(),
        header.warehouseId(),
        header.warehouseName(),
        header.status(),
        statusLabel(header.status()),
        header.totalAmount(),
        header.note(),
        header.submittedBy(),
        header.reviewedBy(),
        header.shippedBy(),
        header.receivedBy(),
        header.submittedAt(),
        header.reviewedAt(),
        header.shippedAt(),
        header.receivedAt(),
        requisitionLines(tenantId, header.id())
    );
  }

  public void reviewRequisition(
      long tenantId,
      String requisitionId,
      boolean approved,
      BigDecimal totalAmount,
      Long reviewedBy,
      String note
  ) {
    jdbcTemplate.update("""
        update store_requisition
        set status = ?,
            total_amount = ?,
            reviewed_by = ?,
            reviewed_at = current_timestamp,
            note = coalesce(?, note),
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        approved ? "APPROVED" : "REJECTED",
        totalAmount.setScale(2, RoundingMode.HALF_UP),
        reviewedBy,
        blankToNull(note),
        tenantId,
        requisitionId
    );
  }

  public void updateApprovedQuantity(long tenantId, String requisitionId, long itemId, BigDecimal quantity) {
    BigDecimal approved = amount(quantity);
    jdbcTemplate.update("""
        update store_requisition_line
        set approved_quantity = ?,
            amount = ? * unit_price
        where tenant_id = ? and requisition_id = ? and item_id = ?
        """,
        approved,
        approved,
        tenantId,
        requisitionId,
        itemId
    );
  }

  public void markShipped(long tenantId, String requisitionId, Long shippedBy) {
    jdbcTemplate.update("""
        update store_requisition
        set status = 'SHIPPED',
            shipped_by = ?,
            shipped_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, shippedBy, tenantId, requisitionId);
  }

  public void markRequisitionReceived(long tenantId, String requisitionId, Long receivedBy, String note) {
    jdbcTemplate.update("""
        update store_requisition
        set status = 'RECEIVED',
            received_by = ?,
            received_at = current_timestamp,
            received_note = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, receivedBy, blankToNull(note), tenantId, requisitionId);
  }

  public void updateShippedQuantity(long tenantId, String requisitionId, long itemId, BigDecimal quantity) {
    jdbcTemplate.update("""
        update store_requisition_line
        set shipped_quantity = ?
        where tenant_id = ? and requisition_id = ? and item_id = ?
        """, amount(quantity), tenantId, requisitionId, itemId);
  }

  public List<WarehouseStockBatchRow> positiveBatches(long tenantId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, expiry_date, quantity from warehouse_stock_batch
        where tenant_id = ? and item_id = ? and quantity > 0
        order by received_date asc, created_at asc, id asc
        """, this::mapBatch, tenantId, itemId);
  }

  public Optional<Long> requisitionWarehouseId(long tenantId, String requisitionId) {
    return jdbcTemplate.query("""
        select supply_warehouse_id from store_requisition
        where tenant_id = ? and id = ? limit 1
        """, (rs, rowNum) -> rs.getLong(1), tenantId, requisitionId).stream().findFirst();
  }

  /**
   * Resolves the immutable receiving-warehouse source for a return order. The
   * source is always the originating requisition's supply warehouse, never a
   * client-provided department or a fallback central warehouse.
   */
  public Optional<WarehouseFacilitySnapshot> receiveWarehouseForRequisition(
      long tenantId,
      String requisitionId
  ) {
    return jdbcTemplate.query("""
        select facility.id as warehouse_id, facility.code as warehouse_code,
               facility.name as warehouse_name
        from store_requisition requisition
        join warehouse_facility facility
          on facility.tenant_id = requisition.tenant_id
         and facility.id = requisition.supply_warehouse_id
        where requisition.tenant_id = ? and requisition.id = ?
        limit 1
        """, (rs, rowNum) -> new WarehouseFacilitySnapshot(
            rs.getLong("warehouse_id"),
            rs.getString("warehouse_code"),
            rs.getString("warehouse_name")
        ), tenantId, requisitionId).stream().findFirst();
  }

  public List<WarehouseStockBatchRow> positiveBatches(long tenantId, long warehouseId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, expiry_date, quantity
        from warehouse_stock_batch
        where tenant_id = ? and warehouse_id = ? and item_id = ? and quantity > 0
        order by received_date asc, created_at asc, id asc
        """, this::mapBatch, tenantId, warehouseId, itemId);
  }

  public List<WarehouseStockBatchRow> positiveBatchesForUpdate(long tenantId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, expiry_date, quantity from warehouse_stock_batch
        where tenant_id = ? and item_id = ? and quantity > 0
        order by received_date asc, created_at asc, id asc for update
        """, this::mapBatch, tenantId, itemId);
  }

  public List<WarehouseStockBatchRow> positiveBatchesForUpdate(long tenantId, long warehouseId, long itemId) {
    return jdbcTemplate.query("""
        select id, item_id, batch_no, expiry_date, quantity
        from warehouse_stock_batch
        where tenant_id = ? and warehouse_id = ? and item_id = ? and quantity > 0
        order by received_date asc, created_at asc, id asc
        for update
        """, this::mapBatch, tenantId, warehouseId, itemId);
  }

  public boolean reserveRequest(long tenantId, String requestType, String requestKey) {
    try {
      jdbcTemplate.update("""
          insert into warehouse_request_dedup(tenant_id, request_type, request_key, created_at)
          values (?, ?, ?, current_timestamp)
          """, tenantId, requestType, requestKey);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  public void completeReservedRequest(long tenantId, String requestType, String requestKey, String businessId) {
    jdbcTemplate.update("""
        update warehouse_request_dedup
        set business_id = ?
        where tenant_id = ? and request_type = ? and request_key = ?
        """, blankToNull(businessId), tenantId, requestType, requestKey);
  }

  public Optional<String> reservedRequestBusinessId(long tenantId, String requestType, String requestKey) {
    return jdbcTemplate.query("""
        select business_id
        from warehouse_request_dedup
        where tenant_id = ? and request_type = ? and request_key = ?
        limit 1
        """, (rs, rowNum) -> rs.getString("business_id"), tenantId, requestType, requestKey)
        .stream()
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  public List<WarehouseStockBatchResponse> stockBatches(long tenantId) {
    boolean facilityAware = hasWarehouseColumn("warehouse_stock_batch");
    String sql = """
        select b.id, b.item_id, i.name as item_name, i.unit, b.batch_no,
               b.received_date, b.expiry_date, b.quantity, b.unit_cost, b.note, b.created_at,
        """ + (facilityAware
            ? " b.warehouse_id, facility.name as warehouse_name, coalesce(inv.expiry_alert_days, i.expiry_alert_days, 3) as expiry_alert_days\n"
            : " cast(null as bigint) as warehouse_id, cast(null as varchar(160)) as warehouse_name, coalesce(i.expiry_alert_days, 3) as expiry_alert_days\n") + """
        from warehouse_stock_batch b
        join warehouse_item i on i.tenant_id = b.tenant_id and i.id = b.item_id
        """ + (facilityAware
            ? " join warehouse_facility facility on facility.tenant_id = b.tenant_id and facility.id = b.warehouse_id\n"
                + " left join warehouse_inventory inv on inv.tenant_id = b.tenant_id and inv.warehouse_id = b.warehouse_id and inv.item_id = b.item_id\n"
            : "") + """
        where b.tenant_id = ?
        order by i.sort_order, i.name, b.received_date asc, b.created_at asc, b.id asc
        """;
    return jdbcTemplate.query(sql, this::mapStockBatch, tenantId);
  }

  public List<WarehouseStockBatchResponse> stockBatches(long tenantId, long warehouseId) {
    return jdbcTemplate.query("""
        select b.id, b.item_id, i.name as item_name, i.unit, b.batch_no,
               b.received_date, b.expiry_date, b.quantity, b.unit_cost, b.note, b.created_at,
               b.warehouse_id, facility.name as warehouse_name,
               coalesce(inv.expiry_alert_days, i.expiry_alert_days, 3) as expiry_alert_days
        from warehouse_stock_batch b
        join warehouse_item i on i.tenant_id = b.tenant_id and i.id = b.item_id
        join warehouse_facility facility
          on facility.tenant_id = b.tenant_id and facility.id = b.warehouse_id
        left join warehouse_inventory inv
          on inv.tenant_id = b.tenant_id and inv.warehouse_id = b.warehouse_id
         and inv.item_id = b.item_id
        where b.tenant_id = ? and b.warehouse_id = ?
        order by i.sort_order, i.name, b.received_date asc, b.created_at asc, b.id asc
        """, this::mapStockBatch, tenantId, warehouseId);
  }

  public void updateBatchQuantity(long tenantId, long batchId, BigDecimal quantity) {
    jdbcTemplate.update("""
        update warehouse_stock_batch
        set quantity = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, amount(quantity), tenantId, batchId);
  }

  public int pendingRequisitionCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from store_requisition
        where tenant_id = ? and status in ('SUBMITTED', 'APPROVED')
        """, Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public int pendingRequisitionCount(long tenantId, Long warehouseId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from store_requisition
        where tenant_id = ? and status in ('SUBMITTED', 'APPROVED')
          and (? is null or supply_warehouse_id = ?)
        """, Integer.class, tenantId, warehouseId, warehouseId);
    return count == null ? 0 : count;
  }

  public int pendingReceiptCount(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_delivery_order
        where tenant_id = ? and status = 'SHIPPED' and (? is null or store_id = ?)
        """, Integer.class, tenantId, blankToNull(storeId), blankToNull(storeId));
    return count == null ? 0 : count;
  }

  public int pendingPurchaseCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_purchase_order
        where tenant_id = ? and status in ('DRAFT', 'ORDERED')
        """, Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public int pendingPurchaseCount(long tenantId, Long warehouseId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_purchase_order
        where tenant_id = ? and status in ('DRAFT', 'ORDERED')
          and (? is null or warehouse_id = ?)
        """, Integer.class, tenantId, warehouseId, warehouseId);
    return count == null ? 0 : count;
  }

  public void insertReturnOrder(
      long tenantId,
      WarehouseFacilitySnapshot receiveWarehouse,
      String id,
      String returnNo,
      String sourceRequisitionId,
      String sourceDeliveryId,
      String returnStoreId,
      String returnStoreName,
      String status,
      BigDecimal totalAmount,
      String handledBy,
      String operatorName,
      String reason,
      String note,
      String returnDate
  ) {
    if (receiveWarehouse == null
        || receiveWarehouse.id() <= 0
        || receiveWarehouse.code() == null
        || receiveWarehouse.code().isBlank()
        || receiveWarehouse.name() == null
        || receiveWarehouse.name().isBlank()) {
      throw new IllegalArgumentException("配送退货单必须提供来源叫货单的有效收货仓快照");
    }
    jdbcTemplate.update("""
        insert into warehouse_return_order(
          id, tenant_id, warehouse_id, receive_warehouse_code_snapshot,
          receive_warehouse_name_snapshot, return_no, source_requisition_id, source_delivery_id,
          return_store_id, return_store_name, receive_department, status,
          total_amount, handled_by, created_by, updated_by, reviewed_by,
          checked_by, reason, note, return_date, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        id,
        tenantId,
        receiveWarehouse.id(),
        receiveWarehouse.code().trim(),
        receiveWarehouse.name().trim(),
        returnNo,
        blankToNull(sourceRequisitionId),
        blankToNull(sourceDeliveryId),
        returnStoreId,
        returnStoreName,
        receiveWarehouse.name().trim(),
        status,
        amount(totalAmount),
        blankToNull(handledBy),
        blankToNull(operatorName),
        null,
        null,
        null,
        blankToNull(reason),
        blankToNull(note),
        returnDate
    );
  }

  public void insertReturnOrderLine(
      long tenantId,
      String returnOrderId,
      Long sourceRequisitionLineId,
      long itemId,
      String itemName,
      String spec,
      Long batchId,
      String batchNo,
      BigDecimal quantity,
      String unit,
      BigDecimal unitPrice,
      BigDecimal returnPrice,
      String reason,
      String note
  ) {
    BigDecimal safeQuantity = amount(quantity);
    BigDecimal safeUnitPrice = amount(unitPrice);
    BigDecimal safeReturnPrice = amount(returnPrice);
    jdbcTemplate.update("""
        insert into warehouse_return_order_line(
          tenant_id, return_order_id, source_requisition_line_id,
          item_id, item_name, spec, batch_id, batch_no,
          quantity, unit, unit_price, return_price, amount, reason, note
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tenantId,
        returnOrderId,
        sourceRequisitionLineId,
        itemId,
        itemName,
        blankToNull(spec),
        batchId,
        blankToNull(batchNo),
        safeQuantity,
        unit == null || unit.isBlank() ? "件" : unit,
        safeUnitPrice,
        safeReturnPrice,
        safeQuantity.multiply(safeReturnPrice).setScale(2, RoundingMode.HALF_UP),
        blankToNull(reason),
        blankToNull(note)
    );
  }

  public List<WarehouseReturnResponse> returns(long tenantId, String storeId) {
    return returns(tenantId, storeId, null);
  }

  public List<WarehouseReturnResponse> returns(long tenantId, String storeId, Long warehouseId) {
    String sql = """
        select o.id, o.return_no, o.source_requisition_id, o.source_delivery_id,
               o.return_store_id, o.return_store_name,
               o.warehouse_id as receive_warehouse_id,
               o.receive_warehouse_name_snapshot as receive_warehouse_name,
               o.receive_department, o.status, o.total_amount, o.handled_by,
               o.created_by, o.updated_by, o.reviewed_by, o.checked_by,
               o.reason, o.note, o.review_note, o.received_note,
               o.return_date, o.reviewed_at, o.received_at, o.created_at, o.updated_at,
               (select count(*) from warehouse_return_order_line l where l.tenant_id = o.tenant_id and l.return_order_id = o.id) as line_count,
               (select count(*) from warehouse_attachment a where a.tenant_id = o.tenant_id and a.business_type in ('RETURN_ORDER', 'WAREHOUSE_RETURN') and a.business_id = o.id) as attachment_count
        from warehouse_return_order o
        where o.tenant_id = ?
          and (? is null or o.return_store_id = ?)
        """ + (warehouseId == null ? "" : " and o.warehouse_id = ?\n") + """
        order by o.return_date desc, o.created_at desc
        limit 120
        """;
    List<WarehouseReturnHeaderRow> headers = warehouseId == null
        ? jdbcTemplate.query(sql, this::mapReturnHeader, tenantId, blankToNull(storeId), blankToNull(storeId))
        : jdbcTemplate.query(sql, this::mapReturnHeader, tenantId, blankToNull(storeId), blankToNull(storeId), warehouseId);
    ArrayList<WarehouseReturnResponse> rows = new ArrayList<>();
    for (WarehouseReturnHeaderRow header : headers) {
      rows.add(new WarehouseReturnResponse(
          header.id(),
          header.returnNo(),
          header.sourceRequisitionId(),
          header.sourceDeliveryId(),
          header.returnStoreId(),
          header.returnStoreName(),
          header.receiveWarehouseId(),
          header.receiveWarehouseName(),
          header.receiveDepartment(),
          header.status(),
          returnStatusLabel(header.status()),
          header.totalAmount(),
          header.handledBy(),
          header.createdBy(),
          header.updatedBy(),
          header.reviewedBy(),
          header.checkedBy(),
          header.reason(),
          header.note(),
          header.reviewNote(),
          header.receivedNote(),
          header.returnDate(),
          header.reviewedAt(),
          header.receivedAt(),
          header.createdAt(),
          header.updatedAt(),
          header.lineCount(),
          header.attachmentCount(),
          returnLines(tenantId, header.id())
      ));
    }
    return rows;
  }

  public Optional<WarehouseReturnResponse> returnOrder(long tenantId, String returnOrderId) {
    return returns(tenantId, null).stream()
        .filter(row -> row.id().equals(returnOrderId) || row.returnNo().equals(returnOrderId))
        .findFirst();
  }

  public List<ReturnSourceMovementRow> returnSourceMovements(long tenantId, String requisitionId, long itemId) {
    return jdbcTemplate.query("""
        select l.id as source_requisition_line_id,
               m.item_id, i.name as item_name, i.spec, m.batch_id, b.batch_no,
               abs(m.quantity_delta) as shipped_quantity,
               i.unit, coalesce(l.unit_price, i.unit_price, 0) as unit_price
        from warehouse_stock_movement m
        join warehouse_item i on i.tenant_id = m.tenant_id and i.id = m.item_id
        left join warehouse_stock_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id
        left join store_requisition_line l on l.tenant_id = m.tenant_id
          and l.requisition_id = m.source_id
          and l.item_id = m.item_id
        where m.tenant_id = ?
          and m.source_type = 'REQUISITION'
          and m.source_id = ?
          and m.item_id = ?
          and m.movement_type = 'OUT'
        order by m.created_at, m.id
        """, this::mapReturnSourceMovement, tenantId, requisitionId, itemId);
  }

  public BigDecimal returnedQuantityForRequisitionItem(long tenantId, String requisitionId, long itemId) {
    BigDecimal value = jdbcTemplate.queryForObject("""
        select coalesce(sum(l.quantity), 0)
        from warehouse_return_order_line l
        join warehouse_return_order o on o.tenant_id = l.tenant_id and o.id = l.return_order_id
        where o.tenant_id = ?
          and o.source_requisition_id = ?
          and l.item_id = ?
          and o.status not in ('REJECTED', 'CANCELLED')
        """, BigDecimal.class, tenantId, requisitionId, itemId);
    return amount(value);
  }

  public BigDecimal receivedQuantityForRequisitionItem(long tenantId, String requisitionId, long itemId) {
    BigDecimal value = jdbcTemplate.queryForObject("""
        select coalesce(sum(dl.received_quantity), 0)
        from warehouse_delivery_order d
        join warehouse_delivery_order_line dl on dl.tenant_id = d.tenant_id and dl.delivery_id = d.id
        where d.tenant_id = ?
          and d.requisition_id = ?
          and dl.item_id = ?
        """, BigDecimal.class, tenantId, requisitionId, itemId);
    return amount(value);
  }

  public void reviewReturnOrder(long tenantId, String returnOrderId, boolean approved, String reviewedBy, String note) {
    jdbcTemplate.update("""
        update warehouse_return_order
        set status = ?,
            reviewed_by = ?,
            review_note = ?,
            reviewed_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'SUBMITTED'
        """,
        approved ? "APPROVED" : "REJECTED",
        blankToNull(reviewedBy),
        blankToNull(note),
        tenantId,
        returnOrderId
    );
  }

  public void receiveReturnOrder(long tenantId, String returnOrderId, String checkedBy, String note) {
    jdbcTemplate.update("""
        update warehouse_return_order
        set status = 'RECEIVED',
            checked_by = ?,
            received_note = ?,
            received_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'APPROVED'
        """,
        blankToNull(checkedBy),
        blankToNull(note),
        tenantId,
        returnOrderId
    );
  }

  public void addBatchQuantity(long tenantId, Long batchId, BigDecimal quantity) {
    if (batchId == null) {
      return;
    }
    jdbcTemplate.update("""
        update warehouse_stock_batch
        set quantity = quantity + ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, amount(quantity), tenantId, batchId);
  }

  public void insertWarehouseAttachment(
      long tenantId,
      String storeId,
      String businessType,
      String businessId,
      String fileName,
      String contentType,
      long fileSize,
      byte[] content,
      Long uploadedBy
  ) {
    jdbcTemplate.update("""
        insert into warehouse_attachment(
          tenant_id, store_id, business_type, business_id, file_name, content_type,
          file_size, storage_path, content, uploaded_by, uploaded_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        blankToNull(storeId),
        businessType,
        businessId,
        fileName,
        blankToNull(contentType),
        fileSize,
        "mysql://warehouse_attachment/" + businessId + "/" + fileName,
        content,
        uploadedBy
    );
  }

  public void logAction(long tenantId, Long operatorId, String operatorName, String action, String targetId, String storeId, String reason) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, reason, created_at)
        values (?, ?, ?, ?, 'warehouse', ?, ?, ?, current_timestamp)
        """, tenantId, operatorId, operatorName, action, targetId, storeId, reason);
  }

  public void insertTodoAction(
      String id,
      long tenantId,
      String todoId,
      String actionType,
      String note,
      Long actorUserId,
      String actorName,
      String actorRole
  ) {
    jdbcTemplate.update("""
        insert into todo_action(
          id, tenant_id, todo_id, action_type, status, note,
          actor_user_id, actor_name, actor_role, created_at
        )
        values (?, ?, ?, ?, 'DONE', ?, ?, ?, ?, current_timestamp)
        """,
        id,
        tenantId,
        todoId,
        actionType,
        note,
        actorUserId,
        actorName,
        actorRole
    );
  }

  public List<WarehouseSupplierResponse> suppliers(long tenantId) {
    return jdbcTemplate.query("""
        select id, name, contact_name, phone, settlement_cycle, active
        from warehouse_supplier
        where tenant_id = ?
        order by active desc, name
        """, (rs, rowNum) -> new WarehouseSupplierResponse(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("contact_name"),
        rs.getString("phone"),
        rs.getString("settlement_cycle"),
        rs.getBoolean("active")
    ), tenantId);
  }

  public void insertPurchaseOrder(long tenantId, String id, Long supplierId, BigDecimal totalAmount, String note, Long createdBy) {
    if (hasWarehouseColumn("warehouse_purchase_order")) {
      insertPurchaseOrder(tenantId, centralWarehouseId(tenantId), id, supplierId, totalAmount,
          note, createdBy, null);
      return;
    }
    jdbcTemplate.update("""
        insert into warehouse_purchase_order(
          id, tenant_id, supplier_id, status, total_amount, note, created_by, created_at
        ) values (?, ?, ?, 'ORDERED', ?, ?, ?, current_timestamp)
        """, id, tenantId, supplierId, amount(totalAmount), blankToNull(note), createdBy);
  }

  public boolean activeSupplierExists(long tenantId, Long supplierId) {
    if (supplierId == null) {
      return true;
    }
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from warehouse_supplier
        where tenant_id = ? and id = ? and active = 1
        """, Integer.class, tenantId, supplierId);
    return count != null && count > 0;
  }

  public boolean insertPurchaseOrder(
      long tenantId, long warehouseId, String id, Long supplierId, BigDecimal totalAmount,
      String note, Long createdBy, String idempotencyKey
  ) {
    try {
      jdbcTemplate.update("""
        insert into warehouse_purchase_order(
          id, tenant_id, warehouse_id, idempotency_key, supplier_id, status,
          total_amount, note, created_by, created_at
        )
        values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, current_timestamp)
        """, id, tenantId, warehouseId, blankToNull(idempotencyKey), supplierId,
          amount(totalAmount), blankToNull(note), createdBy);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  public Optional<String> purchaseOrderIdByRequestKey(long tenantId, String requestKey) {
    if (requestKey == null || requestKey.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select id from warehouse_purchase_order
        where tenant_id = ? and idempotency_key = ? limit 1
        """, (rs, rowNum) -> rs.getString(1), tenantId, requestKey.trim()).stream().findFirst();
  }

  public void insertPurchaseOrderLine(long tenantId, String purchaseOrderId, long itemId, BigDecimal orderedQuantity, BigDecimal unitCost, String note) {
    BigDecimal quantity = amount(orderedQuantity);
    BigDecimal cost = amount(unitCost);
    jdbcTemplate.update("""
        insert into warehouse_purchase_order_line(
          tenant_id, purchase_order_id, item_id, ordered_quantity, unit_cost, amount, note
        )
        values (?, ?, ?, ?, ?, ?, ?)
        """, tenantId, purchaseOrderId, itemId, quantity, cost, quantity.multiply(cost).setScale(2, RoundingMode.HALF_UP), blankToNull(note));
  }

  public List<WarehousePurchaseOrderResponse> purchaseOrders(long tenantId) {
    return purchaseOrders(tenantId, null);
  }

  public List<WarehousePurchaseOrderResponse> purchaseOrders(long tenantId, Long warehouseId) {
    String sql = """
        select po.id, po.supplier_id, s.name as supplier_name, po.status, po.total_amount, po.note,
               creator.display_name as created_by, receiver.display_name as received_by, po.created_at, po.received_at
        from warehouse_purchase_order po
        left join warehouse_supplier s on s.tenant_id = po.tenant_id and s.id = po.supplier_id
        left join auth_user creator on creator.tenant_id = po.tenant_id and creator.id = po.created_by
        left join auth_user receiver on receiver.tenant_id = po.tenant_id and receiver.id = po.received_by
        where po.tenant_id = ?
        """ + (warehouseId == null ? "" : " and po.warehouse_id = ?\n") + """
        order by po.created_at desc
        limit 50
        """;
    List<WarehousePurchaseOrderHeaderRow> headers = warehouseId == null
        ? jdbcTemplate.query(sql, this::mapPurchaseHeader, tenantId)
        : jdbcTemplate.query(sql, this::mapPurchaseHeader, tenantId, warehouseId);
    ArrayList<WarehousePurchaseOrderResponse> rows = new ArrayList<>();
    for (WarehousePurchaseOrderHeaderRow header : headers) {
      rows.add(new WarehousePurchaseOrderResponse(
          header.id(),
          header.supplierId(),
          header.supplierName(),
          header.status(),
          purchaseStatusLabel(header.status()),
          header.totalAmount(),
          header.note(),
          header.createdBy(),
          header.receivedBy(),
          header.createdAt(),
          header.receivedAt(),
          purchaseOrderLines(tenantId, header.id())
      ));
    }
    return rows;
  }

  public Optional<WarehousePurchaseOrderResponse> purchaseOrder(long tenantId, String id) {
    return jdbcTemplate.query("""
        select po.id, po.supplier_id, s.name as supplier_name, po.status, po.total_amount, po.note,
               creator.display_name as created_by, receiver.display_name as received_by,
               po.created_at, po.received_at
        from warehouse_purchase_order po
        left join warehouse_supplier s
          on s.tenant_id = po.tenant_id and s.id = po.supplier_id
        left join auth_user creator
          on creator.tenant_id = po.tenant_id and creator.id = po.created_by
        left join auth_user receiver
          on receiver.tenant_id = po.tenant_id and receiver.id = po.received_by
        where po.tenant_id = ? and po.id = ?
        limit 1
        """, this::mapPurchaseHeader, tenantId, id).stream().findFirst()
        .map(header -> new WarehousePurchaseOrderResponse(
            header.id(), header.supplierId(), header.supplierName(), header.status(),
            purchaseStatusLabel(header.status()), header.totalAmount(), header.note(),
            header.createdBy(), header.receivedBy(), header.createdAt(), header.receivedAt(),
            purchaseOrderLines(tenantId, header.id())));
  }

  public void insertDelivery(long tenantId, String id, String requisitionId, String storeId, Long shippedBy, String note) {
    if (hasWarehouseColumn("warehouse_delivery_order")) {
      insertDelivery(tenantId, centralWarehouseId(tenantId), id, requisitionId, storeId, shippedBy, note);
      return;
    }
    jdbcTemplate.update("""
        insert into warehouse_delivery_order(
          id, tenant_id, requisition_id, store_id, status, shipped_by, shipped_at, note
        ) values (?, ?, ?, ?, 'SHIPPED', ?, current_timestamp, ?)
        """, id, tenantId, requisitionId, storeId, shippedBy, blankToNull(note));
  }

  public Optional<PurchaseLockRow> purchaseOrderForUpdate(long tenantId, String id) {
    return jdbcTemplate.query("""
        select id, warehouse_id, status from warehouse_purchase_order
        where tenant_id = ? and id = ? for update
        """, (rs, rowNum) -> new PurchaseLockRow(
        rs.getString("id"), rs.getLong("warehouse_id"), rs.getString("status")), tenantId, id)
        .stream().findFirst();
  }

  public int markPurchaseOrdered(long tenantId, String id) {
    return jdbcTemplate.update("""
        update warehouse_purchase_order set status = 'ORDERED', updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'DRAFT'
        """, tenantId, id);
  }

  public int markPurchaseReceived(long tenantId, String id, Long userId) {
    return jdbcTemplate.update("""
        update warehouse_purchase_order
        set status = 'RECEIVED', received_by = ?, received_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'ORDERED'
        """, userId, tenantId, id);
  }

  public int setPurchaseLineReceived(
      long tenantId, String purchaseOrderId, long itemId, BigDecimal quantity
  ) {
    return jdbcTemplate.update("""
        update warehouse_purchase_order_line
        set received_quantity = ?
        where tenant_id = ? and purchase_order_id = ? and item_id = ?
          and ordered_quantity >= ?
        """, amount(quantity), tenantId, purchaseOrderId, itemId, amount(quantity));
  }

  public void insertDelivery(
      long tenantId, long warehouseId, String id, String requisitionId, String storeId,
      Long shippedBy, String note
  ) {
    jdbcTemplate.update("""
        insert into warehouse_delivery_order(
          id, tenant_id, warehouse_id, requisition_id, store_id, status, shipped_by, shipped_at, note
        )
        values (?, ?, ?, ?, ?, 'SHIPPED', ?, current_timestamp, ?)
        """, id, tenantId, warehouseId, requisitionId, storeId, shippedBy, blankToNull(note));
  }

  public void insertDeliveryLine(long tenantId, String deliveryId, Long requisitionLineId, long itemId, BigDecimal shippedQuantity, BigDecimal unitPrice) {
    BigDecimal quantity = amount(shippedQuantity);
    BigDecimal price = amount(unitPrice);
    jdbcTemplate.update("""
        insert into warehouse_delivery_order_line(
          tenant_id, delivery_id, requisition_line_id, item_id, shipped_quantity, unit_price, amount
        )
        values (?, ?, ?, ?, ?, ?, ?)
        """, tenantId, deliveryId, requisitionLineId, itemId, quantity, price, quantity.multiply(price).setScale(2, RoundingMode.HALF_UP));
  }

  public List<WarehouseDeliveryResponse> deliveries(long tenantId, String storeId) {
    return deliveries(tenantId, storeId, null);
  }

  public List<WarehouseDeliveryResponse> deliveries(long tenantId, String storeId, Long warehouseId) {
    String sql = """
        select d.id, d.requisition_id, d.store_id, s.name as store_name, d.status,
               ship.display_name as shipped_by, rec.display_name as received_by, d.shipped_at, d.received_at
        from warehouse_delivery_order d
        join store_branch s on s.tenant_id = d.tenant_id and s.id = d.store_id
        left join auth_user ship on ship.tenant_id = d.tenant_id and ship.id = d.shipped_by
        left join auth_user rec on rec.tenant_id = d.tenant_id and rec.id = d.received_by
        where d.tenant_id = ? and (? is null or d.store_id = ?)
        """ + (warehouseId == null ? "" : " and d.warehouse_id = ?\n") + """
        order by d.shipped_at desc
        limit 80
        """;
    List<WarehouseDeliveryHeaderRow> headers = warehouseId == null
        ? jdbcTemplate.query(sql, this::mapDeliveryHeader, tenantId, blankToNull(storeId), blankToNull(storeId))
        : jdbcTemplate.query(sql, this::mapDeliveryHeader, tenantId, blankToNull(storeId), blankToNull(storeId), warehouseId);
    ArrayList<WarehouseDeliveryResponse> rows = new ArrayList<>();
    for (WarehouseDeliveryHeaderRow header : headers) {
      rows.add(new WarehouseDeliveryResponse(
          header.id(),
          header.requisitionId(),
          header.storeId(),
          header.storeName(),
          header.status(),
          deliveryStatusLabel(header.status()),
          header.shippedBy(),
          header.receivedBy(),
          header.shippedAt(),
          header.receivedAt(),
          deliveryLines(tenantId, header.id())
      ));
    }
    return rows;
  }

  public Optional<WarehouseDeliveryResponse> deliveryByRequisition(long tenantId, String requisitionId) {
    return jdbcTemplate.query("""
        select d.id, d.requisition_id, d.store_id, s.name as store_name, d.status,
               ship.display_name as shipped_by, rec.display_name as received_by, d.shipped_at, d.received_at
        from warehouse_delivery_order d
        join store_branch s on s.tenant_id = d.tenant_id and s.id = d.store_id
        left join auth_user ship on ship.tenant_id = d.tenant_id and ship.id = d.shipped_by
        left join auth_user rec on rec.tenant_id = d.tenant_id and rec.id = d.received_by
        where d.tenant_id = ? and d.requisition_id = ?
        order by d.shipped_at desc, d.id desc
        limit 1
        """, this::mapDeliveryHeader, tenantId, requisitionId).stream()
        .findFirst()
        .map(header -> new WarehouseDeliveryResponse(
            header.id(),
            header.requisitionId(),
            header.storeId(),
            header.storeName(),
            header.status(),
            deliveryStatusLabel(header.status()),
            header.shippedBy(),
            header.receivedBy(),
            header.shippedAt(),
            header.receivedAt(),
            deliveryLines(tenantId, header.id())
        ));
  }

  public Map<Long, BigDecimal> shippedQuantitiesFromMovements(long tenantId, String requisitionId) {
    Map<Long, BigDecimal> quantities = new HashMap<>();
    jdbcTemplate.query("""
        select item_id, sum(abs(quantity_delta)) as shipped_quantity
        from warehouse_stock_movement
        where tenant_id = ?
          and source_type = 'REQUISITION'
          and source_id = ?
          and movement_type = 'OUT'
        group by item_id
        """, rs -> {
          quantities.put(rs.getLong("item_id"), amount(rs.getBigDecimal("shipped_quantity")));
        }, tenantId, requisitionId);
    return quantities;
  }

  public void insertReceipt(long tenantId, String id, String deliveryId, String requisitionId, String storeId, Long receivedBy, String note) {
    if (hasWarehouseColumn("store_receipt")) {
      insertReceipt(tenantId, centralWarehouseId(tenantId), id, deliveryId, requisitionId,
          storeId, receivedBy, note);
      return;
    }
    jdbcTemplate.update("""
        insert into store_receipt(
          id, tenant_id, delivery_id, requisition_id, store_id, status, received_by, received_at, note
        ) values (?, ?, ?, ?, ?, 'RECEIVED', ?, current_timestamp, ?)
        """, id, tenantId, deliveryId, requisitionId, storeId, receivedBy, blankToNull(note));
  }

  public void insertReceipt(
      long tenantId, long warehouseId, String id, String deliveryId, String requisitionId,
      String storeId, Long receivedBy, String note
  ) {
    jdbcTemplate.update("""
        insert into store_receipt(
          id, tenant_id, warehouse_id, delivery_id, requisition_id, store_id,
          status, received_by, received_at, note
        )
        values (?, ?, ?, ?, ?, ?, 'RECEIVED', ?, current_timestamp, ?)
        """, id, tenantId, warehouseId, deliveryId, requisitionId, storeId,
        receivedBy, blankToNull(note));
  }

  public void insertReceiptLine(long tenantId, String receiptId, long itemId, BigDecimal receivedQuantity, String note) {
    jdbcTemplate.update("""
        insert into store_receipt_line(tenant_id, receipt_id, item_id, received_quantity, note)
        values (?, ?, ?, ?, ?)
        """, tenantId, receiptId, itemId, amount(receivedQuantity), blankToNull(note));
  }

  public void updateDeliveryLineReceived(long tenantId, String deliveryId, long itemId, BigDecimal receivedQuantity) {
    jdbcTemplate.update("""
        update warehouse_delivery_order_line
        set received_quantity = ?
        where tenant_id = ? and delivery_id = ? and item_id = ?
        """, amount(receivedQuantity), tenantId, deliveryId, itemId);
  }

  public void markReceived(long tenantId, String deliveryId, Long receivedBy) {
    jdbcTemplate.update("""
        update warehouse_delivery_order
        set status = 'RECEIVED',
            received_by = ?,
            received_at = current_timestamp
        where tenant_id = ? and id = ?
        """, receivedBy, tenantId, deliveryId);
  }

  public List<WarehouseStockMovementResponse> movements(long tenantId, String storeId, int limit) {
    return movements(tenantId, storeId, null, limit);
  }

  public List<WarehouseStockMovementResponse> movements(
      long tenantId, String storeId, Long warehouseId, int limit
  ) {
    boolean facilityAware = hasWarehouseColumn("warehouse_stock_movement");
    String sql = """
        select m.id, m.item_id, m.batch_id, i.name as item_name, m.movement_type, m.quantity_delta,
               m.source_type, m.source_id, m.store_id, s.name as store_name, m.note,
               u.display_name as operator_name, m.created_at, b.batch_no,
        """ + (facilityAware ? """
               m.warehouse_id, facility.name as warehouse_name,
               case when transfer_order.id is not null then transfer_order.source_warehouse_id
                    when m.quantity_delta < 0 then m.warehouse_id else null end as source_warehouse_id,
               case when transfer_order.id is not null then source_facility.name
                    when m.quantity_delta < 0 then facility.name else null end as source_warehouse_name,
               case when transfer_order.id is not null then transfer_order.target_warehouse_id
                    when m.quantity_delta > 0 then m.warehouse_id else null end as target_warehouse_id,
               case when transfer_order.id is not null then target_facility.name
                    when m.quantity_delta > 0 then facility.name else null end as target_warehouse_name
        """ : """
               cast(null as bigint) as warehouse_id, cast(null as varchar(160)) as warehouse_name,
               cast(null as bigint) as source_warehouse_id, cast(null as varchar(160)) as source_warehouse_name,
               cast(null as bigint) as target_warehouse_id, cast(null as varchar(160)) as target_warehouse_name
        """) + """
        from warehouse_stock_movement m
        join warehouse_item i on i.tenant_id = m.tenant_id and i.id = m.item_id
        left join warehouse_stock_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id
        left join store_branch s on s.tenant_id = m.tenant_id and s.id = m.store_id
        left join auth_user u on u.tenant_id = m.tenant_id and u.id = m.operator_id
        """ + (facilityAware ? """
        join warehouse_facility facility
          on facility.tenant_id = m.tenant_id and facility.id = m.warehouse_id
        left join warehouse_transfer_order transfer_order
          on transfer_order.tenant_id = m.tenant_id
         and m.source_type = 'WAREHOUSE_TRANSFER' and transfer_order.id = m.source_id
        left join warehouse_facility source_facility
          on source_facility.tenant_id = transfer_order.tenant_id
         and source_facility.id = transfer_order.source_warehouse_id
        left join warehouse_facility target_facility
          on target_facility.tenant_id = transfer_order.tenant_id
         and target_facility.id = transfer_order.target_warehouse_id
        """ : "") + """
        where m.tenant_id = ? and (? is null or m.store_id = ?)
        """ + (warehouseId == null ? "" : " and m.warehouse_id = ?\n") + """
        order by m.created_at desc
        limit ?
        """;
    return warehouseId == null
        ? jdbcTemplate.query(sql, this::mapMovement, tenantId, blankToNull(storeId),
            blankToNull(storeId), Math.max(1, limit))
        : jdbcTemplate.query(sql, this::mapMovement, tenantId, blankToNull(storeId),
            blankToNull(storeId), warehouseId, Math.max(1, limit));
  }

  public Optional<WarehouseReceiptPrintRow> receiptPrintRow(long tenantId, long batchId) {
    return jdbcTemplate.query("""
        select b.id as batch_id, b.item_id, i.code as item_code, i.name as item_name, i.spec, i.unit,
               b.batch_no, b.received_date, b.expiry_date,
               coalesce(m.quantity_delta, b.quantity) as received_quantity,
               b.unit_cost, b.note,
               coalesce(u.display_name, '') as operator_name,
               coalesce(m.created_at, b.created_at) as created_at
        from warehouse_stock_batch b
        join warehouse_item i on i.tenant_id = b.tenant_id and i.id = b.item_id
        left join warehouse_stock_movement m on m.tenant_id = b.tenant_id
          and m.movement_type = 'IN'
          and (
            m.batch_id = b.id
            or (m.item_id = b.item_id and m.source_id = b.batch_no)
          )
        left join auth_user u on u.tenant_id = b.tenant_id and u.id = m.operator_id
        where b.tenant_id = ? and b.id = ?
        order by m.created_at desc, m.id desc
        limit 1
        """, this::mapReceiptPrintRow, tenantId, batchId).stream().findFirst();
  }

  public Optional<WarehouseMovementPrintRow> movementPrintRow(long tenantId, long movementId) {
    return jdbcTemplate.query("""
        select m.id as movement_id, m.item_id, m.batch_id, i.name as item_name, i.spec, i.unit,
               m.movement_type, m.quantity_delta, m.source_type, m.source_id, m.store_id,
               s.name as store_name, m.note, u.display_name as operator_name, m.created_at,
               b.batch_no, b.expiry_date, b.unit_cost
        from warehouse_stock_movement m
        join warehouse_item i on i.tenant_id = m.tenant_id and i.id = m.item_id
        left join warehouse_stock_batch b on b.tenant_id = m.tenant_id
          and (
            b.id = m.batch_id
            or (m.movement_type = 'IN' and b.item_id = m.item_id and b.batch_no = m.source_id)
          )
        left join store_branch s on s.tenant_id = m.tenant_id and s.id = m.store_id
        left join auth_user u on u.tenant_id = m.tenant_id and u.id = m.operator_id
        where m.tenant_id = ? and m.id = ?
        limit 1
        """, this::mapMovementPrintRow, tenantId, movementId).stream().findFirst();
  }

  public Optional<WarehouseDeliveryPrintHeader> deliveryPrintHeader(long tenantId, String requisitionId) {
    return jdbcTemplate.query("""
        select r.id as requisition_id, r.store_id, s.name as store_name,
               r.status,
               coalesce(s.manager, '') as receiver_name,
               '' as receiver_phone,
               coalesce(s.area, '') as receiver_address,
               coalesce(d.id, '') as delivery_id,
               coalesce(d.shipped_at, r.shipped_at) as shipped_at,
               coalesce(d.note, r.note) as note,
               coalesce(ship.display_name, '') as operator_name
        from store_requisition r
        join store_branch s on s.tenant_id = r.tenant_id and s.id = r.store_id
        left join warehouse_delivery_order d on d.tenant_id = r.tenant_id and d.requisition_id = r.id
        left join auth_user ship on ship.tenant_id = r.tenant_id and ship.id = coalesce(d.shipped_by, r.shipped_by)
        where r.tenant_id = ? and r.id = ?
        limit 1
        """, this::mapDeliveryPrintHeader, tenantId, requisitionId).stream().findFirst();
  }

  public List<WarehouseDeliveryPrintLine> deliveryPrintLines(long tenantId, String requisitionId) {
    List<WarehouseDeliveryPrintLine> movementLines = jdbcTemplate.query("""
        select m.item_id, i.name as item_name, i.spec, i.unit,
               abs(m.quantity_delta) as shipped_quantity,
               coalesce(l.unit_price, i.unit_price, 0) as unit_price,
               abs(m.quantity_delta) * coalesce(l.unit_price, i.unit_price, 0) as amount,
               b.batch_no as batch_nos,
               coalesce(l.note, m.note) as note
        from warehouse_stock_movement m
        join warehouse_item i on i.tenant_id = m.tenant_id and i.id = m.item_id
        left join warehouse_stock_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id
        left join (
          select tenant_id, requisition_id, item_id, max(unit_price) as unit_price, max(note) as note
          from store_requisition_line
          group by tenant_id, requisition_id, item_id
        ) l on l.tenant_id = m.tenant_id and l.requisition_id = m.source_id and l.item_id = m.item_id
        where m.tenant_id = ?
          and m.source_type = 'REQUISITION'
          and m.source_id = ?
          and m.movement_type = 'OUT'
        order by m.created_at, m.id
        """, this::mapDeliveryPrintMovementLine, tenantId, requisitionId);
    if (!movementLines.isEmpty()) {
      return movementLines;
    }
    List<WarehouseDeliveryPrintLineBase> bases = jdbcTemplate.query("""
        select l.item_id, i.name as item_name, i.spec, i.unit,
               l.shipped_quantity, l.unit_price, l.amount, l.note
        from store_requisition_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.requisition_id = ?
        order by l.id
        """, this::mapDeliveryPrintLineBase, tenantId, requisitionId);
    return bases.stream()
        .map(base -> new WarehouseDeliveryPrintLine(
            base.itemId(),
            base.itemName(),
            base.spec(),
            base.unit(),
            base.shippedQuantity(),
            base.unitPrice(),
            base.amount(),
            batchNosForRequisitionItem(tenantId, requisitionId, base.itemId()),
            base.note()
        ))
        .toList();
  }

  private String batchNosForRequisitionItem(long tenantId, String requisitionId, long itemId) {
    List<String> batchNos = jdbcTemplate.query("""
        select distinct b.batch_no
        from warehouse_stock_movement m
        left join warehouse_stock_batch b on b.tenant_id = m.tenant_id and b.id = m.batch_id
        where m.tenant_id = ?
          and m.source_id = ?
          and m.item_id = ?
          and m.movement_type = 'OUT'
          and b.batch_no is not null
        order by b.batch_no
        """, (rs, rowNum) -> rs.getString("batch_no"), tenantId, requisitionId, itemId);
    return String.join(", ", batchNos);
  }

  private List<WarehouseRequisitionLineResponse> requisitionLines(long tenantId, String requisitionId) {
    return jdbcTemplate.query("""
        select l.id, l.item_id, i.name as item_name, i.unit, l.requested_quantity,
               l.approved_quantity, l.shipped_quantity, l.unit_price, l.amount, l.warning_text, l.note
        from store_requisition_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.requisition_id = ?
        order by l.id
        """, this::mapLine, tenantId, requisitionId);
  }

  private List<WarehousePurchaseOrderLineResponse> purchaseOrderLines(long tenantId, String purchaseOrderId) {
    return jdbcTemplate.query("""
        select l.id, l.item_id, i.name as item_name, i.unit, l.ordered_quantity,
               l.received_quantity, l.unit_cost, l.amount, l.note
        from warehouse_purchase_order_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.purchase_order_id = ?
        order by l.id
        """, this::mapPurchaseLine, tenantId, purchaseOrderId);
  }

  private List<WarehouseDeliveryLineResponse> deliveryLines(long tenantId, String deliveryId) {
    return jdbcTemplate.query("""
        select l.id, l.item_id, i.name as item_name, i.unit, l.shipped_quantity,
               l.received_quantity, l.unit_price, l.amount
        from warehouse_delivery_order_line l
        join warehouse_item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ? and l.delivery_id = ?
        order by l.id
        """, this::mapDeliveryLine, tenantId, deliveryId);
  }

  private List<WarehouseReturnLineResponse> returnLines(long tenantId, String returnOrderId) {
    return jdbcTemplate.query("""
        select id, source_requisition_line_id, item_id, item_name, spec, batch_id, batch_no,
               quantity, unit, unit_price, return_price, amount, reason, note
        from warehouse_return_order_line
        where tenant_id = ? and return_order_id = ?
        order by id
        """, this::mapReturnLine, tenantId, returnOrderId);
  }

  private WarehouseItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal stock = amount(rs.getBigDecimal("stock_quantity"));
    BigDecimal unitPrice = amount(rs.getBigDecimal("unit_price"));
    BigDecimal dailyUsage = amount(rs.getBigDecimal("daily_usage_estimate"));
    BigDecimal minStockQuantity = amount(rs.getBigDecimal("min_stock_quantity"));
    boolean alertEnabled = rs.getBoolean("alert_enabled");
    Integer expiryAlertDays = rs.getObject("expiry_alert_days") == null ? null : ((Number) rs.getObject("expiry_alert_days")).intValue();
    BigDecimal stockValue = stock.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    BigDecimal daysAvailable = dailyUsage.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
        : stock.divide(dailyUsage, 1, RoundingMode.HALF_UP);
    String nearestExpiry = rs.getString("nearest_expiry_date");
    String stockStatus = stockStatus(stock, minStockQuantity, alertEnabled);
    AlertInfo alert = alert(stock, minStockQuantity, alertEnabled, expiryAlertDays, nearestExpiry, rs.getString("unit"));
    return new WarehouseItemResponse(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getString("name"),
        (Long) rs.getObject("category_id"),
        rs.getString("category_name"),
        rs.getString("category"),
        rs.getString("image_url"),
        rs.getString("unit"),
        rs.getString("purchase_unit"),
        rs.getString("stock_unit"),
        rs.getString("ingredient_unit"),
        rs.getString("unit_conversion_text"),
        rs.getString("spec"),
        rs.getString("warehouse_location"),
        unitPrice,
        rs.getObject("shelf_life_days") == null ? null : ((Number) rs.getObject("shelf_life_days")).intValue(),
        amount(rs.getBigDecimal("cups_per_unit")),
        dailyUsage,
        rs.getInt("min_stock_days"),
        rs.getInt("max_stock_days"),
        minStockQuantity,
        alertEnabled,
        expiryAlertDays,
        rs.getBoolean("active"),
        stock,
        ZERO,
        stock,
        stockValue,
        daysAvailable,
        nearestExpiry,
        stockStatus,
        alert.level(),
        alert.text(),
        rs.getString("item_description"),
        rs.getInt("sort_order"),
        rs.getString("item_attributes"),
        itemDepartments(rs.getLong("id"))
    );
  }

  private List<WarehouseItemDepartmentResponse> itemDepartments(long itemId) {
    return jdbcTemplate.query("""
        select id, department_name, department_code, department_group, purchase_method, supplier_name
        from warehouse_item_department
        where item_id = ?
        order by id
        """, (rs, rowNum) -> new WarehouseItemDepartmentResponse(
        rs.getLong("id"),
        rs.getString("department_name"),
        rs.getString("department_code"),
        rs.getString("department_group"),
        rs.getString("purchase_method"),
        rs.getString("supplier_name")
    ), itemId);
  }

  private WarehouseRequisitionHeaderRow mapHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseRequisitionHeaderRow(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getObject("warehouse_id", Long.class),
        rs.getString("warehouse_name"),
        rs.getString("status"),
        amount(rs.getBigDecimal("total_amount")),
        rs.getString("note"),
        rs.getString("submitted_by"),
        rs.getString("reviewed_by"),
        rs.getString("shipped_by"),
        rs.getString("received_by"),
        formatDateTime(rs.getObject("submitted_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("reviewed_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("shipped_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("received_at", LocalDateTime.class))
    );
  }

  private WarehouseRequisitionLineResponse mapLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseRequisitionLineResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("requested_quantity")),
        amount(rs.getBigDecimal("approved_quantity")),
        amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("warning_text"),
        rs.getString("note")
    );
  }

  private WarehouseStockBatchRow mapBatch(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseStockBatchRow(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("batch_no"),
        rs.getString("expiry_date"),
        amount(rs.getBigDecimal("quantity"))
    );
  }

  private WarehouseStockBatchResponse mapStockBatch(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal quantity = amount(rs.getBigDecimal("quantity"));
    String expiryDate = rs.getString("expiry_date");
    int expiryAlertDays = rs.getInt("expiry_alert_days");
    return new WarehouseStockBatchResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("unit"),
        rs.getString("batch_no"),
        rs.getString("received_date"),
        expiryDate,
        quantity,
        amount(rs.getBigDecimal("unit_cost")),
        rs.getString("note"),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class)),
        batchStatus(quantity, expiryDate, expiryAlertDays)
    );
  }

  private WarehousePurchaseOrderHeaderRow mapPurchaseHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehousePurchaseOrderHeaderRow(
        rs.getString("id"),
        rs.getObject("supplier_id", Long.class),
        rs.getString("supplier_name"),
        rs.getString("status"),
        amount(rs.getBigDecimal("total_amount")),
        rs.getString("note"),
        rs.getString("created_by"),
        rs.getString("received_by"),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("received_at", LocalDateTime.class))
    );
  }

  private WarehousePurchaseOrderLineResponse mapPurchaseLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehousePurchaseOrderLineResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("ordered_quantity")),
        amount(rs.getBigDecimal("received_quantity")),
        amount(rs.getBigDecimal("unit_cost")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("note")
    );
  }

  private WarehouseDeliveryHeaderRow mapDeliveryHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseDeliveryHeaderRow(
        rs.getString("id"),
        rs.getString("requisition_id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("status"),
        rs.getString("shipped_by"),
        rs.getString("received_by"),
        formatDateTime(rs.getObject("shipped_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("received_at", LocalDateTime.class))
    );
  }

  private WarehouseDeliveryLineResponse mapDeliveryLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseDeliveryLineResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("received_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("amount"))
    );
  }

  private WarehouseStockMovementResponse mapMovement(ResultSet rs, int rowNum) throws SQLException {
    String movementType = rs.getString("movement_type");
    return new WarehouseStockMovementResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getObject("batch_id", Long.class),
        rs.getString("item_name"),
        movementType,
        movementTypeLabel(movementType),
        amount(rs.getBigDecimal("quantity_delta")),
        rs.getObject("warehouse_id", Long.class),
        rs.getString("warehouse_name"),
        rs.getObject("source_warehouse_id", Long.class),
        rs.getString("source_warehouse_name"),
        rs.getObject("target_warehouse_id", Long.class),
        rs.getString("target_warehouse_name"),
        rs.getString("source_type"),
        rs.getString("source_id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("note"),
        rs.getString("operator_name"),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class)),
        rs.getString("batch_no")
    );
  }

  private WarehouseReturnHeaderRow mapReturnHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseReturnHeaderRow(
        rs.getString("id"),
        rs.getString("return_no"),
        rs.getString("source_requisition_id"),
        rs.getString("source_delivery_id"),
        rs.getString("return_store_id"),
        rs.getString("return_store_name"),
        rs.getObject("receive_warehouse_id", Long.class),
        rs.getString("receive_warehouse_name"),
        rs.getString("receive_department"),
        rs.getString("status"),
        amount(rs.getBigDecimal("total_amount")),
        rs.getString("handled_by"),
        rs.getString("created_by"),
        rs.getString("updated_by"),
        rs.getString("reviewed_by"),
        rs.getString("checked_by"),
        rs.getString("reason"),
        rs.getString("note"),
        rs.getString("review_note"),
        rs.getString("received_note"),
        rs.getString("return_date"),
        formatDateTime(rs.getObject("reviewed_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("received_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class)),
        formatDateTime(rs.getObject("updated_at", LocalDateTime.class)),
        rs.getInt("line_count"),
        rs.getInt("attachment_count")
    );
  }

  private WarehouseReturnLineResponse mapReturnLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseReturnLineResponse(
        rs.getLong("id"),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getObject("batch_id", Long.class),
        rs.getString("batch_no"),
        rs.getObject("source_requisition_line_id", Long.class),
        amount(rs.getBigDecimal("quantity")),
        rs.getString("unit"),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("return_price")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("reason"),
        rs.getString("note")
    );
  }

  private ReturnSourceMovementRow mapReturnSourceMovement(ResultSet rs, int rowNum) throws SQLException {
    return new ReturnSourceMovementRow(
        rs.getObject("source_requisition_line_id", Long.class),
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getObject("batch_id", Long.class),
        rs.getString("batch_no"),
        amount(rs.getBigDecimal("shipped_quantity")),
        rs.getString("unit"),
        amount(rs.getBigDecimal("unit_price"))
    );
  }

  private WarehouseReceiptPrintRow mapReceiptPrintRow(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseReceiptPrintRow(
        rs.getLong("batch_id"),
        rs.getLong("item_id"),
        rs.getString("item_code"),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getString("unit"),
        rs.getString("batch_no"),
        rs.getString("received_date"),
        rs.getString("expiry_date"),
        amount(rs.getBigDecimal("received_quantity")),
        amount(rs.getBigDecimal("unit_cost")),
        rs.getString("note"),
        rs.getString("operator_name"),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class))
    );
  }

  private WarehouseMovementPrintRow mapMovementPrintRow(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseMovementPrintRow(
        rs.getLong("movement_id"),
        rs.getLong("item_id"),
        rs.getObject("batch_id", Long.class),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getString("unit"),
        rs.getString("movement_type"),
        amount(rs.getBigDecimal("quantity_delta")),
        rs.getString("source_type"),
        rs.getString("source_id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("note"),
        rs.getString("operator_name"),
        formatDateTime(rs.getObject("created_at", LocalDateTime.class)),
        rs.getString("batch_no"),
        rs.getString("expiry_date"),
        amount(rs.getBigDecimal("unit_cost"))
    );
  }

  private WarehouseDeliveryPrintHeader mapDeliveryPrintHeader(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseDeliveryPrintHeader(
        rs.getString("requisition_id"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("status"),
        rs.getString("receiver_name"),
        rs.getString("receiver_phone"),
        rs.getString("receiver_address"),
        rs.getString("delivery_id"),
        formatDateTime(rs.getObject("shipped_at", LocalDateTime.class)),
        rs.getString("note"),
        rs.getString("operator_name")
    );
  }

  private WarehouseDeliveryPrintLineBase mapDeliveryPrintLineBase(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseDeliveryPrintLineBase(
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("note")
    );
  }

  private WarehouseDeliveryPrintLine mapDeliveryPrintMovementLine(ResultSet rs, int rowNum) throws SQLException {
    return new WarehouseDeliveryPrintLine(
        rs.getLong("item_id"),
        rs.getString("item_name"),
        rs.getString("spec"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("shipped_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("batch_nos"),
        rs.getString("note")
    );
  }

  private String stockStatus(BigDecimal stock, BigDecimal minStockQuantity, boolean alertEnabled) {
    if (stock.compareTo(BigDecimal.ZERO) <= 0) {
      return "缺货";
    }
    if (alertEnabled && minStockQuantity.compareTo(BigDecimal.ZERO) > 0 && stock.compareTo(minStockQuantity) < 0) {
      return "低库存";
    }
    return "正常";
  }

  private String batchStatus(BigDecimal quantity, String expiryDate, int expiryAlertDays) {
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return "已用完";
    }
    if (expiryDate != null && !expiryDate.isBlank()) {
      LocalDate expiry = LocalDate.parse(expiryDate);
      LocalDate today = LocalDate.now();
      if (expiry.isBefore(today)) {
        return "过期";
      }
      if (expiryAlertDays > 0 && !expiry.isAfter(today.plusDays(expiryAlertDays))) {
        return "临期";
      }
    }
    return "正常";
  }

  private AlertInfo alert(
      BigDecimal stock,
      BigDecimal minStockQuantity,
      boolean alertEnabled,
      Integer expiryAlertDays,
      String nearestExpiry,
      String unit
  ) {
    String safeUnit = unit == null || unit.isBlank() ? "件" : unit;
    if (nearestExpiry != null && !nearestExpiry.isBlank()) {
      java.time.LocalDate expiry = java.time.LocalDate.parse(nearestExpiry);
      long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), expiry);
      if (expiryAlertDays != null && expiryAlertDays > 0 && days <= expiryAlertDays) {
        return new AlertInfo("EXPIRING", "临期风险，最近批次 " + nearestExpiry + " 到期");
      }
    }
    if (alertEnabled) {
      if (stock.compareTo(BigDecimal.ZERO) <= 0) {
        return new AlertInfo("OUT", "暂时缺货，当前库存 0" + safeUnit + "，请安排补货");
      }
      if (minStockQuantity.compareTo(BigDecimal.ZERO) > 0 && stock.compareTo(minStockQuantity) < 0) {
        return new AlertInfo(
            "LOW",
            "库存不足：当前库存 " + qty(stock) + safeUnit + "，最低安全库存 " + qty(minStockQuantity) + safeUnit
        );
      }
    }
    return new AlertInfo("OK", "正常");
  }

  private String statusLabel(String status) {
    return switch (status) {
      case "SUBMITTED" -> "待仓库处理";
      case "APPROVED" -> "待发货";
      case "SHIPPED" -> "待门店收货";
      case "RECEIVED" -> "门店已收货";
      case "REJECTED" -> "已驳回";
      case "TODO_DONE" -> "已处理";
      default -> status;
    };
  }

  private String deliveryStatusLabel(String status) {
    return switch (status) {
      case "SHIPPED" -> "待门店收货";
      case "RECEIVED" -> "门店已收货";
      default -> statusLabel(status);
    };
  }

  private String purchaseStatusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "采购草稿";
      case "ORDERED" -> "待入库";
      case "RECEIVED" -> "已入库";
      case "CANCELLED" -> "已取消";
      default -> status;
    };
  }

  private String returnStatusLabel(String status) {
    return switch (status) {
      case "SUBMITTED" -> "已提交";
      case "APPROVED" -> "仓库已通过";
      case "REJECTED" -> "仓库已驳回";
      case "RECEIVED" -> "仓库已收货";
      case "DRAFT" -> "草稿";
      case "CHECKED" -> "已核对";
      case "CANCELLED" -> "已作废";
      default -> status;
    };
  }

  private String movementTypeLabel(String movementType) {
    return switch (movementType) {
      case "IN" -> "采购入库";
      case "OUT" -> "配送出库";
      case "ADJUST" -> "库存调整";
      default -> movementType;
    };
  }

  private String formatDateTime(LocalDateTime value) {
    return value == null ? null : value.format(DATE_TIME_FORMAT);
  }

  private String qty(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  public Optional<Long> batchWarehouseId(long tenantId, long batchId) {
    return jdbcTemplate.query("select warehouse_id from warehouse_stock_batch where tenant_id = ? and id = ?",
        (rs, rowNum) -> rs.getLong(1), tenantId, batchId).stream().findFirst();
  }

  public Optional<Long> movementWarehouseId(long tenantId, long movementId) {
    return jdbcTemplate.query("select warehouse_id from warehouse_stock_movement where tenant_id = ? and id = ?",
        (rs, rowNum) -> rs.getLong(1), tenantId, movementId).stream().findFirst();
  }

  public Optional<Long> deliveryWarehouseId(long tenantId, String requisitionId) {
    return jdbcTemplate.query("select warehouse_id from warehouse_delivery_order where tenant_id = ? and requisition_id = ? limit 1",
        (rs, rowNum) -> rs.getLong(1), tenantId, requisitionId).stream().findFirst();
  }

  public Optional<Long> returnWarehouseId(long tenantId, String returnId) {
    return jdbcTemplate.query("""
        select warehouse_id from warehouse_return_order
        where tenant_id = ? and (id = ? or return_no = ?) limit 1
        """, (rs, rowNum) -> rs.getLong(1), tenantId, returnId, returnId).stream().findFirst();
  }

  private long centralWarehouseId(long tenantId) {
    Long id = jdbcTemplate.query("""
        select id from warehouse_facility
        where tenant_id = ? and warehouse_type = 'CENTRAL' and enabled = 1
        order by id limit 1
        """, (rs, rowNum) -> rs.getLong(1), tenantId).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("当前企业未配置启用的总仓"));
    return id;
  }

  private boolean hasWarehouseColumn(String tableName) {
    return hasColumn(tableName, "warehouse_id");
  }

  private boolean hasColumn(String tableName, String columnName) {
    try {
      jdbcTemplate.queryForList("select " + columnName + " from " + tableName + " where 1 = 0");
      return true;
    } catch (DataAccessException ex) {
      return false;
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private int positiveInt(Integer value, int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  private record WarehouseRequisitionHeaderRow(
      String id,
      String storeId,
      String storeName,
      Long warehouseId,
      String warehouseName,
      String status,
      BigDecimal totalAmount,
      String note,
      String submittedBy,
      String reviewedBy,
      String shippedBy,
      String receivedBy,
      String submittedAt,
      String reviewedAt,
      String shippedAt,
      String receivedAt
  ) {
  }

  private record WarehousePurchaseOrderHeaderRow(
      String id,
      Long supplierId,
      String supplierName,
      String status,
      BigDecimal totalAmount,
      String note,
      String createdBy,
      String receivedBy,
      String createdAt,
      String receivedAt
  ) {
  }

  private record WarehouseDeliveryHeaderRow(
      String id,
      String requisitionId,
      String storeId,
      String storeName,
      String status,
      String shippedBy,
      String receivedBy,
      String shippedAt,
      String receivedAt
  ) {
  }

  private record WarehouseReturnHeaderRow(
      String id,
      String returnNo,
      String sourceRequisitionId,
      String sourceDeliveryId,
      String returnStoreId,
      String returnStoreName,
      Long receiveWarehouseId,
      String receiveWarehouseName,
      String receiveDepartment,
      String status,
      BigDecimal totalAmount,
      String handledBy,
      String createdBy,
      String updatedBy,
      String reviewedBy,
      String checkedBy,
      String reason,
      String note,
      String reviewNote,
      String receivedNote,
      String returnDate,
      String reviewedAt,
      String receivedAt,
      String createdAt,
      String updatedAt,
      int lineCount,
      int attachmentCount
  ) {
  }

  public record ReturnSourceMovementRow(
      Long sourceRequisitionLineId,
      long itemId,
      String itemName,
      String spec,
      Long batchId,
      String batchNo,
      BigDecimal shippedQuantity,
      String unit,
      BigDecimal unitPrice
  ) {
  }

  public record WarehouseFacilitySnapshot(long id, String code, String name) {
  }

  private record AlertInfo(String level, String text) {
  }

  public record WarehouseReceiptPrintRow(
      long batchId,
      long itemId,
      String itemCode,
      String itemName,
      String spec,
      String unit,
      String batchNo,
      String receivedDate,
      String expiryDate,
      BigDecimal receivedQuantity,
      BigDecimal unitCost,
      String note,
      String operatorName,
      String createdAt
  ) {
  }

  public record WarehouseMovementPrintRow(
      long movementId,
      long itemId,
      Long batchId,
      String itemName,
      String spec,
      String unit,
      String movementType,
      BigDecimal quantityDelta,
      String sourceType,
      String sourceId,
      String storeId,
      String storeName,
      String note,
      String operatorName,
      String createdAt,
      String batchNo,
      String expiryDate,
      BigDecimal unitCost
  ) {
  }

  public record WarehouseDeliveryPrintHeader(
      String requisitionId,
      String storeId,
      String storeName,
      String status,
      String receiverName,
      String receiverPhone,
      String receiverAddress,
      String deliveryId,
      String shippedAt,
      String note,
      String operatorName
  ) {
  }

  private record WarehouseDeliveryPrintLineBase(
      long itemId,
      String itemName,
      String spec,
      String unit,
      BigDecimal shippedQuantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String note
  ) {
  }

  public record WarehouseDeliveryPrintLine(
      long itemId,
      String itemName,
      String spec,
      String unit,
      BigDecimal shippedQuantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String batchNos,
      String note
  ) {
  }

  public record PurchaseLockRow(String id, long warehouseId, String status) {
  }
}
