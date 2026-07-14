package com.storeprofit.system.importing;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProfitImportAsyncConfig {
  @Bean(name = "profitImportExecutor")
  public Executor profitImportExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("profit-import-");
    executor.initialize();
    return executor;
  }
}
