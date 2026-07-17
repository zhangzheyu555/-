package com.storeprofit.system.qmai;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class QmaiAsyncConfig {
  @Bean("qmaiSyncExecutor")
  public Executor qmaiSyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("qmai-sync-");
    executor.initialize();
    return executor;
  }

  @Bean("qmaiFetchExecutor")
  public Executor qmaiFetchExecutor(QmaiProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.getConcurrency());
    executor.setMaxPoolSize(properties.getConcurrency());
    executor.setQueueCapacity(1500);
    executor.setThreadNamePrefix("qmai-fetch-");
    executor.initialize();
    return executor;
  }
}
