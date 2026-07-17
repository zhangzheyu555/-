package com.storeprofit.system.qmai;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 企迈凭证配置持久化（按租户单条，upsert）。 */
@Repository
public class QmaiConfigRepository {
  private final JdbcTemplate jdbcTemplate;

  public QmaiConfigRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<QmaiConfigRow> find(long tenantId, String brand) {
    return jdbcTemplate.query("""
        select tenant_id, open_id, grant_code, open_key, base_url, version, shops,
               console_account, console_password, console_token, updated_by_name, updated_at
        from qmai_platform_config
        where tenant_id = ? and brand = ?
        """, (rs, rowNum) -> new QmaiConfigRow(
            rs.getLong("tenant_id"),
            rs.getString("open_id"),
            rs.getString("grant_code"),
            rs.getString("open_key"),
            rs.getString("base_url"),
            rs.getString("version"),
            rs.getString("shops"),
            rs.getString("console_account"),
            rs.getString("console_password"),
            rs.getString("console_token"),
            rs.getString("updated_by_name"),
            rs.getString("updated_at")), tenantId, brand)
        .stream()
        .findFirst();
  }

  public void upsert(long tenantId, String brand, String openId, String grantCode, String openKey,
      String baseUrl, String version, String shops, String consoleAccount, String consolePassword,
      String consoleToken, Long actorId, String actorName) {
    jdbcTemplate.update("""
        insert into qmai_platform_config(
          tenant_id, brand, open_id, grant_code, open_key, base_url, version, shops,
          console_account, console_password, console_token, updated_by_user_id, updated_by_name)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on duplicate key update
          open_id = values(open_id),
          grant_code = values(grant_code),
          open_key = values(open_key),
          base_url = values(base_url),
          version = values(version),
          shops = values(shops),
          console_account = values(console_account),
          console_password = values(console_password),
          console_token = values(console_token),
          updated_by_user_id = values(updated_by_user_id),
          updated_by_name = values(updated_by_name)
        """, tenantId, brand, openId, grantCode, openKey, baseUrl, version, shops,
        consoleAccount, consolePassword, consoleToken, actorId, actorName);
  }

  /** 数据库存的原始配置行。 */
  public record QmaiConfigRow(
      long tenantId,
      String openId,
      String grantCode,
      String openKey,
      String baseUrl,
      String version,
      String shops,
      String consoleAccount,
      String consolePassword,
      String consoleToken,
      String updatedByName,
      String updatedAt
  ) {}
}
