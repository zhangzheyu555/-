package com.storeprofit.system.platform.auth;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WeChatMiniProgramRepository {
  private final JdbcTemplate jdbcTemplate;

  public WeChatMiniProgramRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<Long> boundUserId(long tenantId, String appId, String openid) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select auth_user_id
          from wechat_mini_program_binding
          where tenant_id = ? and app_id = ? and openid = ?
          """, Long.class, tenantId, appId, openid));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public boolean isBound(long tenantId, long userId, String appId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from wechat_mini_program_binding
        where tenant_id = ? and auth_user_id = ? and app_id = ?
        """, Integer.class, tenantId, userId, appId);
    return count != null && count > 0;
  }

  public void bind(long tenantId, long userId, String appId, String openid, String unionid) {
    jdbcTemplate.update("""
        delete from wechat_mini_program_binding
        where tenant_id = ? and auth_user_id = ? and app_id = ?
        """, tenantId, userId, appId);
    jdbcTemplate.update("""
        insert into wechat_mini_program_binding(
          tenant_id, auth_user_id, app_id, openid, unionid, bound_at
        ) values (?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, userId, appId, openid, unionid);
  }
}
