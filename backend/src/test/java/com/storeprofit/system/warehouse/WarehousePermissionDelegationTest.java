package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WarehousePermissionDelegationTest {
  private final WarehouseRepository repository = mock(WarehouseRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final WarehouseService service = new WarehouseService(repository, accessControl);

  @Test
  void readUsesStoreOrCentralPermissionForTheCorrespondingWorkspace() {
    when(repository.items(1L)).thenReturn(List.of());
    AuthUser storeManager = user("OPERATIONS", "rg1");
    AuthUser warehouse = user("WAREHOUSE", null);
    when(accessControl.hasPermission(storeManager, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(storeManager, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("rg1")));
    when(accessControl.hasPermission(warehouse, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(true);
    when(accessControl.dataScope(warehouse, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, List.of()));

    service.items(storeManager);
    service.items(warehouse);

    verify(accessControl).requireWarehouseStoreRead(storeManager);
    verify(accessControl).requireWarehouseCentralRead(warehouse);
  }

  @Test
  void sensitiveActionsUseDedicatedWarehousePermissionWrappers() {
    AuthUser warehouse = user("WAREHOUSE", null);
    AuthUser storeManager = user("STORE_MANAGER", "rg1");
    RuntimeException centralDenied = new RuntimeException("central denied");
    RuntimeException createDenied = new RuntimeException("create denied");
    RuntimeException reviewDenied = new RuntimeException("review denied");
    RuntimeException receiveDenied = new RuntimeException("receive denied");
    doThrow(centralDenied).when(accessControl).requireWarehouseCentralManage(warehouse);
    doThrow(createDenied).when(accessControl).requireWarehouseRequisitionCreate(storeManager);
    doThrow(reviewDenied).when(accessControl).requireWarehouseRequisitionReview(warehouse);
    doThrow(receiveDenied).when(accessControl).requireWarehouseRequisitionReceive(storeManager);

    assertThatThrownBy(() -> service.saveItem(warehouse, null)).isSameAs(centralDenied);
    assertThatThrownBy(() -> service.createRequisition(storeManager, null)).isSameAs(createDenied);
    assertThatThrownBy(() -> service.review(warehouse, "REQ-1", null)).isSameAs(reviewDenied);
    assertThatThrownBy(() -> service.receiveByStore(storeManager, "REQ-1", null)).isSameAs(receiveDenied);
  }

  @Test
  void storeListScopeRunsOneSqlQueryPerAllowedStoreAndNeverUsesGlobalQuery() {
    AuthUser delegated = user("OPERATIONS", null);
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("rg1", "rg2")));
    when(repository.movements(1L, "rg1", 120)).thenReturn(List.of());
    when(repository.movements(1L, "rg2", 120)).thenReturn(List.of());

    service.movements(delegated);

    verify(accessControl).requireWarehouseStoreRead(delegated);
    verify(repository).movements(1L, "rg1", 120);
    verify(repository).movements(1L, "rg2", 120);
    verify(repository, never()).movements(1L, null, 120);
  }

  @Test
  void personalStoreReadDenyStopsTheRequestBeforeWarehouseQueries() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    RuntimeException denied = new RuntimeException("personal deny");
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    doThrow(denied).when(accessControl).requireWarehouseStoreRead(delegated);

    assertThatThrownBy(() -> service.items(delegated)).isSameAs(denied);

    verifyNoInteractions(repository);
  }

  @Test
  void explicitReturnIdOutsideWarehouseScopeReturnsForbidden() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    WarehouseReturnResponse otherStore = returnOrder("PSTH-OTHER", "rg2");
    BusinessException denied = new BusinessException(
        "FORBIDDEN",
        "当前账号没有访问该业务的权限",
        HttpStatus.FORBIDDEN
    );
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("rg1")));
    when(repository.returnOrder(1L, "PSTH-OTHER")).thenReturn(Optional.of(otherStore));
    doThrow(denied).when(accessControl).requireStoreAccess(
        delegated,
        DataScopeDomains.WAREHOUSE,
        "rg2",
        "查看配送退货单"
    );

    assertThatThrownBy(() -> service.returnOrder(delegated, "PSTH-OTHER"))
        .isSameAs(denied);
  }

  @Test
  void storeOverviewDoesNotQueryCentralSupplierPurchaseOrBatchData() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("rg1")));
    when(repository.items(1L)).thenReturn(List.of());
    when(repository.requisitions(1L, "rg1")).thenReturn(List.of());
    when(repository.deliveries(1L, "rg1")).thenReturn(List.of());
    when(repository.movements(1L, "rg1", 80)).thenReturn(List.of());
    when(repository.storeInventoryQuantities(1L, "rg1")).thenReturn(Map.of());

    service.overview(delegated);

    verify(repository, never()).suppliers(1L);
    verify(repository, never()).purchaseOrders(1L);
    verify(repository, never()).stockBatches(1L);
    verify(repository, never()).pendingPurchaseCount(1L);
    verify(repository, never()).pendingRequisitionCount(1L);
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(7L, 1L, "default", "tester", "", "测试账号", role, storeId, true);
  }

  private WarehouseReturnResponse returnOrder(String id, String storeId) {
    return new WarehouseReturnResponse(
        id,
        id,
        "REQ-1",
        "DEL-1",
        storeId,
        storeId,
        "仓库",
        "SUBMITTED",
        "已提交",
        java.math.BigDecimal.TEN,
        "经办人",
        "创建人",
        "修改人",
        null,
        null,
        "原因",
        "备注",
        null,
        null,
        "2026-07-11",
        null,
        null,
        "2026-07-11 10:00:00",
        "2026-07-11 10:00:00",
        0,
        0,
        List.of()
    );
  }
}
