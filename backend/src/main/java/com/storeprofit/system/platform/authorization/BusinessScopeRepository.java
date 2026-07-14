package com.storeprofit.system.platform.authorization;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessScopeRepository {
  private final JdbcTemplate jdbcTemplate;

  public BusinessScopeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<StoreIdentity> store(long tenantId, String storeId) {
    if (storeId == null || storeId.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select s.id, s.name, s.brand_id, b.name as brand_name
          from store_branch s
          join brand b on b.tenant_id = s.tenant_id and b.id = s.brand_id
          where s.tenant_id = ? and s.id = ?
          """, (rs, rowNum) -> new StoreIdentity(
          rs.getString("id"),
          rs.getString("name"),
          rs.getLong("brand_id"),
          rs.getString("brand_name")
      ), tenantId, storeId.trim()));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
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

  public record StoreIdentity(String storeId, String storeName, long brandId, String brandName) {
  }
}
