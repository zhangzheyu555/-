package com.storeprofit.system.migration;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration")
public class MigrationController {
  private final AuthService authService;
  private final MigrationStatusService migrationStatusService;

  public MigrationController(AuthService authService, MigrationStatusService migrationStatusService) {
    this.authService = authService;
    this.migrationStatusService = migrationStatusService;
  }

  @GetMapping("/status")
  public ApiResponse<MigrationStatusResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(migrationStatusService.status(user));
  }

  @GetMapping("/legacy-kv/preview")
  public ApiResponse<LegacyKvMigrationPreviewResponse> legacyKvPreview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(migrationStatusService.legacyKvPreview(user));
  }

  @PostMapping("/browser-storage/preview")
  public ApiResponse<BrowserStoragePreviewResponse> browserStoragePreview(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BrowserStoragePreviewRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(migrationStatusService.browserStoragePreview(user, request));
  }

  @PostMapping("/browser-storage/run")
  public ApiResponse<BrowserStorageMigrationRunResponse> browserStorageRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BrowserStoragePreviewRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(migrationStatusService.browserStorageRun(user, request));
  }

  @PostMapping("/legacy-kv/run")
  public ApiResponse<LegacyKvMigrationRunResponse> legacyKvRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody LegacyKvMigrationRunRequest request
  ) {
    AuthUser user = authService.requireUser(authorization);
    return ApiResponse.ok(migrationStatusService.legacyKvRun(user, request));
  }
}
