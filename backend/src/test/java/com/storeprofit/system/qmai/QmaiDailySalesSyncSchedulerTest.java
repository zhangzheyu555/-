package com.storeprofit.system.qmai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class QmaiDailySalesSyncSchedulerTest {
  @Test
  void fiveAmJobSubmitsOnlyShanghaiYesterday() {
    QmaiSyncRepository repository = mock(QmaiSyncRepository.class);
    QmaiSyncService service = mock(QmaiSyncService.class);
    when(repository.activeTargets()).thenReturn(List.of(
        new QmaiSyncRepository.SyncTarget(1L, "ruguo")));
    QmaiDailySalesSyncScheduler scheduler = new QmaiDailySalesSyncScheduler(
        repository, service, Runnable::run, true);

    scheduler.syncYesterday();

    verify(service).startScheduledDate(
        1L, "ruguo", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
  }

  @Test
  void rejectedDedicatedExecutorDoesNotEscapeScheduledInvocation() {
    QmaiSyncRepository repository = mock(QmaiSyncRepository.class);
    QmaiSyncService service = mock(QmaiSyncService.class);
    when(repository.activeTargets()).thenReturn(List.of(
        new QmaiSyncRepository.SyncTarget(1L, "ruguo")));
    QmaiDailySalesSyncScheduler scheduler = new QmaiDailySalesSyncScheduler(
        repository, service, command -> {
          throw new RejectedExecutionException("full");
        }, true);

    assertDoesNotThrow(scheduler::syncYesterday);
    verifyNoInteractions(service);
  }
}
