package com.storeprofit.system.platform.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {
  private final JdbcTemplate jdbcTemplate;

  public AuthRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<AuthUser> findByUsername(long tenantId, String username) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select u.id, u.tenant_id, t.name as tenant_name, u.username, u.password_hash,
                 u.display_name, u.role, u.store_id, u.enabled
          from auth_user u
          join tenant t on t.id = u.tenant_id
          where u.tenant_id = ? and u.username = ? and t.status = 'ACTIVE'
          """, this::mapUser, tenantId, username));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<AuthUser> findByToken(String token) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select u.id, u.tenant_id, ten.name as tenant_name, u.username, u.password_hash,
                 u.display_name, u.role, u.store_id, u.enabled
          from auth_token t
          join auth_user u on u.id = t.user_id
          join tenant ten on ten.id = u.tenant_id
          where t.token = ?
            and t.tenant_id = u.tenant_id
            and t.expires_at > current_timestamp
            and u.enabled = 1
            and ten.status = 'ACTIVE'
          """, this::mapUser, token));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<AuthUser> users(long tenantId) {
    return jdbcTemplate.query("""
        select u.id, u.tenant_id, t.name as tenant_name, u.username, u.password_hash,
               u.display_name, u.role, u.store_id, u.enabled
        from auth_user u
        join tenant t on t.id = u.tenant_id
        where u.tenant_id = ?
        order by u.id
        """, this::mapUser, tenantId);
  }

  public void createToken(String token, long tenantId, long userId, OffsetDateTime expiresAt) {
    jdbcTemplate.update("delete from auth_token where expires_at <= current_timestamp");
    jdbcTemplate.update("""
        insert into auth_token(token, tenant_id, user_id, expires_at, created_at)
        values (?, ?, ?, ?, current_timestamp)
        """, token, tenantId, userId, java.sql.Timestamp.from(expiresAt.toInstant()));
  }

  public void deleteToken(String token) {
    jdbcTemplate.update("delete from auth_token where token = ?", token);
  }

  public List<String> storeScope(long tenantId, long userId, String role, String directStoreId) {
    if ("ADMIN".equals(role) || "BOSS".equals(role) || "FINANCE".equals(role)) {
      return List.of("all");
    }
    List<String> scoped = jdbcTemplate.queryForList(
        "select store_id from user_store_scope where tenant_id = ? and user_id = ? order by store_id",
        String.class,
        tenantId,
        userId
    );
    if (!scoped.isEmpty()) {
      return scoped;
    }
    return directStoreId == null || directStoreId.isBlank() ? List.of() : List.of(directStoreId);
  }

  public boolean userExists(long tenantId, String username) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from auth_user where tenant_id = ? and username = ?",
        Integer.class,
        tenantId,
        username
    );
    return count != null && count > 0;
  }

  public void createUser(long tenantId, String username, String passwordHash, String displayName, String role, String storeId) {
    jdbcTemplate.update("""
        insert into auth_user(tenant_id, username, password_hash, display_name, role, store_id, enabled, created_at)
        values (?, ?, ?, ?, ?, ?, 1, current_timestamp)
        """, tenantId, username, passwordHash, displayName, role, storeId);
  }

  private AuthUser mapUser(ResultSet rs, int rowNum) throws SQLException {
    return new AuthUser(
        rs.getLong("id"),
        rs.getLong("tenant_id"),
        rs.getString("tenant_name"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getString("display_name"),
        rs.getString("role"),
        rs.getString("store_id"),
        rs.getBoolean("enabled")
    );
  }
}
