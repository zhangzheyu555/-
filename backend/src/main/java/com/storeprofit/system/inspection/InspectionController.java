package com.storeprofit.system.inspection;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/inspections")
public class InspectionController {
  private final AccessControlService accessControl;
  private final InspectionService inspectionService;

  public InspectionController(AccessControlService accessControl, InspectionService inspectionService) {
    this.accessControl = accessControl;
    this.inspectionService = inspectionService;
  }

  @GetMapping
  public ApiResponse<List<InspectionRecordResponse>> records(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String dateFrom,
      @RequestParam(required = false) String dateTo,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) Boolean passed
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionRead(user);
    return ApiResponse.ok(inspectionService.records(user, dateFrom, dateTo, brandId, storeId, passed));
  }

  @GetMapping("/service-health")
  public ApiResponse<InspectionServiceHealthResponse> serviceHealth(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionRead(user);
    return ApiResponse.ok(inspectionService.serviceHealth());
  }

  @GetMapping("/{id}")
  public ApiResponse<InspectionRecordResponse> record(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionRead(user);
    return ApiResponse.ok(inspectionService.record(user, id));
  }

  @PostMapping
  public ApiResponse<InspectionRecordResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody InspectionRecordRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.save(user, null, request));
  }

  @PutMapping("/{id}")
  public ApiResponse<InspectionRecordResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody InspectionRecordRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.save(user, id, request));
  }

  @PostMapping("/{id}/detection-results")
  public ApiResponse<InspectionRecordResponse> bindDetectionResults(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody InspectionDetectionBindingRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.bindDetectionResults(user, id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    inspectionService.delete(user, id);
    return ApiResponse.ok();
  }

  @PostMapping("/detect")
  public ApiResponse<Map<String, Object>> detect(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam("file") MultipartFile file
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.detect(file));
  }

  @PostMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> payload
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    InspectionService.ExportFile file = inspectionService.export(payload);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header("Content-Disposition", "attachment; filename*=UTF-8''" + file.filename())
        .header("X-Export-Filename", file.filename())
        .body(file.content());
  }
}
