package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MigrationControllerTest {
  @Test
  void legacyKvStatusIsGoneAndCannotReachMigrationService() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

    when(authService.requireUser("Bearer token")).thenReturn(user);

    assertLegacyKvApiDisabled(() -> controller.status("Bearer token"));

    verify(authService).requireUser("Bearer token");
    verifyNoInteractions(migrationStatusService);
  }

  @Test
  void legacyKvPreviewIsGoneAndCannotReachMigrationService() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

    when(authService.requireUser("Bearer token")).thenReturn(user);

    assertLegacyKvApiDisabled(() -> controller.legacyKvPreview("Bearer token"));

    verify(authService).requireUser("Bearer token");
    verifyNoInteractions(migrationStatusService);
  }

  @Test
  void browserStoragePreviewUsesAuthenticatedUserAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    BrowserStoragePreviewRequest request = new BrowserStoragePreviewRequest(Map.of("stores", "[]"));
    BrowserStoragePreviewResponse response = new BrowserStoragePreviewResponse(
        true,
        1,
        1,
        0,
        0,
        2,
        List.of(new BrowserStoragePreviewItemResponse(
            "stores",
            "BUSINESS_DATA",
            "store_branch",
            2,
            "UPLOAD_TO_MYSQL",
            true
        ))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(migrationStatusService.browserStoragePreview(user, request)).thenReturn(response);

    ApiResponse<BrowserStoragePreviewResponse> result = controller.browserStoragePreview("Bearer token", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(migrationStatusService).browserStoragePreview(user, request);
  }

  @Test
  void browserStorageRunIsGoneAndCannotReachLegacyKvWrite() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    BrowserStoragePreviewRequest request = new BrowserStoragePreviewRequest(Map.of("stores", "[]"));

    when(authService.requireUser("Bearer token")).thenReturn(user);

    assertLegacyKvApiDisabled(() -> controller.browserStorageRun("Bearer token", request));

    verify(authService).requireUser("Bearer token");
    verifyNoInteractions(migrationStatusService);
  }

  @Test
  void legacyKvRunIsGoneAndCannotReachMigrationService() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    LegacyKvMigrationRunRequest request = new LegacyKvMigrationRunRequest(List.of("stores"));

    when(authService.requireUser("Bearer token")).thenReturn(user);

    assertLegacyKvApiDisabled(() -> controller.legacyKvRun("Bearer token", request));

    verify(authService).requireUser("Bearer token");
    verifyNoInteractions(migrationStatusService);
  }

  private void assertLegacyKvApiDisabled(Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getCode()).isEqualTo("LEGACY_KV_API_DISABLED");
          assertThat(error.getStatus()).isEqualTo(HttpStatus.GONE);
        });
  }
}
