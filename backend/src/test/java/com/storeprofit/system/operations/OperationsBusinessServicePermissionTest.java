package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.organization.StoreBusinessGuard;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperationsBusinessServicePermissionTest {
  private final OperationsBusinessRepository repository = mock(OperationsBusinessRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OperationsBusinessService service = new OperationsBusinessService(repository, accessControl);
  private final AuthUser user = new AuthUser(
      7L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);

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
  void inventoryActionsUseReadManageAndReviewPermissions() {
    when(accessControl.dataScope(user, DataScopeDomains.WAREHOUSE)).thenReturn(DataScope.all());
    when(repository.inventoryChecks(1L, null)).thenReturn(List.of());
    when(repository.inventoryCheck(1L, 99L)).thenReturn(Optional.empty());

    service.inventoryChecks(user);
    assertThatThrownBy(() -> service.saveInventoryCheck(user, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.reviewInventoryCheck(user, 99L))
        .isInstanceOf(BusinessException.class);

    verify(accessControl).requirePermission(user, PermissionCodes.INVENTORY_READ, "查看门店盘存单");
    verify(accessControl).requirePermission(user, PermissionCodes.INVENTORY_MANAGE, "保存门店盘存单");
    verify(accessControl).requirePermission(user, PermissionCodes.INVENTORY_REVIEW, "复核门店盘存单");
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
}
