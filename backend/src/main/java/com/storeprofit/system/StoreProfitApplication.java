package com.storeprofit.system;

import com.storeprofit.system.config.DatabaseEnvironmentGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreProfitApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(StoreProfitApplication.class);
    application.addInitializers(context ->
        DatabaseEnvironmentGuard.validate(context.getEnvironment()));
    application.run(args);
  }
}
