package com.storeprofit.system.health;

import com.storeprofit.system.common.ApiResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
  private final BuildProperties buildProperties;
  private final Flyway flyway;
  private final DataSource dataSource;

  @Value("${app.inspection.detect-url:http://127.0.0.1:8000/detect}")
  private String inspectionDetectUrl;

  @Value("${app.environment}")
  private String applicationEnvironment;

  public HealthController(
      @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
      org.springframework.beans.factory.ObjectProvider<BuildProperties> buildPropertiesProvider,
      Flyway flyway,
      DataSource dataSource
  ) {
    this.buildProperties = buildPropertiesProvider.getIfAvailable();
    this.flyway = flyway;
    this.dataSource = dataSource;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "UP");
    result.put("time", OffsetDateTime.now().toString());
    result.put("environment", applicationEnvironment);

    // Build info
    if (buildProperties != null) {
      result.put("sourceVersion", buildProperties.get("sourceVersion"));
      result.put("buildTime", buildProperties.get("time"));
    } else {
      result.put("sourceVersion", "dev");
      result.put("buildTime", "unknown");
    }

    // Flyway migration version
    try {
      MigrationInfo current = flyway.info().current();
      result.put("databaseMigrationVersion",
          current != null ? current.getVersion().getVersion() : "none");
    } catch (Exception ignored) {
      result.put("databaseMigrationVersion", "error");
    }

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet identity = statement.executeQuery(
             "select version(), @@port, database(), current_user()")) {
      if (!identity.next()) {
        throw new SQLException("Database identity query returned no row");
      }
      String currentUser = identity.getString(4);
      String normalizedUser = currentUser == null ? "" : currentUser.toLowerCase(Locale.ROOT);
      boolean localScopedAccount = !normalizedUser.startsWith("root@")
          && (normalizedUser.endsWith("@127.0.0.1") || normalizedUser.endsWith("@localhost"));
      result.put("databaseVersion", identity.getString(1));
      result.put("databasePort", identity.getInt(2));
      result.put("databaseName", identity.getString(3));
      result.put("databaseAccountScope", localScopedAccount ? "LOCAL_SCOPED" : "UNRESTRICTED_OR_TEST");
    } catch (SQLException exception) {
      throw new IllegalStateException("Database health identity check failed", exception);
    }

    // Inspection service status (non-blocking check)
    result.put("inspectionServiceStatus", "CONFIGURED");
    result.put("inspectionModelStatus", "UNKNOWN");
    result.put("inspectionDetectUrl", inspectionDetectUrl);

    return ApiResponse.ok(result);
  }
}
