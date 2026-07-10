package com.storeprofit.system.todo;

import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BusinessTodoReconcileScheduler {
  private static final Logger log = LoggerFactory.getLogger(BusinessTodoReconcileScheduler.class);

  private final BusinessTodoRepository repository;
  private final BusinessTodoService service;
  private final boolean enabled;

  public BusinessTodoReconcileScheduler(
      BusinessTodoRepository repository,
      BusinessTodoService service,
      @Value("${app.exception.auto-reconcile-enabled:true}") boolean enabled
  ) {
    this.repository = repository;
    this.service = service;
    this.enabled = enabled;
  }

  @Scheduled(
      initialDelayString = "${app.exception.auto-reconcile-initial-delay-ms:60000}",
      fixedDelayString = "${app.exception.auto-reconcile-delay-ms:3600000}"
  )
  public void reconcileCurrentMonth() {
    if (!enabled) {
      return;
    }
    reconcileMonth(YearMonth.now().toString());
  }

  void reconcileMonth(String month) {
    List<Long> tenantIds = repository.activeTenantIds();
    for (Long tenantId : tenantIds) {
      try {
        service.reconcileSystemMonth(tenantId, month);
      } catch (RuntimeException ex) {
        log.error("Business todo auto reconcile failed. tenantId={}, month={}", tenantId, month, ex);
      }
    }
  }
}
