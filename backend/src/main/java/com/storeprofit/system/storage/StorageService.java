package com.storeprofit.system.storage;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageService {
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
  public void set(String key, String value) {
    jdbcTemplate.update("""
        insert into kv_storage(storage_key, storage_value, updated_at)
        values (?, ?, current_timestamp)
        on duplicate key update
          storage_value = values(storage_value),
          updated_at = current_timestamp
        """, key, value);
  }
}
