package com.storeprofit.system.migration;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.http.HttpStatus;
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
  @Deprecated(since = "0.2.0", forRemoval = true)
  public ApiResponse<MigrationStatusResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  @GetMapping("/legacy-kv/preview")
  @Deprecated(since = "0.2.0", forRemoval = true)
  public ApiResponse<LegacyKvMigrationPreviewResponse> legacyKvPreview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  @PostMapping("/browser-storage/preview")
  public ApiResponse<BrowserStoragePreviewResponse> browserStoragePreview(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BrowserStoragePreviewRequest request
  ) {
    return ApiResponse.ok(migrationStatusService.browserStoragePreview(
        authService.requireUser(authorization), request));
  }

  @PostMapping("/browser-storage/run")
  @Deprecated(since = "0.2.0", forRemoval = true)
  public ApiResponse<BrowserStorageMigrationRunResponse> browserStorageRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody BrowserStoragePreviewRequest request
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  @PostMapping("/legacy-kv/run")
  @Deprecated(since = "0.2.0", forRemoval = true)
  public ApiResponse<LegacyKvMigrationRunResponse> legacyKvRun(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody LegacyKvMigrationRunRequest request
  ) {
    authService.requireUser(authorization);
    throw legacyKvApiDisabled();
  }

  private BusinessException legacyKvApiDisabled() {
    return new BusinessException(
        "LEGACY_KV_API_DISABLED",
        "历史兼容数据接口已停用",
        HttpStatus.GONE
    );
  }
}
