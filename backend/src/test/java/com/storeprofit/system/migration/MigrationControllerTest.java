package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationControllerTest {
  @Test
  void statusUsesAuthenticatedUserAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    MigrationStatusResponse response = new MigrationStatusResponse(
        false,
        6,
        0,
        List.of(new LegacyKvKeyStatusResponse("stores", "store_branch", false, 0, "NOT_PRESENT"))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(migrationStatusService.status(user)).thenReturn(response);

    ApiResponse<MigrationStatusResponse> result = controller.status("Bearer token");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(migrationStatusService).status(user);
  }

  @Test
  void legacyKvPreviewUsesAuthenticatedUserAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    LegacyKvMigrationPreviewResponse response = new LegacyKvMigrationPreviewResponse(
        false,
        6,
        1,
        12,
        List.of(new LegacyKvMigrationPreviewItemResponse(
            "salary",
            "salary_record",
            true,
            12,
            "MAP_TO_STRUCTURED_TABLE",
            false
        ))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(migrationStatusService.legacyKvPreview(user)).thenReturn(response);

    ApiResponse<LegacyKvMigrationPreviewResponse> result = controller.legacyKvPreview("Bearer token");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(migrationStatusService).legacyKvPreview(user);
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
  void browserStorageRunUsesAuthenticatedUserAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    BrowserStoragePreviewRequest request = new BrowserStoragePreviewRequest(Map.of("stores", "[]"));
    BrowserStorageMigrationRunResponse response = new BrowserStorageMigrationRunResponse(
        true,
        1,
        1,
        0,
        0,
        List.of(new BrowserStorageMigrationRunItemResponse(
            "stores",
            "BUSINESS_DATA",
            "store_branch",
            "WRITTEN_TO_MYSQL",
            true
        ))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(migrationStatusService.browserStorageRun(user, request)).thenReturn(response);

    ApiResponse<BrowserStorageMigrationRunResponse> result = controller.browserStorageRun("Bearer token", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(migrationStatusService).browserStorageRun(user, request);
  }

  @Test
  void legacyKvRunUsesAuthenticatedUserAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    MigrationStatusService migrationStatusService = mock(MigrationStatusService.class);
    MigrationController controller = new MigrationController(authService, migrationStatusService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    LegacyKvMigrationRunRequest request = new LegacyKvMigrationRunRequest(List.of("stores"));
    LegacyKvMigrationRunResponse response = new LegacyKvMigrationRunResponse(
        true,
        1,
        1,
        0,
        0,
        List.of(new LegacyKvMigrationRunItemResponse(
            "stores",
            "store_branch",
            "MIGRATED",
            2,
            "migrated 2 stores"
        ))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(migrationStatusService.legacyKvRun(user, request)).thenReturn(response);

    ApiResponse<LegacyKvMigrationRunResponse> result = controller.legacyKvRun("Bearer token", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(migrationStatusService).legacyKvRun(user, request);
  }
}
