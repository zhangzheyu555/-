package com.storeprofit.system.platform.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class SystemControllerTest {
  @Test
  void overviewRequiresAuthenticatedUser() {
    AuthService authService = mock(AuthService.class);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    when(authService.requireUser("Bearer token")).thenReturn(user);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("audit");
    SystemController controller = new SystemController(authService, environment, "test-version");

    ApiResponse<SystemOverview> response = controller.overview("Bearer token");

    assertThat(response.success()).isTrue();
    assertThat(response.data().activeProfile()).isEqualTo("audit");
    verify(authService).requireUser("Bearer token");
  }

  @Test
  void versionReportsBuildDatabaseAndEnvironmentIdentity() {
    AuthService authService = mock(AuthService.class);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    when(authService.requireUser("Bearer token")).thenReturn(user);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn("26");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");
    SystemController controller = new SystemController(authService, environment, "test-version", jdbcTemplate);

    ApiResponse<SystemController.VersionInfo> response = controller.version("Bearer token");

    assertThat(response.success()).isTrue();
    assertThat(response.data().applicationVersion()).isEqualTo("test-version");
    assertThat(response.data().databaseMigrationVersion()).isEqualTo("26");
    assertThat(response.data().environment()).isEqualTo("test");
    assertThat(response.data().sourceVersion()).isNotBlank();
    verify(authService).requireUser("Bearer token");
  }
}
