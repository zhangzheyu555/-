package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
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
      7L, 1L, "default", "operator", "", "运营", "OPERATIONS", null, true);

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
