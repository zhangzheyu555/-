package com.storeprofit.system.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

class HealthControllerTest {
  @Test
  void anonymousHealthOnlyReportsLiveness() {
    Flyway flyway = mock(Flyway.class);
    DataSource dataSource = mock(DataSource.class);
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    HealthController controller = new HealthController(
        flyway, dataSource, authService, accessControl);

    ApiResponse<Map<String, Object>> response = controller.health();

    assertThat(response.data())
        .containsEntry("status", "UP")
        .containsEntry("service", "store-profit-backend")
        .containsKey("time")
        .doesNotContainKeys(
            "environment",
            "databaseMigrationVersion",
            "databaseVersion",
            "databasePort",
            "databaseName",
            "databaseAccountScope");
    verifyNoInteractions(flyway, dataSource, authService, accessControl);
  }

  @Test
  void reportsSanitizedRuntimeDatabaseIdentity() throws Exception {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService infoService = mock(MigrationInfoService.class);
    MigrationInfo migration = mock(MigrationInfo.class);
    DataSource dataSource = mock(DataSource.class);
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthUser user = new AuthUser(
        1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);

    when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer test-token");
    when(authService.requireUser("Bearer test-token")).thenReturn(user);
    when(flyway.info()).thenReturn(infoService);
    when(infoService.current()).thenReturn(migration);
    when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("37"));
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("select version(), @@port, database(), current_user()"))
        .thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getString(1)).thenReturn("8.0.46");
    when(resultSet.getInt(2)).thenReturn(3307);
    when(resultSet.getString(3)).thenReturn("store_profit_mysql8_final");
    when(resultSet.getString(4)).thenReturn("app_user@127.0.0.1");

    HealthController controller = new HealthController(
        flyway, dataSource, authService, accessControl);
    ReflectionTestUtils.setField(controller, "applicationEnvironment", "STAGING");

    ApiResponse<Map<String, Object>> response = controller.diagnostics(request);

    assertThat(response.data())
        .containsEntry("environment", "STAGING")
        .containsEntry("databaseMigrationVersion", "37")
        .containsEntry("databaseVersion", "8.0.46")
        .containsEntry("databasePort", 3307)
        .containsEntry("databaseName", "store_profit_mysql8_final")
        .containsEntry("databaseAccountScope", "LOCAL_SCOPED")
        .doesNotContainValue("app_user@127.0.0.1");
    verify(authService).requireUser("Bearer test-token");
    verify(accessControl).requireSystemDashboardRead(user);
  }
}
