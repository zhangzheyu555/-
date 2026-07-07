package com.storeprofit.system.organization;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrganizationService {
  private final OrganizationRepository organizationRepository;

  public OrganizationService(OrganizationRepository organizationRepository) {
    this.organizationRepository = organizationRepository;
  }

  public List<BrandResponse> brands(AuthUser user) {
    return organizationRepository.brands(user.tenantId());
  }

  public List<StoreResponse> stores(AuthUser user) {
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null) {
      return organizationRepository.stores(user.tenantId()).stream()
          .filter(store -> user.storeId().equals(store.id()))
          .toList();
    }
    return organizationRepository.stores(user.tenantId());
  }

  public void upsertStore(AuthUser user, StoreUpsertRequest request) {
    requireAdmin(user);
    if (organizationRepository.storeIdBelongsToOtherTenant(user.tenantId(), request.id())) {
      throw new BusinessException("STORE_CONFLICT", "门店ID已被其他企业使用", HttpStatus.CONFLICT);
    }
    if (!organizationRepository.brandExists(user.tenantId(), request.brandId())) {
      throw new BusinessException("BRAND_NOT_FOUND", "品牌不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    organizationRepository.upsertStore(user.tenantId(), request);
  }

  private void requireAdmin(AuthUser user) {
    if (!List.of("ADMIN", "BOSS").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅老板可维护门店档案", HttpStatus.FORBIDDEN);
    }
  }
}
