package com.storeprofit.system.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrganizationServicePermissionTest {
  private final OrganizationRepository repository = mock(OrganizationRepository.class);
  private final DataScopeService dataScopeService = mock(DataScopeService.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OrganizationService service = new OrganizationService(repository, dataScopeService, accessControl);
  private final AuthUser user = new AuthUser(
      7L, 1L, "default", "operator", "", "运营", "OPERATIONS", null, true);

  @Test
  void listUsesStoreReadPermissionAndRepositoryScope() {
    DataScope scope = new DataScope("STORE_LIST", List.of("rg1"));
    when(dataScopeService.scope(user, DataScopeDomains.STORE)).thenReturn(scope);
    when(repository.stores(1L, scope)).thenReturn(List.of());

    service.stores(user);

    verify(accessControl).requireStoreRead(user);
    verify(repository).stores(1L, scope);
  }

  @Test
  void updateUsesManagePermissionAndExplicitStoreScope() {
    StoreUpsertRequest request = new StoreUpsertRequest(
        "rg1", "RG1", "一店", 1L, "荆州", "店长", "2026-01-01", "营业中", "");
    when(repository.brandExists(1L, 1L)).thenReturn(true);

    service.upsertStore(user, request);

    verify(accessControl).requireStoreManage(user);
    verify(accessControl).requireStoreAccess(user, DataScopeDomains.STORE, "rg1", "维护门店档案");
    verify(repository).upsertStore(1L, request);
  }
}
