package com.storeprofit.system.qmai;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At 05:00 Asia/Shanghai, queues only yesterday's finalized QMAI sales for each active tenant. */
@Component
public class QmaiDailySalesSyncScheduler {
  private static final Logger log =
      LoggerFactory.getLogger(QmaiDailySalesSyncScheduler.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private final QmaiSyncRepository repository;
  private final QmaiSyncService service;
  private final Executor schedulerExecutor;
  private final boolean enabled;

  public QmaiDailySalesSyncScheduler(
      QmaiSyncRepository repository,
      QmaiSyncService service,
      @Qualifier("qmaiScheduledSyncExecutor") Executor schedulerExecutor,
      @Value("${app.qmai.daily-sync-enabled:true}") boolean enabled
  ) {
    this.repository = repository;
    this.service = service;
    this.schedulerExecutor = schedulerExecutor;
    this.enabled = enabled;
  }

  @Scheduled(cron = "${app.qmai.daily-sync-cron:0 0 5 * * *}", zone = "Asia/Shanghai")
  public void syncYesterday() {
    if (!enabled) {
      return;
    }
    LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
    for (QmaiSyncRepository.SyncTarget target : repository.activeTargets()) {
      try {
        schedulerExecutor.execute(() -> {
          try {
            service.startScheduledDate(target.tenantId(), target.brand(), yesterday);
          } catch (RuntimeException ex) {
            // One unconfigured tenant/brand must not block the remaining configured targets.
            log.warn("QMAI scheduled sales sync was not queued. tenantId={}, brand={}, date={}",
                target.tenantId(), target.brand(), yesterday, ex);
          }
        });
      } catch (RejectedExecutionException ex) {
        log.error("QMAI scheduled executor queue is full. tenantId={}, brand={}, date={}",
            target.tenantId(), target.brand(), yesterday, ex);
      }
    }
  }
}
