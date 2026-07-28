package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarehouseReturnFacilityScopeTest {
  private final WarehouseRepository repository = mock(WarehouseRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final BusinessScopeResolver scopeResolver = mock(BusinessScopeResolver.class);
  private final WarehouseTopologyService topologyService = mock(WarehouseTopologyService.class);
  private final WarehouseTopologyRepository topologyRepository = mock(WarehouseTopologyRepository.class);
  private final WarehouseService service = new WarehouseService(
      repository, accessControl, scopeResolver, topologyService, topologyRepository);

  @Test
  void warehouseQueryIsValidatedAndFilteredByTheSelectedReceivingWarehouse() {
    AuthUser warehouse = user("WAREHOUSE", null);
    FacilityRow shandong = facility(2L, "SD-REGIONAL", "山东分仓", "REGIONAL");
    when(topologyService.requireVisibleFacility(warehouse, 2L, "查看配送退货单"))
        .thenReturn(shandong);
    when(repository.returns(1L, null, 2L)).thenReturn(List.of());

    assertThat(service.returns(warehouse, 2L)).isEmpty();

    verify(topologyService).requireVisibleFacility(warehouse, 2L, "查看配送退货单");
    verify(repository).returns(1L, null, 2L);
  }

  @Test
  void storeManagerQueryKeepsOwnStoreIsolationInsideItsSupplyWarehouse() {
    AuthUser manager = user("STORE_MANAGER", "rg1");
    FacilityRow central = facility(1L, "JZ-CENTRAL", "荆州总仓", "CENTRAL");
    when(topologyService.requireVisibleFacility(manager, 1L, "查看配送退货单"))
        .thenReturn(central);
    when(repository.returns(1L, "rg1", 1L)).thenReturn(List.of());

    assertThat(service.returns(manager, 1L)).isEmpty();

    verify(repository).returns(1L, "rg1", 1L);
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(8L, 1L, "测试企业", "tester", "", "测试账号", role, storeId, true);
  }

  private FacilityRow facility(long id, String code, String name, String type) {
    return new FacilityRow(
        id, code, name, type, type.equals("CENTRAL") ? "JINGZHOU" : "SHANDONG",
        type.equals("CENTRAL") ? null : 1L,
        type.equals("CENTRAL") ? null : "荆州总仓",
        type.equals("CENTRAL"), true, true);
  }
}
