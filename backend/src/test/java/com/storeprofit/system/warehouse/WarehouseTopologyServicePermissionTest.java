package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import org.junit.jupiter.api.Test;

class WarehouseTopologyServicePermissionTest {
  private final WarehouseTopologyRepository repository = mock(WarehouseTopologyRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final WarehouseTopologyService service = new WarehouseTopologyService(
      repository,
      accessControl,
      mock(BusinessScopeResolver.class),
      mock(AuditRepository.class)
  );
  private final AuthUser user = new AuthUser(
      7, 1, "测试企业", "sd-warehouse", "", "山东仓管理员", "WAREHOUSE", null, true);

  @Test
  void currentReadDenyCannotBeBypassedByLegacyReadAllow() {
    RuntimeException denied = new RuntimeException("warehouse.read denied");
    doThrow(denied).when(accessControl).requireWarehouseRead(user);

    assertThatThrownBy(() -> service.visibleFacilities(user)).isSameAs(denied);

    verify(accessControl).requireWarehouseRead(user);
    verifyNoInteractions(repository);
  }

  @Test
  void currentPurchaseDenyStopsBeforeFacilityOrLegacyPermissionChecks() {
    RuntimeException denied = new RuntimeException("warehouse.purchase denied");
    doThrow(denied).when(accessControl).requireWarehousePurchase(user);

    assertThatThrownBy(() -> service.requirePurchaseWarehouse(user, 2L, "创建采购单"))
        .isSameAs(denied);

    verify(accessControl).requireWarehousePurchase(user);
    verifyNoInteractions(repository);
  }
}
