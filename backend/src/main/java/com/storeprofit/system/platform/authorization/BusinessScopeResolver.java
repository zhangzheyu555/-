package com.storeprofit.system.platform.authorization;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeRepository.StoreIdentity;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Resolves request store/brand parameters against the authenticated user's effective data scope.
 * Controllers and business services must not trust a STORE_MANAGER supplied storeId or brandId.
 */
@Service
public class BusinessScopeResolver {
  private static final Logger log = LoggerFactory.getLogger(BusinessScopeResolver.class);

  private final AuthRepository authRepository;
  private final DataScopeService dataScopeService;
  private final BusinessScopeRepository repository;
  private final AuditRepository auditRepository;

  public BusinessScopeResolver(
      AuthRepository authRepository,
      DataScopeService dataScopeService,
      BusinessScopeRepository repository,
      AuditRepository auditRepository
  ) {
    this.authRepository = authRepository;
    this.dataScopeService = dataScopeService;
    this.repository = repository;
    this.auditRepository = auditRepository;
  }

  public BusinessScope sessionScope(AuthUser user) {
    if (isStoreManager(user)) {
      return requireStoreManagerScope(user, DataScopeDomains.STORE, "进入店长工作台");
    }
    DataScope dataScope = dataScopeService.scope(user, DataScopeDomains.STORE);
    String directStoreId = normalize(user == null ? null : user.storeId());
    StoreIdentity store = directStoreId == null
        ? null
        : repository.store(user.tenantId(), directStoreId).orElse(null);
    return scope(store, dataScope);
  }

  public BusinessScope resolve(
      AuthUser user,
      String domainCode,
      String requestedStoreId,
      Long requestedBrandId,
      String action
  ) {
    String domain = normalizeDomain(domainCode);
    if (isStoreManager(user)) {
      BusinessScope bound = requireStoreManagerScope(user, domain, action);
      String requestedStore = normalize(requestedStoreId);
      if (requestedStore != null && !requestedStore.equals(bound.storeId())) {
        deny(user, action, "STORE", requestedStore, requestedStore, "店长请求了其他门店");
      }
      if (requestedBrandId != null && !requestedBrandId.equals(bound.brandId())) {
        deny(user, action, "BRAND", requestedBrandId.toString(), bound.storeId(), "店长请求了其他品牌");
      }
      return bound;
    }

    DataScope dataScope = dataScopeService.scope(user, domain);
    String storeId = normalize(requestedStoreId);
    StoreIdentity store = null;
    if (storeId != null) {
      if (!AccessControlService.isBoss(user) && !dataScope.allowsStore(storeId)) {
        deny(user, action, "STORE", storeId, storeId, "门店不在当前账号的数据范围内");
      }
      store = repository.store(user.tenantId(), storeId)
          .orElseThrow(() -> new BusinessException(
              "STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST));
      if (requestedBrandId != null && requestedBrandId.longValue() != store.brandId()) {
        deny(user, action, "BRAND", requestedBrandId.toString(), storeId, "品牌与门店不匹配");
      }
    } else if (requestedBrandId != null && !repository.brandExists(user.tenantId(), requestedBrandId)) {
      throw new BusinessException("BRAND_NOT_FOUND", "品牌不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    Long brandId = requestedBrandId;
    if (brandId == null && store != null) {
      brandId = store.brandId();
    }
    return new BusinessScope(
        storeId,
        store == null ? null : store.storeName(),
        brandId,
        store == null ? null : store.brandName(),
        dataScope
    );
  }

  public BusinessScope requireStoreManagerScope(AuthUser user, String domainCode, String action) {
    String directStoreId = normalize(user == null ? null : user.storeId());
    List<String> assignments = user == null
        ? List.of()
        : authRepository.assignedStoreScope(user.tenantId(), user.id()).stream()
            .map(BusinessScopeResolver::normalize)
            .filter(value -> value != null)
            .distinct()
            .sorted()
            .toList();
    DataScope dataScope = user == null
        ? DataScope.none()
        : dataScopeService.scope(user, normalizeDomain(domainCode));
    boolean uniqueConsistentBinding = directStoreId != null
        && assignments.size() == 1
        && directStoreId.equals(assignments.getFirst());
    boolean ownStoreScope = DataScopeModes.OWN_STORE.equals(dataScope.mode())
        && dataScope.storeIds().size() == 1
        && directStoreId != null
        && directStoreId.equals(dataScope.storeIds().getFirst());
    if (!uniqueConsistentBinding || !ownStoreScope) {
      invalidManagerScope(user, action, directStoreId,
          "店长必须唯一绑定一个门店，且数据范围必须为该门店的OWN_STORE");
    }
    StoreIdentity store = repository.store(user.tenantId(), directStoreId)
        .orElseGet(() -> {
          invalidManagerScope(user, action, directStoreId, "绑定门店不存在或不属于当前企业");
          throw new IllegalStateException("unreachable");
        });
    return scope(store, dataScope);
  }

  private BusinessScope scope(StoreIdentity store, DataScope dataScope) {
    return new BusinessScope(
        store == null ? null : store.storeId(),
        store == null ? null : store.storeName(),
        store == null ? null : store.brandId(),
        store == null ? null : store.brandName(),
        dataScope
    );
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private String normalizeDomain(String value) {
    String domain = value == null ? "" : value.trim().toUpperCase();
    if (!DataScopeDomains.ALL.contains(domain)) {
      throw new IllegalArgumentException("Unknown data-scope domain: " + value);
    }
    return domain;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void deny(
      AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String reason
  ) {
    if (user != null) {
      try {
        auditRepository.writePermissionDenied(user, action, targetType, targetId, storeId, reason);
      } catch (RuntimeException ex) {
        log.warn("Failed to write business-scope denial audit for user {}: {}", user.id(), ex.getMessage());
      }
    }
    throw new BusinessException("FORBIDDEN", "当前账号只能访问绑定门店的数据", HttpStatus.FORBIDDEN);
  }

  private void invalidManagerScope(
      AuthUser user,
      String action,
      String storeId,
      String reason
  ) {
    if (user != null) {
      try {
        auditRepository.writePermissionDenied(
            user, action, "STORE_SCOPE", storeId, storeId, reason);
      } catch (RuntimeException ex) {
        log.warn("Failed to write store-manager scope configuration denial for user {}: {}",
            user.id(), ex.getMessage());
      }
    }
    throw new BusinessException(
        "STORE_MANAGER_SCOPE_INVALID",
        "店长账号必须且只能绑定一家门店，请联系老板重新配置",
        HttpStatus.FORBIDDEN
    );
  }
}
