package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class QmaiMigrationContractTest {
  @Test
  void mysqlAndH2MigrationsCreateTheSameBusinessTablesWithoutSecrets() throws IOException {
    String mysql = migration("db/migration/V60__qmai_operating_data.sql");
    String h2 = migration("db/migration-h2/V60__qmai_operating_data.sql");

    for (String table : new String[] {
        "qmai_platform_config", "qmai_store_mapping", "qmai_sync_batch",
        "qmai_daily_sales", "qmai_product_sales"
    }) {
      assertThat(mysql).contains("create table " + table);
      assertThat(h2).contains("create table " + table);
    }

    assertThat(mysql).doesNotContain("open_key", "grant_code", "console_token", "cookie", "password");
    assertThat(h2).doesNotContain("open_key", "grant_code", "console_token", "cookie", "password");
  }

  private String migration(String resource) throws IOException {
    Path path = Path.of("src/main/resources").resolve(resource);
    return Files.readString(path).toLowerCase(Locale.ROOT);
  }
}
