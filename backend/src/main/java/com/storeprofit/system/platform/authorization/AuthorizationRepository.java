package com.storeprofit.system.platform.authorization;

import com.storeprofit.system.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuthorizationRepository {
  private final JdbcTemplate jdbcTemplate;

  public AuthorizationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<PermissionCatalogEntry> catalog() {
    return jdbcTemplate.query("""
        select permission_code, module_code, permission_name, description,
               risk_level, enabled, sort_order
        from permission_catalog
        order by sort_order, permission_code
        """, (rs, rowNum) -> new PermissionCatalogEntry(
        rs.getString("permission_code"),
        rs.getString("module_code"),
        rs.getString("permission_name"),
        rs.getString("description"),
        rs.getString("risk_level"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order")
    ));
  }

  public Set<String> enabledPermissionCodes() {
    return new LinkedHashSet<>(jdbcTemplate.queryForList("""
        select permission_code
        from permission_catalog
        where enabled = 1
        order by sort_order, permission_code
        """, String.class));
  }

  public Set<String> roleTemplatePermissions(long tenantId, String role) {
    return new LinkedHashSet<>(jdbcTemplate.queryForList("""
        select rp.permission_code
        from role_permission rp
        join permission_catalog pc on pc.permission_code = rp.permission_code and pc.enabled = 1
        where rp.tenant_id = ? and rp.role_code = ?
        order by pc.sort_order, rp.permission_code
        """, String.class, tenantId, role));
  }

  public List<UserPermissionOverride> userOverrides(long tenantId, long userId) {
    return jdbcTemplate.query("""
        select permission_code, effect
        from user_permission_override
        where tenant_id = ? and user_id = ?
        order by permission_code
        """, (rs, rowNum) -> new UserPermissionOverride(
        rs.getString("permission_code"),
        PermissionEffect.valueOf(rs.getString("effect").trim().toUpperCase())
    ), tenantId, userId);
  }

  @Transactional
  public void replaceUserOverrides(
      long tenantId,
      long userId,
      List<UserPermissionOverride> overrides,
      Long actorId
  ) {
    jdbcTemplate.update(
        "delete from user_permission_override where tenant_id = ? and user_id = ?",
        tenantId,
        userId
    );
    if (overrides == null) {
      return;
    }
    for (UserPermissionOverride override : overrides) {
      jdbcTemplate.update("""
          insert into user_permission_override(
            tenant_id, user_id, permission_code, effect, created_by, created_at, updated_at
          ) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """,
          tenantId,
          userId,
          override.permissionCode(),
          override.effect().name(),
          actorId
      );
    }
  }

  @Transactional
  public long incrementPermissionVersionAndDeleteTokens(long tenantId, long userId) {
    int updated = jdbcTemplate.update("""
        update auth_user
        set permission_version = permission_version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, tenantId, userId);
    if (updated == 0) {
      throw new BusinessException("USER_NOT_FOUND", "未找到账号", HttpStatus.NOT_FOUND);
    }
    jdbcTemplate.update(
        "delete from auth_token where tenant_id = ? and user_id = ?",
        tenantId,
        userId
    );
    Long version = jdbcTemplate.queryForObject("""
        select permission_version from auth_user where tenant_id = ? and id = ?
        """, Long.class, tenantId, userId);
    return version == null ? 1L : version;
  }
}
