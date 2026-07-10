package com.storeprofit.system.todo;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessTodoReconcileSchedulerTest {
  private final BusinessTodoRepository repository = mock(BusinessTodoRepository.class);
  private final BusinessTodoService service = mock(BusinessTodoService.class);

  @Test
  void reconcilesEveryActiveTenantForTheRequestedMonth() {
    when(repository.activeTenantIds()).thenReturn(List.of(1L, 2L));
    BusinessTodoReconcileScheduler scheduler = new BusinessTodoReconcileScheduler(repository, service, true);

    scheduler.reconcileMonth("2026-07");

    verify(service).reconcileSystemMonth(1L, "2026-07");
    verify(service).reconcileSystemMonth(2L, "2026-07");
  }

  @Test
  void oneTenantFailureDoesNotBlockOtherTenants() {
    when(repository.activeTenantIds()).thenReturn(List.of(1L, 2L));
    doThrow(new IllegalStateException("database unavailable"))
        .when(service).reconcileSystemMonth(1L, "2026-07");
    BusinessTodoReconcileScheduler scheduler = new BusinessTodoReconcileScheduler(repository, service, true);

    scheduler.reconcileMonth("2026-07");

    verify(service).reconcileSystemMonth(2L, "2026-07");
  }

  @Test
  void disabledSchedulerDoesNotReadTenants() {
    BusinessTodoReconcileScheduler scheduler = new BusinessTodoReconcileScheduler(repository, service, false);

    scheduler.reconcileCurrentMonth();

    verify(repository, never()).activeTenantIds();
  }
}
