package com.storeprofit.system.storage;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageService {
  private static final Set<String> ALLOWED_WRITE_KEYS = Set.of(
      "stores",
      "entries",
      "salary",
      "expenses",
      "inspections",
      "logs",
      "schema_v"
  );
  private static final Set<String> BLOCKED_WRITE_KEYS = Set.of(
      "accounts",
      "app_pin",
      "tokens",
      "passwords"
  );

  private final JdbcTemplate jdbcTemplate;

  public StorageService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<String> get(String key) {
    try {
      String value = jdbcTemplate.queryForObject(
          "select storage_value from kv_storage where storage_key = ?",
          String.class,
          key
      );
      return Optional.ofNullable(value);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  @Transactional
  public void set(AuthUser user, String key, String value) {
    String normalizedKey = normalizeKey(key);
    requireBoss(user);
    requireAllowedKey(normalizedKey);
    jdbcTemplate.update("""
        insert into kv_storage(storage_key, storage_value, updated_at)
        values (?, ?, current_timestamp)
        on duplicate key update
          storage_value = values(storage_value),
          updated_at = current_timestamp
        """, normalizedKey, value);
    logLegacyWrite(user, normalizedKey);
  }

  private void requireBoss(AuthUser user) {
    if (!Set.of("BOSS", "ADMIN").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅老板可写入 legacy KV 存储", HttpStatus.FORBIDDEN);
    }
  }

  private void requireAllowedKey(String key) {
    if (BLOCKED_WRITE_KEYS.contains(key)) {
      throw new BusinessException("LEGACY_STORAGE_KEY_BLOCKED", "该 legacy KV key 禁止写入", HttpStatus.FORBIDDEN);
    }
    if (!ALLOWED_WRITE_KEYS.contains(key)) {
      throw new BusinessException("LEGACY_STORAGE_KEY_NOT_ALLOWED", "该 legacy KV key 不在允许写入列表中", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeKey(String key) {
    String normalized = key == null ? "" : key.trim().toLowerCase();
    if (normalized.isBlank()) {
      throw new BusinessException("BAD_STORAGE_KEY", "key must not be blank", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private void logLegacyWrite(AuthUser user, String key) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, reason, created_at)
        values (?, ?, ?, 'legacy_storage_write', 'kv_storage', ?, 'legacy KV write via /api/storage', current_timestamp)
        """, user.tenantId(), user.id(), user.displayName(), key);
  }
}
