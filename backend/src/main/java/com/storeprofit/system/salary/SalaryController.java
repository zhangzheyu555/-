package com.storeprofit.system.salary;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/salaries")
public class SalaryController {
  private final AuthService authService;
  private final SalaryQueryService salaryQueryService;
  private final SalaryGenerationService salaryGenerationService;
  private final SalaryWorkflowService salaryWorkflowService;
  private final SalaryExportService salaryExportService;

  // Backward-compatible: keep old SalaryService reference for any code that still uses it
  @SuppressWarnings("unused")
  private final SalaryService salaryService;

  public SalaryController(
      AuthService authService,
      SalaryQueryService salaryQueryService,
      SalaryGenerationService salaryGenerationService,
      SalaryWorkflowService salaryWorkflowService,
      SalaryExportService salaryExportService,
      SalaryService salaryService
  ) {
    this.authService = authService;
    this.salaryQueryService = salaryQueryService;
    this.salaryGenerationService = salaryGenerationService;
    this.salaryWorkflowService = salaryWorkflowService;
    this.salaryExportService = salaryExportService;
    this.salaryService = salaryService;
  }

  @GetMapping
  public ApiResponse<List<SalaryRecordResponse>> records(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(defaultValue = "false") boolean allMonths
  ) {
    return ApiResponse.ok(salaryQueryService.records(authService.requireUser(authorization), month, brandId, storeId, allMonths));
  }

  @GetMapping("/page")
  public ApiResponse<SalaryPageResponse> recordsPaged(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.ok(salaryQueryService.recordsPaged(authService.requireUser(authorization), month, brandId, storeId, page, size));
  }

  @GetMapping("/employee-page")
  public ApiResponse<SalaryEmployeePageResponse> employeePage(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.ok(salaryQueryService.employeePage(
        authService.requireUser(authorization), month, brandId, storeId, status, keyword, page, size));
  }

  @GetMapping("/available-months")
  public ApiResponse<List<SalaryAvailableMonth>> availableMonths(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(salaryQueryService.availableMonths(authService.requireUser(authorization), storeId));
  }

  @GetMapping("/summary")
  public ApiResponse<SalarySummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(salaryQueryService.summary(authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/business-metrics")
  public ApiResponse<SalaryBusinessMetricsResponse> businessMetrics(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(salaryQueryService.businessMetrics(
        authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/assignment-candidates")
  public ApiResponse<List<SalaryAssignmentCandidate>> assignmentCandidates(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(salaryWorkflowService.assignmentCandidates(
        authService.requireUser(authorization), storeId, month));
  }

  @GetMapping("/preview")
  public ApiResponse<SalaryGenerateReport> previewGeneration(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(salaryGenerationService.previewGeneration(authService.requireUser(authorization), storeId, month));
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> exportCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    String csv = salaryExportService.exportCsv(authService.requireUser(authorization), month, brandId, storeId);
    byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"salary-export.csv\"")
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .body(bytes);
  }

  @GetMapping("/{id}")
  public ApiResponse<SalaryRecordResponse> getRecord(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryQueryService.getRecord(authService.requireUser(authorization), id));
  }

  @PostMapping
  public ApiResponse<SalaryRecordResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryRecordRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.save(authService.requireUser(authorization), null, request));
  }

  @PostMapping("/assign-employee")
  public ApiResponse<SalaryRecordResponse> assignEmployee(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryAssignmentRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.assignEmployee(
        authService.requireUser(authorization), request));
  }

  @PostMapping("/generate")
  public ApiResponse<List<SalaryRecordResponse>> generate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryGenerateRequest request
  ) {
    return ApiResponse.ok(salaryGenerationService.generate(authService.requireUser(authorization), request));
  }

  @PostMapping("/generate-report")
  public ApiResponse<SalaryGenerateReport> generateWithReport(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryGenerateRequest request
  ) {
    return ApiResponse.ok(salaryGenerationService.generateWithReport(authService.requireUser(authorization), request));
  }

  @PutMapping("/attendance")
  public ApiResponse<SalaryRepository.AttendanceRow> saveAttendance(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryAttendanceRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.saveAttendance(authService.requireUser(authorization), request));
  }

  @PutMapping("/{id}")
  public ApiResponse<SalaryRecordResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody SalaryRecordRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.save(authService.requireUser(authorization), id, request));
  }

  @PutMapping("/history-import/{id}")
  public ApiResponse<SalaryRecordResponse> importHistorical(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody SalaryRecordRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.importHistorical(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/submit")
  public ApiResponse<SalaryRecordResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryWorkflowService.submit(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/approve")
  public ApiResponse<SalaryRecordResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryWorkflowService.approve(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/reject")
  public ApiResponse<SalaryRecordResponse> reject(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) SalaryReviewRequest request
  ) {
    return ApiResponse.ok(salaryWorkflowService.reject(
        authService.requireUser(authorization), id, request == null ? null : request.note()));
  }

  @PostMapping("/{id}/mark-paid")
  public ApiResponse<SalaryRecordResponse> markPaid(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryWorkflowService.markPaid(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/lock")
  public ApiResponse<SalaryRecordResponse> lockRecord(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryWorkflowService.lockRecord(authService.requireUser(authorization), id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    salaryWorkflowService.delete(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }
}
