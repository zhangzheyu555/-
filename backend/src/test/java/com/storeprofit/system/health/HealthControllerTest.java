package com.storeprofit.system.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class HealthControllerTest {
  @Test
  void publicHealthIsAMinimalLivenessProbe() {
    Flyway flyway = mock(Flyway.class);
    DataSource dataSource = mock(DataSource.class);
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);

    HealthController controller = new HealthController(flyway, dataSource, authService, accessControl);

    ApiResponse<Map<String, Object>> response = controller.health();

    assertThat(response.data())
        .containsEntry("status", "UP")
        .containsEntry("service", "store-profit-backend")
        .containsKey("time")
        .hasSize(3);
    verifyNoInteractions(flyway, dataSource, authService, accessControl);
  }

  @Test
  void diagnosticsRequiresSystemDashboardAccessAndKeepsRawAccountPrivate() throws Exception {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService infoService = mock(MigrationInfoService.class);
    MigrationInfo migration = mock(MigrationInfo.class);
    DataSource dataSource = mock(DataSource.class);
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    AuthUser user = new AuthUser(
        1L, 1L, "测试租户", "boss", "hash", "老板", "BOSS", null, true);

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
    when(authService.requireUser("Bearer system-token")).thenReturn(user);

    HealthController controller = new HealthController(flyway, dataSource, authService, accessControl);
    ReflectionTestUtils.setField(controller, "applicationEnvironment", "STAGING");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health/diagnostics");
    request.addHeader("Authorization", "Bearer system-token");

    ApiResponse<Map<String, Object>> response = controller.diagnostics(request);

    assertThat(response.data())
        .containsEntry("databaseVersion", "8.0.46")
        .containsEntry("databasePort", 3307)
        .containsEntry("databaseName", "store_profit_mysql8_final")
        .containsEntry("databaseAccountScope", "LOCAL_SCOPED")
        .doesNotContainValue("app_user@127.0.0.1");
    verify(accessControl).requireSystemDashboardRead(user);
  }

  @Test
  void diagnosticsStopsBeforeDatabaseAccessWhenSystemPermissionIsDenied() {
    Flyway flyway = mock(Flyway.class);
    DataSource dataSource = mock(DataSource.class);
    AuthService authService = mock(AuthService.class);
    AccessControlService accessControl = mock(AccessControlService.class);
    AuthUser user = new AuthUser(
        2L, 1L, "测试租户", "finance", "hash", "财务", "FINANCE", null, true);
    when(authService.requireUser("Bearer finance-token")).thenReturn(user);
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有查看系统信息的权限", HttpStatus.FORBIDDEN))
        .when(accessControl).requireSystemDashboardRead(user);
    HealthController controller = new HealthController(flyway, dataSource, authService, accessControl);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health/diagnostics");
    request.addHeader("Authorization", "Bearer finance-token");

    assertThatThrownBy(() -> controller.diagnostics(request))
        .isInstanceOf(BusinessException.class)
        .hasMessage("当前账号没有查看系统信息的权限");

    verify(accessControl).requireSystemDashboardRead(user);
    verifyNoInteractions(flyway, dataSource);
  }
}
