package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TodoAuthorizationBoundaryTest {
  @Test
  void businessTodoListUsesPermissionAndSqlVisibilityScope() {
    AccessControlService accessControl = mock(AccessControlService.class);
    BusinessTodoRepository repository = mock(BusinessTodoRepository.class);
    BusinessTodoService service = businessService(repository, accessControl);
    AuthUser manager = user(3L, "STORE_MANAGER", "s1");
    when(accessControl.allowedStoreIds(manager, DataScopeDomains.STORE)).thenReturn(Set.of("s1"));
    when(repository.listVisible(
        1L, null, 300, "STORE_MANAGER", false, false, List.of("s1")))
        .thenReturn(List.of());

    service.list(manager, null);

    verify(accessControl).requireTodoRead(manager);
    verify(repository).listVisible(
        1L, null, 300, "STORE_MANAGER", false, false, List.of("s1"));
    verify(repository, never()).list(1L, null, 300);
  }

  @Test
  void existingBusinessTodoOutsideScopeReturnsForbidden() {
    AccessControlService accessControl = mock(AccessControlService.class);
    BusinessTodoRepository repository = mock(BusinessTodoRepository.class);
    BusinessTodoService service = businessService(repository, accessControl);
    AuthUser manager = user(3L, "STORE_MANAGER", "s1");
    when(accessControl.allowedStoreIds(manager, DataScopeDomains.STORE)).thenReturn(Set.of("s1"));
    when(repository.findVisibleById(
        1L, "todo-other", "STORE_MANAGER", false, false, List.of("s1")))
        .thenReturn(Optional.empty());
    when(repository.existsById(1L, "todo-other")).thenReturn(true);

    assertThatThrownBy(() -> service.detail(manager, "todo-other"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((BusinessException) error).getCode())
            .isEqualTo("FORBIDDEN"));
  }

  @Test
  void roleTodoRejectsRequestedStoreOutsideDataScopeBeforeQuery() {
    AccessControlService accessControl = mock(AccessControlService.class);
    RoleTodoRepository repository = mock(RoleTodoRepository.class);
    RoleTodoService service = roleService(repository, accessControl);
    AuthUser finance = user(2L, "FINANCE", null);
    when(accessControl.canAccessStore(finance, DataScopeDomains.FINANCE, "s2")).thenReturn(false);

    assertThatThrownBy(() -> service.todos(
        finance,
        RoleTodoAudience.FINANCE,
        new RoleTodoQuery(false, null, 50, null, "s2")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((BusinessException) error).getCode())
            .isEqualTo("FORBIDDEN"));

    verify(accessControl).requireTodoRead(finance);
    verify(repository, never()).profitRiskEntries(1L, null, "s2", 50);
  }

  @Test
  void roleTodoQueriesEachAllowedStoreInRepositorySql() {
    AccessControlService accessControl = mock(AccessControlService.class);
    RoleTodoRepository repository = mock(RoleTodoRepository.class);
    RoleTodoService service = roleService(repository, accessControl);
    AuthUser finance = user(2L, "FINANCE", null);
    when(accessControl.allowedStoreIds(finance, DataScopeDomains.FINANCE)).thenReturn(Set.of("s2", "s1"));

    service.todos(finance, RoleTodoAudience.FINANCE, RoleTodoQuery.defaults());

    verify(accessControl).requireTodoRead(finance);
    verify(repository).profitRiskEntries(1L, null, "s1", 50);
    verify(repository).profitRiskEntries(1L, null, "s2", 50);
    verify(repository).pendingExpenseClaims(1L, null, "s1", 50);
    verify(repository).pendingExpenseClaims(1L, null, "s2", 50);
  }

  @Test
  void roleTodoMutationRequiresTransitionPermission() {
    AccessControlService accessControl = mock(AccessControlService.class);
    RoleTodoRepository repository = mock(RoleTodoRepository.class);
    RoleTodoService service = roleService(repository, accessControl);
    AuthUser finance = user(2L, "FINANCE", null);
    when(accessControl.allowedStoreIds(finance, DataScopeDomains.STORE)).thenReturn(Set.of("s1"));

    assertThatThrownBy(() -> service.resolve(
        finance,
        RoleTodoAudience.FINANCE,
        "expense-missing",
        new RoleTodoCompletionRequest("处理", List.of())))
        .isInstanceOf(BusinessException.class);

    verify(accessControl).requireTodoTransition(finance);
    verify(accessControl).requireTodoRead(finance);
  }

  private BusinessTodoService businessService(
      BusinessTodoRepository repository,
      AccessControlService accessControl
  ) {
    return new BusinessTodoService(
        mock(FinanceRepository.class),
        repository,
        mock(RoleTodoActionRepository.class),
        accessControl,
        new BigDecimal("0.45"),
        new BigDecimal("0.20")
    );
  }

  private RoleTodoService roleService(RoleTodoRepository repository, AccessControlService accessControl) {
    return new RoleTodoService(
        repository,
        mock(RoleTodoEscalationRepository.class),
        mock(RoleTodoActionRepository.class),
        accessControl
    );
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "测试企业", role.toLowerCase(), "", role, role, storeId, true);
  }
}
