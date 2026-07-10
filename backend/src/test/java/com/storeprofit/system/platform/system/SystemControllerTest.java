package com.storeprofit.system.platform.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
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
}
