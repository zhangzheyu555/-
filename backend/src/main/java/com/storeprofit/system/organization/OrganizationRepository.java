package com.storeprofit.system.organization;

import com.storeprofit.system.platform.authorization.DataScope;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {
  private static final List<String> STORE_LINK_COLUMNS = List.of("store_id", "return_store_id");
  private static final List<String> STORE_DELETE_LINK_EXCLUDED_TABLES = List.of("store_branch", "operation_log");

  private final JdbcTemplate jdbcTemplate;

  public OrganizationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<BrandResponse> brands(long tenantId) {
    return brands(tenantId, null);
  }

  public List<BrandResponse> brands(long tenantId, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("""
        select id, code, name, color, sort_order
        from brand b
        where b.tenant_id = ?
        """);
    ArrayList<Object> params = new ArrayList<>();
    params.add(tenantId);
    appendBrandScope(sql, params, dataScope);
    sql.append(" order by b.sort_order, b.id");
    return jdbcTemplate.query(sql.toString(), this::mapBrand, params.toArray());
  }

  public List<StoreResponse> stores(long tenantId) {
    return stores(tenantId, null);
  }

  public List<StoreResponse> stores(long tenantId, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("""
        select s.id, s.code, s.name, s.brand_id, b.name as brand_name, s.area, s.manager,
               date_format(s.open_date, '%Y-%m-%d') as open_date, s.status, s.note,
               s.region_code, s.supply_warehouse_id, w.name as supply_warehouse_name
        from store_branch s
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        left join warehouse_facility w
          on w.id = s.supply_warehouse_id and w.tenant_id = s.tenant_id
        where s.tenant_id = ?
        """);
    ArrayList<Object> params = new ArrayList<>();
    params.add(tenantId);
    appendStoreScope(sql, params, "s.id", dataScope);
    sql.append(" order by b.sort_order, s.code, s.id");
    return jdbcTemplate.query(sql.toString(), this::mapStore, params.toArray());
  }

  public long ensureBrand(long tenantId, String code, String name, String color, int sortOrder) {
    jdbcTemplate.update("""
        insert into brand(tenant_id, code, name, color, sort_order, created_at)
        values (?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          name = values(name),
          color = values(color),
          sort_order = values(sort_order),
          updated_at = current_timestamp
        """, tenantId, code, name, color, sortOrder);
    Long id = jdbcTemplate.queryForObject(
        "select id from brand where tenant_id = ? and code = ?",
        Long.class,
        tenantId,
        code
    );
    return id == null ? 0L : id;
  }

  public void upsertStore(long tenantId, StoreUpsertRequest request) {
    Long supplyWarehouseId = request.supplyWarehouseId();
    if (supplyWarehouseId == null && request.regionCode() != null && !request.regionCode().isBlank()) {
      supplyWarehouseId = supplyWarehouseIdForRegion(tenantId, request.regionCode()).orElse(null);
    }
    upsertStore(tenantId, request, supplyWarehouseId);
  }

  public void upsertStore(long tenantId, StoreUpsertRequest request, Long supplyWarehouseId) {
    jdbcTemplate.update("""
        insert into store_branch(
          id, tenant_id, brand_id, code, name, area, region_code, supply_warehouse_id,
          manager, open_date, status, note, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          brand_id = values(brand_id),
          code = values(code),
          name = values(name),
          area = values(area),
          region_code = coalesce(values(region_code), region_code),
          supply_warehouse_id = coalesce(values(supply_warehouse_id), supply_warehouse_id),
          manager = values(manager),
          open_date = values(open_date),
          status = values(status),
          note = values(note),
          updated_at = current_timestamp
        """,
        request.id(),
        tenantId,
        request.brandId(),
        blankToNull(request.code()),
        request.name(),
        blankToNull(request.area()),
        blankToNull(request.regionCode()),
        supplyWarehouseId,
        blankToNull(request.manager()),
        blankToNull(request.openDate()),
        request.status() == null || request.status().isBlank() ? "营业中" : request.status(),
        blankToNull(request.note())
    );
  }

  public Optional<StoreResponse> store(long tenantId, String storeId) {
    return stores(tenantId).stream().filter(store -> store.id().equals(storeId)).findFirst();
  }

  public int deleteStore(long tenantId, String storeId) {
    return jdbcTemplate.update(
        "delete from store_branch where tenant_id = ? and id = ?",
        tenantId,
        storeId
    );
  }

  public boolean storeHasLinkedData(long tenantId, String storeId) {
    Boolean linked = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
      DatabaseMetaData metadata = connection.getMetaData();
      List<StoreLinkColumn> columns = storeLinkColumns(metadata, connection.getCatalog(), connection.getSchema());
      for (StoreLinkColumn column : columns) {
        if (hasLinkedRows(connection, column, tenantId, storeId)) {
          return true;
        }
      }
      return false;
    });
    return Boolean.TRUE.equals(linked);
  }

  public Optional<Long> supplyWarehouseIdForRegion(long tenantId, String regionCode) {
    if (regionCode == null || regionCode.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate.query("""
        select id from warehouse_facility
        where tenant_id = ? and region_code = ? and store_supply_allowed = 1 and enabled = 1
        order by case warehouse_type when 'CENTRAL' then 0 else 1 end, id
        limit 1
        """, (rs, rowNum) -> rs.getLong(1), tenantId, regionCode.trim().toUpperCase())
        .stream().findFirst();
  }

  public boolean brandExists(long tenantId, long brandId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from brand where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        brandId
    );
    return count != null && count > 0;
  }

  public boolean storeIdBelongsToOtherTenant(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where id = ? and tenant_id <> ?",
        Integer.class,
        storeId,
        tenantId
    );
    return count != null && count > 0;
  }

  public boolean storeCodeBelongsToAnotherStore(long tenantId, String code, String storeId) {
    if (code == null || code.isBlank()) {
      return false;
    }
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and code = ? and id <> ?",
        Integer.class,
        tenantId,
        code.trim(),
        storeId
    );
    return count != null && count > 0;
  }

  public int brandCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from brand where tenant_id = ?", Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public int storeCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from store_branch where tenant_id = ?", Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public Optional<String> kv(String key) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(
          "select storage_value from kv_storage where storage_key = ?",
          String.class,
          key
      ));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public void addUserStoreScope(long tenantId, long userId, String storeId) {
    jdbcTemplate.update("""
        insert ignore into user_store_scope(tenant_id, user_id, store_id, created_at)
        values (?, ?, ?, current_timestamp)
        """, tenantId, userId, storeId);
  }

  private BrandResponse mapBrand(ResultSet rs, int rowNum) throws SQLException {
    return new BrandResponse(
        rs.getLong("id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("color"),
        rs.getInt("sort_order")
    );
  }

  private StoreResponse mapStore(ResultSet rs, int rowNum) throws SQLException {
    return new StoreResponse(
        rs.getString("id"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getLong("brand_id"),
        rs.getString("brand_name"),
        rs.getString("area"),
        rs.getString("manager"),
        rs.getString("open_date"),
        rs.getString("status"),
        rs.getString("note"),
        rs.getString("region_code"),
        rs.getObject("supply_warehouse_id", Long.class),
        rs.getString("supply_warehouse_name")
    );
  }

  private Object blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private List<StoreLinkColumn> storeLinkColumns(DatabaseMetaData metadata, String catalog, String schema)
      throws SQLException {
    List<StoreLinkColumn> columns = new ArrayList<>();
    int scannedTables = collectStoreLinkColumns(metadata, catalog, schema, columns);
    if (scannedTables == 0 && (catalog != null || schema != null)) {
      collectStoreLinkColumns(metadata, null, null, columns);
    }
    return columns;
  }

  private int collectStoreLinkColumns(
      DatabaseMetaData metadata,
      String catalog,
      String schema,
      List<StoreLinkColumn> columns
  ) throws SQLException {
    int scannedTables = 0;
    try (ResultSet tables = metadata.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
      while (tables.next()) {
        scannedTables++;
        String tableName = tables.getString("TABLE_NAME");
        if (isStoreDeleteLinkExcluded(tableName)) {
          continue;
        }
        collectStoreLinkColumns(metadata, catalog, schema, tableName, columns);
      }
    }
    return scannedTables;
  }

  private void collectStoreLinkColumns(
      DatabaseMetaData metadata,
      String catalog,
      String schema,
      String tableName,
      List<StoreLinkColumn> columns
  ) throws SQLException {
    boolean tenantScoped = false;
    List<String> linkedColumns = new ArrayList<>();
    try (ResultSet tableColumns = metadata.getColumns(catalog, schema, tableName, null)) {
      while (tableColumns.next()) {
        String columnName = tableColumns.getString("COLUMN_NAME");
        String normalized = normalizeIdentifier(columnName);
        if ("tenant_id".equals(normalized)) {
          tenantScoped = true;
        }
        if (STORE_LINK_COLUMNS.contains(normalized)) {
          linkedColumns.add(columnName);
        }
      }
    }
    for (String columnName : linkedColumns) {
      StoreLinkColumn column = new StoreLinkColumn(tableName, columnName, tenantScoped);
      if (!columns.contains(column)) {
        columns.add(column);
      }
    }
  }

  private boolean hasLinkedRows(Connection connection, StoreLinkColumn column, long tenantId, String storeId)
      throws SQLException {
    String sql = "select count(*) from " + safeIdentifier(column.tableName())
        + " where " + safeIdentifier(column.columnName()) + " = ?"
        + (column.tenantScoped() ? " and tenant_id = ?" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, storeId);
      if (column.tenantScoped()) {
        statement.setLong(2, tenantId);
      }
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && rows.getInt(1) > 0;
      }
    }
  }

  private boolean isStoreDeleteLinkExcluded(String tableName) {
    return STORE_DELETE_LINK_EXCLUDED_TABLES.contains(normalizeIdentifier(tableName));
  }

  private String normalizeIdentifier(String identifier) {
    return String.valueOf(identifier == null ? "" : identifier).trim().toLowerCase(Locale.ROOT);
  }

  private String safeIdentifier(String identifier) throws SQLException {
    String value = String.valueOf(identifier == null ? "" : identifier).trim();
    if (!value.matches("[A-Za-z0-9_]+")) {
      throw new SQLException("Unsafe database identifier: " + identifier);
    }
    return value;
  }

  private void appendBrandScope(StringBuilder sql, List<Object> params, DataScope dataScope) {
    if (dataScope == null || dataScope.allowsAllStores()) {
      return;
    }
    if (dataScope.deniesStoreAccess() || dataScope.storeIds() == null || dataScope.storeIds().isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and exists (select 1 from store_branch scoped_store")
        .append(" where scoped_store.tenant_id = b.tenant_id")
        .append(" and scoped_store.brand_id = b.id")
        .append(" and scoped_store.id in (")
        .append(placeholders(dataScope.storeIds().size()))
        .append("))");
    params.addAll(dataScope.storeIds());
  }

  private void appendStoreScope(
      StringBuilder sql,
      List<Object> params,
      String storeColumn,
      DataScope dataScope
  ) {
    if (dataScope == null || dataScope.allowsAllStores()) {
      return;
    }
    if (dataScope.deniesStoreAccess() || dataScope.storeIds() == null || dataScope.storeIds().isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and ").append(storeColumn).append(" in (")
        .append(placeholders(dataScope.storeIds().size()))
        .append(")");
    params.addAll(dataScope.storeIds());
  }

  private String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  private record StoreLinkColumn(String tableName, String columnName, boolean tenantScoped) {
  }
}
