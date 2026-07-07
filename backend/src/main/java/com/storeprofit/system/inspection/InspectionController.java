package com.storeprofit.system.inspection;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {
  private final AuthService authService;
  private final InspectionService inspectionService;

  public InspectionController(AuthService authService, InspectionService inspectionService) {
    this.authService = authService;
    this.inspectionService = inspectionService;
  }

  @PostMapping("/detect")
  public ApiResponse<Map<String, Object>> detect(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam("file") MultipartFile file
  ) {
    // 旧版页面（static/index.html）没有 token 机制，与 /api/storage 保持一致：带 token 则校验
    if (authorization != null && !authorization.isBlank()) {
      authService.requireUser(authorization);
    }
    return ApiResponse.ok(inspectionService.detect(file));
  }

  @PostMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> payload
  ) {
    if (authorization != null && !authorization.isBlank()) {
      authService.requireUser(authorization);
    }
    InspectionService.ExportFile file = inspectionService.export(payload);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header("Content-Disposition", "attachment; filename*=UTF-8''" + file.filename())
        .header("X-Export-Filename", file.filename())
        .body(file.content());
  }
}
