package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import org.junit.jupiter.api.Test;

class WarehouseTopologyServicePermissionTest {
  private final WarehouseTopologyRepository repository = mock(WarehouseTopologyRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final WarehouseTopologyService service = new WarehouseTopologyService(
      repository,
      accessControl,
      mock(BusinessScopeResolver.class),
      auditRepository
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

  @Test
  void disabledTransferRouteWritesPermissionDeniedAuditBeforeReturning403() {
    AuthUser boss = new AuthUser(
        8, 1, "测试企业", "boss", "", "老板", "BOSS", null, true);
    FacilityRow source = new FacilityRow(
        1, "JZ-CENTRAL", "荆州总仓", "CENTRAL", "JINGZHOU", null, null, true, true, true);
    FacilityRow target = new FacilityRow(
        2, "SD-REGIONAL", "山东分仓", "REGIONAL", "SHANDONG", 1L, "荆州总仓", false, true, true);

    assertThatThrownBy(() -> service.requireTransferRequest(boss, source, target))
        .isInstanceOf(BusinessException.class);

    verify(auditRepository).writePermissionDenied(
        boss, "申请仓间调拨", "WAREHOUSE", "2", null, "该仓间供货路线未启用");
  }
}
