package com.storeprofit.system.qmai;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor so historical imports cannot consume request threads or grow without limit. */
@Configuration
public class QmaiSyncAsyncConfig {
  @Bean(name = "qmaiSyncExecutor")
  public Executor qmaiSyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    // Do not reserve a database lease for a task waiting behind a long month import.
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("qmai-sales-sync-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "qmaiScheduledSyncExecutor")
  public Executor qmaiScheduledSyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("qmai-sales-scheduled-");
    executor.initialize();
    return executor;
  }
}
