package com.storeprofit.system.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DatabaseRuntimeGuardConfiguration {
  @Bean
  FlywayMigrationStrategy verifiedDatabaseFlywayMigrationStrategy(
      DatabaseRuntimeIdentityGuard identityGuard) {
    return flyway -> {
      identityGuard.verifyBeforeMigrationOrStartup();
      flyway.migrate();
    };
  }
}
