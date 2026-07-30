package com.storeprofit.system.organization;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrganizationServicePermissionTest {
  private final OrganizationRepository repository = mock(OrganizationRepository.class);
  private final DataScopeService dataScopeService = mock(DataScopeService.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OrganizationService service = new OrganizationService(repository, dataScopeService, accessControl);
  private final AuthUser user = new AuthUser(
      7L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);

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
  void knowledgeBaseStoreOptionsUseTheDedicatedManagementScope() {
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("rg1"));
    when(accessControl.knowledgeBaseManagementStoreScope(user)).thenReturn(scope);
    when(repository.stores(1L, scope)).thenReturn(List.of());

    service.knowledgeBaseStores(user);

    verify(accessControl).requireKnowledgeBaseManage(user);
    verify(accessControl).knowledgeBaseManagementStoreScope(user);
    verify(repository).stores(1L, scope);
  }

  @Test
  void inventoryReductionsRequireStoreReadAndExplicitStoreScope() {
    StoreResponse store = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "2026-01-01", "营业中", "");
    LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 8, 1, 0, 0);
    when(repository.store(1L, "rg1")).thenReturn(java.util.Optional.of(store));
    when(repository.inventoryReductionSummary(1L, "rg1", start, end))
        .thenReturn(new OrganizationRepository.InventoryReductionSummary(2, 2));
    when(repository.inventoryReductions(1L, "rg1", start, end, 100)).thenReturn(List.of());

    StoreInventoryReductionResponse response =
        service.inventoryReductions(user, "rg1", "2026-07", 100);

    assertThat(response.storeId()).isEqualTo("rg1");
    assertThat(response.movementCount()).isEqualTo(2);
    assertThat(response.truncated()).isTrue();
    verify(accessControl).requireStoreRead(user);
    verify(accessControl).requireStoreAccess(
        user, DataScopeDomains.STORE, "rg1", "查看门店库存减少记录");
    verify(repository).inventoryReductions(1L, "rg1", start, end, 100);
  }

  @Test
  void inventoryReductionsRejectCrossStoreAccessBeforeReadingMovements() {
    StoreResponse store = new StoreResponse(
        "rg2", "RG2", "二店", 1L, "如果", "荆州", "店长", "2026-01-01", "营业中", "");
    when(repository.store(1L, "rg2")).thenReturn(java.util.Optional.of(store));
    doThrow(new BusinessException("FORBIDDEN", "无权访问该门店", org.springframework.http.HttpStatus.FORBIDDEN))
        .when(accessControl)
        .requireStoreAccess(user, DataScopeDomains.STORE, "rg2", "查看门店库存减少记录");

    assertThatThrownBy(() -> service.inventoryReductions(user, "rg2", "2026-07", 100))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(repository, never()).inventoryReductionSummary(
        1L,
        "rg2",
        LocalDateTime.of(2026, 7, 1, 0, 0),
        LocalDateTime.of(2026, 8, 1, 0, 0)
    );
  }

  @Test
  void updateUsesManagePermissionAndExplicitStoreScope() {
    StoreUpsertRequest request = new StoreUpsertRequest(
        "rg1", "RG1", "一店", 1L, "荆州", null, "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, "e1", "rg1", 0L);
    StoreUpsertRequest normalized = new StoreUpsertRequest(
        "rg1", "RG1", "一店", 1L, "荆州", "店长", "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, "e1", "rg1", 0L);
    StoreResponse existing = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, null,
        "e1", "rg1", "一店", 0L);
    StoreResponse updated = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "13800138000",
        "2026-01-01", "营业中", "", "JINGZHOU", null, null,
        "e1", "rg1", "一店", 1L);
    when(repository.store(1L, "rg1")).thenReturn(java.util.Optional.of(existing), java.util.Optional.of(updated));
    when(repository.brandExists(1L, 1L)).thenReturn(true);
    when(repository.manager(1L, "e1")).thenReturn(java.util.Optional.of(
        new OrganizationRepository.ManagerReference(
            "e1", "店长", "13800138000", "rg2", "二店", "在职")));
    when(repository.updateStore(1L, normalized, null, 0L)).thenReturn(1);
    when(repository.assignEmployeeAsStoreManager(
        1L, "e1", "rg1", "一店", "如果")).thenReturn(1);

    service.updateStore(user, request);

    verify(accessControl).requireStoreManage(user);
    verify(accessControl).requireStoreAccess(user, DataScopeDomains.STORE, "rg1", "维护门店档案");
    verify(accessControl, never()).requireStoreAccess(
        user, DataScopeDomains.STORE, "rg2", "选择门店负责人");
    verify(repository).updateStore(1L, normalized, null, 0L);
    verify(repository).assignEmployeeAsStoreManager(
        1L, "e1", "rg1", "一店", "如果");
  }

  @Test
  void softDeleteUsesManagePermissionAndExplicitStoreScope() {
    StoreResponse store = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "2026-01-01", "停用", "");
    when(repository.store(1L, "rg1")).thenReturn(java.util.Optional.of(store));
    when(repository.softDeleteStore(1L, "rg1", 7L, 0L)).thenReturn(1);

    service.deleteStore(user, "rg1", 0L);

    verify(accessControl).requireStoreManage(user);
    verify(accessControl).requireStoreAccess(user, DataScopeDomains.STORE, "rg1", "软删除门店档案");
    verify(repository).softDeleteStore(1L, "rg1", 7L, 0L);
  }

  @Test
  void softDeleteRequiresTheStoreToBeInactive() {
    StoreResponse store = new StoreResponse(
        "rg1", "RG1", "一店", 1L, "如果", "荆州", "店长", "2026-01-01", "营业中", "");
    when(repository.store(1L, "rg1")).thenReturn(java.util.Optional.of(store));
    assertThatThrownBy(() -> service.deleteStore(user, "rg1", 0L))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_SOFT_DELETE_REQUIRES_INACTIVE"));

    verify(repository, never()).softDeleteStore(1L, "rg1", 7L, 0L);
  }

  @Test
  void storeManagerCannotMaintainStoreEvenThroughTheServiceFallback() {
    OrganizationService constrainedService = new OrganizationService(repository);
    AuthUser manager = new AuthUser(
        8L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "rg1", true);
    StoreUpsertRequest request = new StoreUpsertRequest(
        "rg1", "RG1", "一店", 1L, "荆州", "店长", "2026-01-01", "营业中", "");

    assertThatThrownBy(() -> constrainedService.upsertStore(manager, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verifyNoInteractions(repository);
  }

  @Test
  void storeManagerCanStillReadOnlyTheBoundStore() {
    OrganizationService constrainedService = new OrganizationService(repository);
    AuthUser manager = new AuthUser(
        8L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "rg1", true);
    DataScope ownStore = new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"));
    when(repository.stores(1L, ownStore)).thenReturn(List.of());

    assertThat(constrainedService.stores(manager)).isEmpty();

    verify(repository).stores(1L, ownStore);
  }
}
