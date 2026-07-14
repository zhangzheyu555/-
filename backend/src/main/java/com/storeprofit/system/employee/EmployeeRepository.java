package com.storeprofit.system.employee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public EmployeeRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<EmployeeResponse> records(long tenantId, Long brandId, String storeId, String status) {
    return records(tenantId, brandId, storeId, status, null);
  }

  public List<EmployeeResponse> records(
      long tenantId,
      Long brandId,
      String storeId,
      String status,
      Collection<String> allowedStoreIds
  ) {
    StringBuilder sql = new StringBuilder("""
        select e.id, e.store_id, s.code as store_code,
               coalesce(e.store_name, s.name) as store_name,
               s.brand_id, coalesce(e.brand_name, b.name) as brand_name,
               e.name, e.phone, e.role, e.position, e.employment_type,
               e.base_salary, e.status, date_format(e.hire_date, '%Y-%m-%d') as hire_date,
               e.remark
        from employee e
        left join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where e.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and e.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    } else if (allowedStoreIds != null) {
      List<String> normalizedStoreIds = allowedStoreIds.stream()
          .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
          .map(String::trim)
          .distinct()
          .toList();
      if (normalizedStoreIds.isEmpty()) {
        sql.append(" and 1 = 0");
      } else {
        sql.append(" and e.store_id in (:allowedStoreIds)");
        params.addValue("allowedStoreIds", normalizedStoreIds);
      }
    }
    if (status != null && !status.isBlank()) {
      sql.append(" and e.status = :status");
      params.addValue("status", status.trim());
    }
    sql.append(" order by s.code, e.status desc, e.role, e.name");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public Optional<EmployeeResponse> record(long tenantId, String id) {
    List<EmployeeResponse> rows = namedJdbcTemplate.query("""
        select e.id, e.store_id, s.code as store_code,
               coalesce(e.store_name, s.name) as store_name,
               s.brand_id, coalesce(e.brand_name, b.name) as brand_name,
               e.name, e.phone, e.role, e.position, e.employment_type,
               e.base_salary, e.status, date_format(e.hire_date, '%Y-%m-%d') as hire_date,
               e.remark
        from employee e
        left join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where e.tenant_id = :tenantId and e.id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRecord
    );
    return rows.stream().findFirst();
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
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select name
          from store_branch
          where tenant_id = ? and id = ?
          """, String.class, tenantId, storeId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<String> brandName(long tenantId, String storeId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select b.name
          from store_branch s
          left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
          where s.tenant_id = ? and s.id = ?
          """, String.class, tenantId, storeId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public void upsertSeed(
      long tenantId,
      String id,
      String storeId,
      String storeName,
      String brandName,
      String name,
      String role,
      String position,
      String employmentType,
      BigDecimal baseSalary,
      String status,
      String remark,
      String dataSource
  ) {
    namedJdbcTemplate.update("""
        insert into employee(
          id, tenant_id, store_id, store_name, brand_name, name, role, position,
          employment_type, base_salary, status, remark, data_source, created_at, updated_at
        )
        values(
          :id, :tenantId, :storeId, :storeName, :brandName, :name, :role, :position,
          :employmentType, :baseSalary, :status, :remark, :dataSource, current_timestamp, current_timestamp
        )
        on duplicate key update
          store_name = if(data_source = 'LEGACY_SEED', values(store_name), store_name),
          brand_name = if(data_source = 'LEGACY_SEED', values(brand_name), brand_name),
          role = if(data_source = 'LEGACY_SEED', values(role), role),
          position = if(data_source = 'LEGACY_SEED', values(position), position),
          employment_type = if(data_source = 'LEGACY_SEED', values(employment_type), employment_type),
          base_salary = if(data_source = 'LEGACY_SEED', values(base_salary), base_salary),
          status = if(data_source = 'LEGACY_SEED', values(status), status),
          remark = if(data_source = 'LEGACY_SEED', values(remark), remark),
          data_source = if(data_source = 'LEGACY_SEED', values(data_source), data_source),
          updated_at = current_timestamp
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId)
            .addValue("storeId", storeId)
            .addValue("storeName", blankToNull(storeName))
            .addValue("brandName", blankToNull(brandName))
            .addValue("name", name)
            .addValue("role", blankToNull(role))
            .addValue("position", blankToNull(position))
            .addValue("employmentType", blankToNull(employmentType))
            .addValue("baseSalary", amount(baseSalary))
            .addValue("status", status == null || status.isBlank() ? "在职" : status)
            .addValue("remark", blankToNull(remark))
            .addValue("dataSource", dataSource == null || dataSource.isBlank() ? "MANUAL_ENTRY" : dataSource)
    );
  }

  private EmployeeResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
    return new EmployeeResponse(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        boxedLong(rs.getLong("brand_id"), rs.wasNull()),
        rs.getString("brand_name"),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("role"),
        rs.getString("position"),
        rs.getString("employment_type"),
        amount(rs.getBigDecimal("base_salary")),
        rs.getString("status"),
        rs.getString("hire_date"),
        rs.getString("remark")
    );
  }

  private Long boxedLong(long value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
