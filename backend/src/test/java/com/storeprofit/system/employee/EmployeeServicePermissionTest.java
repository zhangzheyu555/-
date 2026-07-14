package com.storeprofit.system.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmployeeServicePermissionTest {
  @Test
  void recordsDelegatesToEmployeeReadPermission() {
    EmployeeRepository repository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    EmployeeService service = new EmployeeService(repository, accessControl);
    AuthUser finance = new AuthUser(
        7L, 1L, "default", "finance", "", "财务", "FINANCE", null, true);
    when(accessControl.hasAllDataScope(finance, DataScopeDomains.STORE)).thenReturn(true);
    when(repository.records(1L, null, null, null)).thenReturn(List.of());

    assertThat(service.records(finance, null, null, null)).isEmpty();

    verify(accessControl).requireEmployeeRead(finance);
    verify(repository).records(1L, null, null, null);
  }

  @Test
  void limitedStoreScopeIsPassedIntoRepositorySqlBoundary() {
    EmployeeRepository repository = mock(EmployeeRepository.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    EmployeeService service = new EmployeeService(repository, accessControl);
    AuthUser finance = new AuthUser(
        7L, 1L, "default", "finance", "", "财务", "FINANCE", null, true);
    Set<String> allowedStoreIds = Set.of("store-a", "store-b");
    when(accessControl.allowedStoreIds(finance, DataScopeDomains.STORE))
        .thenReturn(allowedStoreIds);
    when(repository.records(1L, null, null, null, allowedStoreIds)).thenReturn(List.of());

    assertThat(service.records(finance, null, null, null)).isEmpty();

    verify(repository).records(1L, null, null, null, allowedStoreIds);
  }
}
