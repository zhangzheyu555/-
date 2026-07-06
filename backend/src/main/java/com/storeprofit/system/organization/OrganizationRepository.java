package com.storeprofit.system.organization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {
  private final JdbcTemplate jdbcTemplate;

  public OrganizationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<BrandResponse> brands() {
    return jdbcTemplate.query("""
        select id, code, name, color, sort_order
        from brand
        order by sort_order, id
        """, this::mapBrand);
  }

  public List<StoreResponse> stores() {
    return jdbcTemplate.query("""
        select s.id, s.code, s.name, s.brand_id, b.name as brand_name, s.area, s.manager,
               date_format(s.open_date, '%Y-%m-%d') as open_date, s.status, s.note
        from store_branch s
        left join brand b on b.id = s.brand_id
        order by b.sort_order, s.code, s.id
        """, this::mapStore);
  }

  public long ensureBrand(String code, String name, String color, int sortOrder) {
    jdbcTemplate.update("""
        insert into brand(code, name, color, sort_order, created_at)
        values (?, ?, ?, ?, current_timestamp)
        on duplicate key update
          name = values(name),
          color = values(color),
          sort_order = values(sort_order)
        """, code, name, color, sortOrder);
    Long id = jdbcTemplate.queryForObject("select id from brand where code = ?", Long.class, code);
    return id == null ? 0L : id;
  }

  public void upsertStore(StoreUpsertRequest request) {
    jdbcTemplate.update("""
        insert into store_branch(id, brand_id, code, name, area, manager, open_date, status, note, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          brand_id = values(brand_id),
          code = values(code),
          name = values(name),
          area = values(area),
          manager = values(manager),
          open_date = values(open_date),
          status = values(status),
          note = values(note),
          updated_at = current_timestamp
        """,
        request.id(),
        request.brandId(),
        blankToNull(request.code()),
        request.name(),
        blankToNull(request.area()),
        blankToNull(request.manager()),
        blankToNull(request.openDate()),
        request.status() == null || request.status().isBlank() ? "营业中" : request.status(),
        blankToNull(request.note())
    );
  }

  public int brandCount() {
    Integer count = jdbcTemplate.queryForObject("select count(*) from brand", Integer.class);
    return count == null ? 0 : count;
  }

  public int storeCount() {
    Integer count = jdbcTemplate.queryForObject("select count(*) from store_branch", Integer.class);
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

  public void addUserStoreScope(long userId, String storeId) {
    jdbcTemplate.update("""
        insert ignore into user_store_scope(user_id, store_id, created_at)
        values (?, ?, current_timestamp)
        """, userId, storeId);
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
        rs.getString("note")
    );
  }

  private Object blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
