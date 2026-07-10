package com.storeprofit.system.importing;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports/profit")
public class ProfitImportController {
  private final AuthService authService;
  private final ProfitImportService profitImportService;

  public ProfitImportController(AuthService authService, ProfitImportService profitImportService) {
    this.authService = authService;
    this.profitImportService = profitImportService;
  }

  @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ProfitImportRecognizeResponse> recognize(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "AUTO") ProfitImportSourceType sourceType,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String month
  ) {
    return ApiResponse.ok(profitImportService.recognize(
        authService.requireUser(authorization),
        file,
        sourceType,
        storeId,
        month
    ));
  }

  @PostMapping("/{importId}/commit")
  public ApiResponse<ProfitImportCommitResponse> commit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String importId,
      @RequestBody ProfitImportCommitRequest request
  ) {
    return ApiResponse.ok(profitImportService.commit(authService.requireUser(authorization), request));
  }

  // Vue 前端（frontend-vue/src/api/imports.ts）不带 importId 直接 POST /commit，行内已含全部提交信息。
  @PostMapping("/commit")
  public ApiResponse<ProfitImportCommitResponse> commitRows(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody ProfitImportCommitRequest request
  ) {
    return ApiResponse.ok(profitImportService.commit(authService.requireUser(authorization), request));
  }
}
