package com.storeprofit.system.inspection;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
  private final InspectionExportService inspectionExportService;

  @Autowired
  public InspectionController(
      AccessControlService accessControl,
      InspectionService inspectionService,
      InspectionExportService inspectionExportService
  ) {
    this.accessControl = accessControl;
    this.inspectionService = inspectionService;
    this.inspectionExportService = inspectionExportService;
  }

  /** Compatibility constructor retained for focused controller tests. */
  public InspectionController(AccessControlService accessControl, InspectionService inspectionService) {
    this(accessControl, inspectionService, null);
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

  @GetMapping(value = "/{id}/export.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  public ResponseEntity<byte[]> exportRecord(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionRead(user);
    if (inspectionExportService == null) {
      throw new IllegalStateException("Inspection export service is unavailable");
    }
    InspectionExportFile file = inspectionExportService.export(user, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8)
            .build().toString())
        .header("X-Export-Filename", file.filename())
        .body(file.content());
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

  @PostMapping("/history-repairs")
  public ApiResponse<InspectionHistoryRepairResponse> repairHistory(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.repairHistory(user));
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

  @PostMapping("/{id}/detections/{detectionKey}/confirm")
  public ApiResponse<InspectionDetectionDecisionResponse> confirmDetection(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable String detectionKey,
      @RequestBody(required = false) InspectionDetectionDecisionRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.confirmDetection(user, id, detectionKey, request));
  }

  @PostMapping("/{id}/detections/{detectionKey}/revoke")
  public ApiResponse<InspectionDetectionDecisionResponse> revokeDetection(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable String detectionKey,
      @RequestBody(required = false) InspectionDetectionDecisionRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.revokeDetection(user, id, detectionKey, request));
  }

  @PostMapping("/{id}/detections/{detectionKey}/manual-adjust")
  public ApiResponse<InspectionDetectionDecisionResponse> adjustDetection(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @PathVariable String detectionKey,
      @Valid @RequestBody InspectionDetectionAdjustmentRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.adjustDetection(user, id, detectionKey, request));
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
    return ApiResponse.ok(inspectionService.detect(user, file));
  }

  /** Read-only clause matching for draft inspections; this endpoint never persists a score. */
  @PostMapping("/detection-suggestions")
  public ApiResponse<List<Map<String, Object>>> detectionSuggestions(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody InspectionDetectionBindingRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.detectionSuggestions(user, request));
  }

  /** Confirms a draft suggestion without creating or updating any business record. */
  @PostMapping("/detection-suggestions/{detectionKey}/confirm")
  public ApiResponse<Map<String, Object>> confirmDraftDetection(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String detectionKey,
      @RequestBody InspectionDraftDetectionConfirmRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    accessControl.requireInspectionManage(user);
    return ApiResponse.ok(inspectionService.confirmDraftDetection(user, detectionKey, request));
  }

}
