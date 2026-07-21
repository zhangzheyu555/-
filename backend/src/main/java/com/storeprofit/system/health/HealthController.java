package com.storeprofit.system.health;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
  private final Flyway flyway;
  private final DataSource dataSource;
  private final AuthService authService;
  private final AccessControlService accessControl;

  @Value("${app.environment}")
  private String applicationEnvironment;

  public HealthController(
      Flyway flyway,
      DataSource dataSource,
      AuthService authService,
      AccessControlService accessControl
  ) {
    this.flyway = flyway;
    this.dataSource = dataSource;
    this.authService = authService;
    this.accessControl = accessControl;
  }

  /**
   * Anonymous liveness probe. It intentionally does not touch dependent services or expose
   * deployment metadata, so a public reverse-proxy health check cannot reveal the runtime shape.
   */
  @GetMapping
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "UP");
    result.put("service", "store-profit-backend");
    result.put("time", OffsetDateTime.now().toString());
    return ApiResponse.ok(result);
  }

  /**
   * Authenticated readiness diagnostics for controlled operations only. The authentication filter
   * protects this sub-path before it reaches the controller; the dashboard permission keeps the
   * database identity and deployment state limited to the system administrator.
   */
  @GetMapping("/diagnostics")
  public ApiResponse<Map<String, Object>> diagnostics(HttpServletRequest request) {
    AuthUser user = authService.requireUser(request.getHeader(HttpHeaders.AUTHORIZATION));
    accessControl.requireSystemDashboardRead(user);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "UP");
    result.put("environment", applicationEnvironment);
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
    return ApiResponse.ok(result);
  }
}
