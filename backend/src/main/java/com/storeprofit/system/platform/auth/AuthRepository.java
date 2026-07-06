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

  public Optional<AuthUser> findByUsername(String username) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select id, username, password_hash, display_name, role, store_id, enabled
          from auth_user
          where username = ?
          """, this::mapUser, username));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<AuthUser> findByToken(String token) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select u.id, u.username, u.password_hash, u.display_name, u.role, u.store_id, u.enabled
          from auth_token t
          join auth_user u on u.id = t.user_id
          where t.token = ? and t.expires_at > current_timestamp and u.enabled = 1
          """, this::mapUser, token));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<AuthUser> users() {
    return jdbcTemplate.query("""
        select id, username, password_hash, display_name, role, store_id, enabled
        from auth_user
        order by id
        """, this::mapUser);
  }

  public void createToken(String token, long userId, OffsetDateTime expiresAt) {
    jdbcTemplate.update("delete from auth_token where expires_at <= current_timestamp");
    jdbcTemplate.update("""
        insert into auth_token(token, user_id, expires_at, created_at)
        values (?, ?, ?, current_timestamp)
        """, token, userId, java.sql.Timestamp.from(expiresAt.toInstant()));
  }

  public void deleteToken(String token) {
    jdbcTemplate.update("delete from auth_token where token = ?", token);
  }

  public List<String> storeScope(long userId, String role, String directStoreId) {
    if ("ADMIN".equals(role) || "BOSS".equals(role) || "FINANCE".equals(role)) {
      return List.of("all");
    }
    List<String> scoped = jdbcTemplate.queryForList(
        "select store_id from user_store_scope where user_id = ? order by store_id",
        String.class,
        userId
    );
    if (!scoped.isEmpty()) {
      return scoped;
    }
    return directStoreId == null || directStoreId.isBlank() ? List.of() : List.of(directStoreId);
  }

  public boolean userExists(String username) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from auth_user where username = ?", Integer.class, username);
    return count != null && count > 0;
  }

  public void createUser(String username, String passwordHash, String displayName, String role, String storeId) {
    jdbcTemplate.update("""
        insert into auth_user(username, password_hash, display_name, role, store_id, enabled, created_at)
        values (?, ?, ?, ?, ?, 1, current_timestamp)
        """, username, passwordHash, displayName, role, storeId);
  }

  private AuthUser mapUser(ResultSet rs, int rowNum) throws SQLException {
    return new AuthUser(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getString("display_name"),
        rs.getString("role"),
        rs.getString("store_id"),
        rs.getBoolean("enabled")
    );
  }
}
