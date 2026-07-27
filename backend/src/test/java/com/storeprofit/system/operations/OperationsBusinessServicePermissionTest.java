package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.organization.StoreBusinessGuard;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OperationsBusinessServicePermissionTest {
  private final OperationsBusinessRepository repository = mock(OperationsBusinessRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OperationsBusinessService service = new OperationsBusinessService(repository, accessControl);
  private final AuthUser user = new AuthUser(
      7L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "S-001", true);
  private final AuthUser boss = new AuthUser(
      8L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

  @Test
  void inactiveStoreCannotCreateANewInventoryCheck() {
    OperationsBusinessRepository isolatedRepository = mock(OperationsBusinessRepository.class);
    AccessControlService isolatedAccess = mock(AccessControlService.class);
    StoreBusinessGuard guard = mock(StoreBusinessGuard.class);
    AuthUser manager = new AuthUser(
        8L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "s1", true);
    doThrow(new BusinessException(
        "STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN",
        "门店已停用，不能创建新的盘存单",
        org.springframework.http.HttpStatus.CONFLICT
    )).when(guard).requireActive(manager, "s1", "盘存单");
    OperationsBusinessService guarded = new OperationsBusinessService(
        isolatedRepository, isolatedAccess, guard);

    assertThatThrownBy(() -> guarded.saveInventoryCheck(
        manager, new InventoryCheckRequest(null, "s1", "2026-07-24", "", List.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN"));

    verifyNoInteractions(isolatedRepository);
  }

  @Test
  void inventoryActionsDelegateToDedicatedAuthorizationBoundaries() {
    when(accessControl.dataScope(user, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("S-001")));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));
    when(repository.inventoryChecks(1L, null, Set.of("S-001"))).thenReturn(List.of());
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.empty());

    service.inventoryChecks(user);
    assertThatThrownBy(() -> service.saveInventoryCheck(user, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.reviewInventoryCheck(boss, 99L))
        .isInstanceOf(BusinessException.class);

    verify(accessControl).requireInventoryRead(user);
    verify(accessControl).requireInventoryManage(user);
    verify(accessControl).requireInventoryReview(boss);
  }

  @Test
  void fiveFormalRolesCanReadCatalogListAndDetailWithinWarehouseScope() {
    AuthUser finance = roleUser(20L, "finance", "财务", "FINANCE", null);
    AuthUser supervisor = roleUser(21L, "supervisor", "督导", "SUPERVISOR", null);
    AuthUser warehouse = roleUser(22L, "warehouse", "仓库", "WAREHOUSE", null);
    List<AuthUser> readers = List.of(boss, user, finance, supervisor, warehouse);
    InventoryCheckResponse check = check("S-001");
    when(repository.inventoryItems(1L)).thenReturn(List.of());
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.of(check));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));

    for (AuthUser reader : readers) {
      DataScope scope = "STORE_MANAGER".equals(reader.role())
          ? new DataScope(DataScopeModes.OWN_STORE, List.of("S-001"))
          : DataScope.all();
      when(accessControl.dataScope(reader, DataScopeDomains.WAREHOUSE)).thenReturn(scope);

      assertThat(service.inventoryItems(reader)).isEmpty();
      assertThat(service.inventoryChecks(reader)).isEmpty();
      assertThat(service.inventoryCheck(reader, 99L)).isSameAs(check);
    }

    verify(repository).inventoryChecks(1L, null, Set.of("S-001"));
    verify(repository, times(4)).inventoryChecks(1L, null);
    for (AuthUser reader : readers) {
      verify(accessControl, times(3)).requireInventoryRead(reader);
      verify(accessControl).requireStoreAccess(
          reader, DataScopeDomains.WAREHOUSE, "S-001", "查看盘存单");
    }
  }

  @Test
  void employeeCannotReachAnyInventoryReadRepository() {
    AuthUser employee = roleUser(30L, "employee", "员工", "EMPLOYEE", "S-001");

    assertThatThrownBy(() -> service.inventoryItems(employee))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.inventoryChecks(employee))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.inventoryCheck(employee, 99L))
        .isInstanceOf(BusinessException.class);

    verifyNoInteractions(repository);
  }

  @Test
  void companyRolesCanReviewButCannotEnterSubmitCancelOrMaintainPrices() {
    AuthUser finance = roleUser(40L, "finance", "财务", "FINANCE", null);
    AuthUser supervisor = roleUser(41L, "supervisor", "督导", "SUPERVISOR", null);
    AuthUser warehouse = roleUser(42L, "warehouse", "仓库", "WAREHOUSE", null);
    InventoryCheckResponse submitted = check("S-001");
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.of(submitted));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));
    when(repository.reviewInventoryCheck(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

    for (AuthUser reviewer : List.of(finance, supervisor, warehouse)) {
      assertThatThrownBy(() -> service.saveInventoryCheck(reviewer, null))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> service.submitInventoryCheck(reviewer, 99L))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> service.cancelInventoryCheck(reviewer, 99L))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> service.updateInventoryItemPrice(reviewer, "PD-001", null))
          .isInstanceOf(BusinessException.class);
      service.reviewInventoryCheck(reviewer, 99L);
      verify(repository).reviewInventoryCheck(
          1L, 99L, reviewer.id(), reviewer.displayName(), reviewer.role());
    }

    assertThatThrownBy(() -> service.reviewInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.updateInventoryItemPrice(user, "PD-001", null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.exportInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);

    assertThatThrownBy(() -> service.saveInventoryCheck(boss, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.submitInventoryCheck(boss, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.cancelInventoryCheck(boss, 99L))
        .isInstanceOf(BusinessException.class);

  }

  @Test
  void bossFinanceSupervisorAndWarehouseCanExportButManagerAndEmployeeCannot() {
    AuthUser finance = roleUser(50L, "finance", "财务", "FINANCE", null);
    AuthUser supervisor = roleUser(51L, "supervisor", "督导", "SUPERVISOR", null);
    AuthUser warehouse = roleUser(52L, "warehouse", "仓库", "WAREHOUSE", null);
    AuthUser employee = roleUser(53L, "employee", "员工", "EMPLOYEE", "S-001");
    InventoryCheckResponse check = check("S-001");
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.of(check));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));

    for (AuthUser exporter : List.of(boss, finance, supervisor, warehouse)) {
      assertThat(service.exportInventoryCheck(exporter, 99L).fileName())
          .isEqualTo("测试门店-店铺盘存-2026-07-27-PDC-TEST.xlsx");
      verify(accessControl).requireInventoryExport(exporter);
      verify(accessControl).requireInventoryRead(exporter);
      verify(accessControl).requireStoreAccess(
          exporter, DataScopeDomains.WAREHOUSE, "S-001", "查看盘存单");
    }

    assertThatThrownBy(() -> service.exportInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.exportInventoryCheck(employee, 99L))
        .isInstanceOf(BusinessException.class);
    verify(repository, times(4)).inventoryCheck(1L, 99L);
  }

  @Test
  void nonRuguoManagerCannotLoadInventoryWorkspaceOrCreateCheck() {
    AuthUser otherBrandManager =
        roleUser(60L, "bw-manager", "霸王茶姬店长", "STORE_MANAGER", "BW-001");
    when(repository.inventoryStoreName(1L, "BW-001")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.inventoryItems(otherBrandManager))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("INVENTORY_STORE_NOT_ALLOWED");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
    assertThatThrownBy(() -> service.inventoryChecks(otherBrandManager))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.inventoryCheck(otherBrandManager, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.saveInventoryCheck(
        otherBrandManager,
        new InventoryCheckRequest(
            null, "BW-001", "2026-07-27", null, List.of())))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("INVENTORY_STORE_NOT_ALLOWED");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });

    verify(repository, never()).inventoryItems(1L);
    verify(repository, never()).inventoryChecks(1L, null, Set.of("BW-001"));
    verify(repository, never()).inventoryCheck(1L, 99L);
    verify(repository, never()).saveInventoryCheck(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nonRuguoHistoricalCheckCannotBeReadExportedOrMutated() {
    AuthUser finance = roleUser(70L, "finance", "财务", "FINANCE", null);
    InventoryCheckResponse otherBrandCheck = check("RX-001");
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.of(otherBrandCheck));
    when(repository.inventoryStoreName(1L, "RX-001")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.inventoryCheck(boss, 99L))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("INVENTORY_CHECK_NOT_FOUND"));
    assertThatThrownBy(() -> service.exportInventoryCheck(finance, 99L))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("INVENTORY_CHECK_NOT_FOUND"));
    assertThatThrownBy(() -> service.submitInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.cancelInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.reviewInventoryCheck(boss, 99L))
        .isInstanceOf(BusinessException.class);

    verify(repository, never()).reviewInventoryCheck(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void storeManagerCannotReadOrWriteAnotherStoresInventoryCheck() {
    InventoryCheckResponse otherStoreCheck = check("S-002");
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "店长只能访问本门店数据", HttpStatus.FORBIDDEN);
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.of(otherStoreCheck));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("本店"));
    when(repository.inventoryStoreName(1L, "S-002")).thenReturn(Optional.of("其他茹菓门店"));
    doThrow(forbidden).when(accessControl).requireStoreAccess(
        user, DataScopeDomains.WAREHOUSE, "S-002", "查看盘存单");
    doThrow(forbidden).when(accessControl).requireStoreAccess(
        user, DataScopeDomains.WAREHOUSE, "S-002", "保存盘存单");
    doThrow(forbidden).when(accessControl).requireStoreAccess(
        user, DataScopeDomains.WAREHOUSE, "S-002", "提交盘存单");

    assertThatThrownBy(() -> service.inventoryCheck(user, 99L))
        .isSameAs(forbidden);
    assertThatThrownBy(() -> service.saveInventoryCheck(user, new InventoryCheckRequest(
        null, "S-002", "2026-07-27", null, List.of())))
        .isSameAs(forbidden);
    assertThatThrownBy(() -> service.submitInventoryCheck(user, 99L))
        .isSameAs(forbidden);

    verify(repository, never()).reviewInventoryCheck(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void examEndpointsUseLearnManageAndReportPermissions() {
    when(accessControl.dataScope(user, DataScopeDomains.EXAM)).thenReturn(DataScope.all());
    when(repository.examPapers(1L)).thenReturn(List.of());
    when(repository.examAttempts(1L, null, null)).thenReturn(List.of());
    when(repository.trainingMaterials(1L, 7L)).thenReturn(List.of());
    when(repository.learningRecords(1L, null)).thenReturn(List.of());

    service.examPapers(user);
    service.examAttempts(user);
    service.trainingMaterials(user);
    service.learningRecords(user);

    verify(accessControl).requireExamManage(user);
    verify(accessControl, times(2)).requireExamCompanyRead(user);
    verify(accessControl).requireExamRead(user);
  }

  private AuthUser roleUser(
      long id,
      String username,
      String displayName,
      String role,
      String storeId
  ) {
    return new AuthUser(
        id, 1L, "default", username, "", displayName, role, storeId, true);
  }

  private InventoryCheckResponse check(String storeId) {
    return new InventoryCheckResponse(
        99L, "PDC-TEST", storeId, "测试门店", "2026-07-27",
        "SUBMITTED", "已提交", BigDecimal.ZERO, 7L, null,
        null, null, null, null, null,
        "2026-07-27 10:00:00", "2026-07-27 10:00:00", List.of());
  }
}
