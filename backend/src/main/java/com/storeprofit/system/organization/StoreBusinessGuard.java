package com.storeprofit.system.organization;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One backend policy boundary for creating new store-scoped business documents.
 * Historical reads and transitions of existing documents deliberately do not pass through this guard.
 */
@Service
public class StoreBusinessGuard {
  private static final Logger log = LoggerFactory.getLogger(StoreBusinessGuard.class);

  private final JdbcTemplate jdbcTemplate;
  private final AuditRepository auditRepository;

  public StoreBusinessGuard(JdbcTemplate jdbcTemplate, AuditRepository auditRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditRepository = auditRepository;
  }

  public void requireActive(AuthUser user, String storeId, String documentLabel) {
    String normalizedStoreId = storeId == null ? "" : storeId.trim();
    String label = documentLabel == null || documentLabel.isBlank() ? "业务单据" : documentLabel.trim();
    Optional<StoreStatusRow> store = jdbcTemplate.query("""
        select id, name, status
        from store_branch
        where tenant_id = ? and id = ?
        """, (rs, rowNum) -> new StoreStatusRow(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("status")
    ), user.tenantId(), normalizedStoreId).stream().findFirst();
    if (store.isEmpty()) {
      throw new BusinessException(
          "STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    if (active(store.get().status())) {
      return;
    }
    String action = "创建" + label;
    try {
      auditRepository.writePermissionDenied(
          user,
          action,
          "STORE",
          store.get().id(),
          store.get().id(),
          "门店已停用，禁止创建新的" + label
      );
    } catch (RuntimeException exception) {
      log.warn("Failed to audit inactive-store denial for store {} and user {}: {}",
          store.get().id(), user.id(), exception.getMessage());
    }
    throw new BusinessException(
        "STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN",
        "门店“" + store.get().name() + "”已停用，不能创建新的" + label + "；重新启用门店后可恢复。",
        HttpStatus.CONFLICT
    );
  }

  private boolean active(String status) {
    String value = status == null ? "" : status.trim();
    return value.isBlank()
        || "营业中".equals(value)
        || "正常".equals(value)
        || "ACTIVE".equalsIgnoreCase(value);
  }

  private record StoreStatusRow(String id, String name, String status) {
  }
}
