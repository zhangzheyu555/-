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
    return organizationRepository.brands();
  }

  public List<StoreResponse> stores(AuthUser user) {
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null) {
      return organizationRepository.stores().stream()
          .filter(store -> user.storeId().equals(store.id()))
          .toList();
    }
    return organizationRepository.stores();
  }

  public void upsertStore(AuthUser user, StoreUpsertRequest request) {
    requireAdmin(user);
    organizationRepository.upsertStore(request);
  }

  private void requireAdmin(AuthUser user) {
    if (!"ADMIN".equals(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅管理员可维护门店档案", HttpStatus.FORBIDDEN);
    }
  }
}
