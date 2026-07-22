package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InspectionServicePermissionTest {
  private final InspectionRecordRepository repository = mock(InspectionRecordRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final InspectionService service = new InspectionService(
      repository,
      accessControl,
      "http://127.0.0.1:8000/detect",
      "http://127.0.0.1:8000/export",
      Duration.ofSeconds(1)
  );
  private final AuthUser user = new AuthUser(
      7L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);

  @Test
  void recordReadDelegatesToInspectionReadPermission() {
    assertThatThrownBy(() -> service.record(user, "missing"))
        .isInstanceOf(BusinessException.class);

    verify(accessControl).requireInspectionRead(user);
    verify(repository).record(1L, "missing");
  }

  @Test
  void recordWriteDelegatesToInspectionManagePermissionBeforeValidation() {
    assertThatThrownBy(() -> service.save(user, null, null))
        .isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.getCode()).isEqualTo("BAD_REQUEST"));

    verify(accessControl).requireInspectionManage(user);
  }

  @Test
  void recordsPassLimitedInspectionScopeToRepositorySql() {
    Set<String> allowedStoreIds = Set.of("store-a", "store-b");
    when(accessControl.allowedStoreIds(user, DataScopeDomains.INSPECTION))
        .thenReturn(allowedStoreIds);
    when(repository.records(1L, null, null, null, null, null, allowedStoreIds))
        .thenReturn(List.of());

    assertThat(service.records(user, null, null, null, null, null)).isEmpty();

    verify(repository).records(1L, null, null, null, null, null, allowedStoreIds);
  }
}
