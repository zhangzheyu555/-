package com.storeprofit.system.qmai;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class QmaiSyncWorker {
  private static final Logger log = LoggerFactory.getLogger(QmaiSyncWorker.class);
  private final QmaiClient client;
  private final QmaiRepository repository;
  private final Executor fetchExecutor;

  public QmaiSyncWorker(QmaiClient client, QmaiRepository repository,
      @Qualifier("qmaiFetchExecutor") Executor fetchExecutor) {
    this.client = client;
    this.repository = repository;
    this.fetchExecutor = fetchExecutor;
  }

  @Async("qmaiSyncExecutor")
  public void run(long tenantId, long batchId, List<QmaiModels.ShopMapping> mappings, List<LocalDate> dates) {
    try {
      repository.markRunning(batchId);
      List<CompletableFuture<TaskResult>> tasks = new ArrayList<>();
      for (QmaiModels.ShopMapping mapping : mappings) {
        for (LocalDate date : dates) {
          tasks.add(CompletableFuture.supplyAsync(() -> fetch(tenantId, batchId, mapping, date), fetchExecutor));
        }
      }
      AtomicInteger completed = new AtomicInteger();
      AtomicInteger failed = new AtomicInteger();
      AtomicInteger dailyRows = new AtomicInteger();
      AtomicInteger productRows = new AtomicInteger();
      List<String> errors = new ArrayList<>();
      for (CompletableFuture<TaskResult> task : tasks) {
        TaskResult result = task.join();
        completed.incrementAndGet();
        if (result.error() != null) {
          failed.incrementAndGet();
          if (errors.size() < 8) {
            errors.add(result.error());
          }
        } else {
          dailyRows.incrementAndGet();
          productRows.addAndGet(result.productRows());
        }
        repository.markProgress(batchId, completed.get(), failed.get(), dailyRows.get(), productRows.get());
      }
      String status = failed.get() == 0 ? "SUCCEEDED" : (dailyRows.get() == 0 ? "FAILED" : "PARTIAL");
      repository.markFinished(batchId, status, errors.isEmpty() ? null : String.join("；", errors));
    } catch (RuntimeException ex) {
      String message = ex.getMessage() == null ? "同步任务异常结束" : ex.getMessage();
      log.error("Qmai batch {} stopped unexpectedly: {}", batchId, message, ex);
      repository.markFinished(batchId, "FAILED", message);
    }
  }

  private TaskResult fetch(long tenantId, long batchId, QmaiModels.ShopMapping mapping, LocalDate date) {
    try {
      QmaiModels.DailySnapshot snapshot = client.fetchDay(mapping, date);
      return new TaskResult(repository.replaceDay(tenantId, batchId, snapshot), null);
    } catch (RuntimeException ex) {
      String message = ex.getMessage() == null ? "企迈请求失败" : ex.getMessage();
      log.warn("Qmai sync failed for tenant={}, store={}, date={}: {}",
          tenantId, mapping.storeId(), date, message);
      return new TaskResult(0, mapping.qmaiShopName() + " " + date + "：" + message);
    }
  }

  private record TaskResult(int productRows, String error) {
  }
}
