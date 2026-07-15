package com.storeprofit.system.importing;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/profit-imports")
public class ProfitImportPreviewJobController {
  private final AuthService authService;
  private final ProfitImportPreviewJobService jobService;

  public ProfitImportPreviewJobController(AuthService authService, ProfitImportPreviewJobService jobService) {
    this.authService = authService;
    this.jobService = jobService;
  }

  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ProfitImportPreviewJobResponse> preview(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "AUTO") ProfitImportSourceType sourceType,
      @RequestParam String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(jobService.submit(
        authService.requireUser(authorization), file, sourceType, storeId, month));
  }

  @GetMapping("/{jobId}")
  public ApiResponse<ProfitImportPreviewJobResponse> status(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String jobId
  ) {
    return ApiResponse.ok(jobService.get(authService.requireUser(authorization), jobId));
  }

  @PostMapping("/{jobId}/confirm")
  public ApiResponse<ProfitImportCommitResponse> confirm(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String jobId,
      @RequestBody ProfitImportJobConfirmRequest request
  ) {
    return ApiResponse.ok(jobService.confirm(authService.requireUser(authorization), jobId, request));
  }

  @DeleteMapping("/{jobId}")
  public ApiResponse<Void> cancel(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String jobId
  ) {
    jobService.cancel(authService.requireUser(authorization), jobId);
    return ApiResponse.ok(null);
  }
}
