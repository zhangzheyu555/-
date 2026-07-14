package com.storeprofit.system.platform.system;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private static final Logger log = LoggerFactory.getLogger(SystemController.class);
  private final AuthService authService;
  private final AccessControlService accessControl;
  private final Environment environment;
  private final JdbcTemplate jdbcTemplate;
  private final String version;
  private final Instant startupTime = Instant.now();
  private final Properties buildProperties = loadBuildProperties();

  @Autowired
  public SystemController(
      AuthService authService,
      AccessControlService accessControl,
      Environment environment,
      @Value("${app.version:0.1.0}") String version,
      JdbcTemplate jdbcTemplate
  ) {
    this.authService = authService;
    this.accessControl = accessControl;
    this.environment = environment;
    this.version = version;
    this.jdbcTemplate = jdbcTemplate;
  }

  SystemController(AuthService authService, Environment environment, String version) {
    this.authService = authService;
    this.accessControl = null;
    this.environment = environment;
    this.version = version;
    this.jdbcTemplate = null;
  }

  SystemController(
      AuthService authService,
      Environment environment,
      String version,
      JdbcTemplate jdbcTemplate
  ) {
    this.authService = authService;
    this.accessControl = null;
    this.environment = environment;
    this.version = version;
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping("/overview")
  public ApiResponse<SystemOverview> overview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    requireDashboardAccess(authorization);
    String[] profiles = environment.getActiveProfiles();
    String activeProfile = profiles.length == 0 ? "default" : String.join(",", profiles);
    return ApiResponse.ok(new SystemOverview(
        "门店利润系统",
        version,
        activeProfile,
        List.of("经营驾驶舱", "数据中心", "门店运营", "系统管理")
    ));
  }

  @GetMapping("/version")
  public ApiResponse<VersionInfo> version(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    requireDashboardAccess(authorization);
    String buildTime = buildProperties.getProperty("build.time", "unknown");
    String sourceVersion = buildProperties.getProperty(
        "build.sourceVersion",
        "source-" + version + "-runtime-" + startupTime
    );
    return ApiResponse.ok(new VersionInfo(
        version,
        version,
        buildTime,
        sourceVersion,
        databaseMigrationVersion(),
        activeEnvironment(),
        startupTime.toString()
    ));
  }

  private AuthUser requireDashboardAccess(String authorization) {
    AuthUser user = authService.requireUser(authorization);
    if (accessControl != null) {
      accessControl.requireSystemDashboardRead(user);
      return user;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException(
          "FORBIDDEN", "当前账号没有查看系统信息的权限", HttpStatus.FORBIDDEN);
    }
    return user;
  }

  private String activeEnvironment() {
    String configured = environment.getProperty("app.environment");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String[] profiles = environment.getActiveProfiles();
    return profiles.length == 0 ? "default" : String.join(",", profiles);
  }

  private String databaseMigrationVersion() {
    if (jdbcTemplate == null) {
      return "unavailable";
    }
    try {
      String migrationVersion = jdbcTemplate.queryForObject("""
          select version
          from flyway_schema_history
          where success = 1
          order by installed_rank desc
          limit 1
          """, String.class);
      return migrationVersion == null || migrationVersion.isBlank() ? "none" : migrationVersion;
    } catch (RuntimeException ex) {
      log.warn("Unable to read database migration version");
      return "unavailable";
    }
  }

  private static Properties loadBuildProperties() {
    Properties properties = new Properties();
    try (InputStream stream = SystemController.class.getClassLoader()
        .getResourceAsStream("META-INF/build-info.properties")) {
      if (stream != null) {
        properties.load(stream);
      }
    } catch (IOException ignored) {
      // The endpoint still returns runtime source information when build metadata is unavailable.
    }
    return properties;
  }

  public record VersionInfo(
      String version,
      String applicationVersion,
      String buildTime,
      String sourceVersion,
      String databaseMigrationVersion,
      String environment,
      String startupTime
  ) {}
}
