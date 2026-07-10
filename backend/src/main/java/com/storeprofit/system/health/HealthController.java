package com.storeprofit.system.health;

import com.storeprofit.system.common.ApiResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
    } catch (Exception e) {
      result.put("databaseMigrationVersion", "error: " + e.getMessage());
    }

    // Inspection service status (non-blocking check)
    result.put("inspectionServiceStatus", "CONFIGURED");
    result.put("inspectionModelStatus", "UNKNOWN");
    result.put("inspectionDetectUrl", inspectionDetectUrl);

    return ApiResponse.ok(result);
  }
}
