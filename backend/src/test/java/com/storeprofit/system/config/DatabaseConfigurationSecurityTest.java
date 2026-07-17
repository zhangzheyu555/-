package com.storeprofit.system.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class DatabaseConfigurationSecurityTest {
  @Test
  void mysqlConnectionRequiresEnvironmentVariablesAndCannotCreateDatabases() throws IOException {
    try (var stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(stream).isNotNull();
      String yaml = new String(stream.readAllBytes(), UTF_8);

      assertThat(yaml)
          .contains("environment: ${APP_ENV}")
          .contains("jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?")
          .contains("createDatabaseIfNotExist=false")
          .contains("allowPublicKeyRetrieval=true")
          .contains("connectTimeout=2000")
          .contains("socketTimeout=2000")
          .contains("username: ${MYSQL_USERNAME}")
          .contains("password: ${MYSQL_PASSWORD}")
          .contains("baseline-on-migrate: false")
          .contains("out-of-order: false")
          .contains("clean-disabled: true")
          .contains("validate-on-migrate: true")
          .doesNotContain("${MYSQL_HOST:localhost}")
          .doesNotContain("${MYSQL_DATABASE:store_profit}")
          .doesNotContain("${MYSQL_USERNAME:root}")
          .doesNotContain("${MYSQL_PASSWORD:}")
          .doesNotContain("APPROVED_MYSQL_DATABASES")
          .doesNotContain("EXPECTED_MYSQL_VERSION")
          .doesNotContain("createDatabaseIfNotExist=true");
    }
  }
}
