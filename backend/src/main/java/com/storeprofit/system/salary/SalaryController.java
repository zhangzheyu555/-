package com.storeprofit.system.salary;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
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

@RestController
@RequestMapping("/api/salaries")
public class SalaryController {
  private final AuthService authService;
  private final SalaryService salaryService;

  public SalaryController(AuthService authService, SalaryService salaryService) {
    this.authService = authService;
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
    return ApiResponse.ok(salaryService.records(authService.requireUser(authorization), month, brandId, storeId, allMonths));
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
    return ApiResponse.ok(salaryService.recordsPaged(authService.requireUser(authorization), month, brandId, storeId, page, size));
  }

  @GetMapping("/summary")
  public ApiResponse<SalarySummaryResponse> summary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(salaryService.summary(authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/preview")
  public ApiResponse<SalaryGenerateReport> previewGeneration(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(salaryService.previewGeneration(authService.requireUser(authorization), storeId, month));
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> exportCsv(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    String csv = salaryService.exportCsv(authService.requireUser(authorization), month, brandId, storeId);
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
    return ApiResponse.ok(salaryService.getRecord(authService.requireUser(authorization), id));
  }

  @PostMapping
  public ApiResponse<SalaryRecordResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryRecordRequest request
  ) {
    return ApiResponse.ok(salaryService.save(authService.requireUser(authorization), null, request));
  }

  @PostMapping("/generate")
  public ApiResponse<List<SalaryRecordResponse>> generate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryGenerateRequest request
  ) {
    return ApiResponse.ok(salaryService.generate(authService.requireUser(authorization), request));
  }

  @PostMapping("/generate-report")
  public ApiResponse<SalaryGenerateReport> generateWithReport(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody SalaryGenerateRequest request
  ) {
    return ApiResponse.ok(salaryService.generateWithReport(authService.requireUser(authorization), request));
  }

  @PutMapping("/{id}")
  public ApiResponse<SalaryRecordResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody SalaryRecordRequest request
  ) {
    return ApiResponse.ok(salaryService.save(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/submit")
  public ApiResponse<SalaryRecordResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryService.submit(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/approve")
  public ApiResponse<SalaryRecordResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryService.approve(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/reject")
  public ApiResponse<SalaryRecordResponse> reject(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) SalaryReviewRequest request
  ) {
    return ApiResponse.ok(salaryService.reject(
        authService.requireUser(authorization), id, request == null ? null : request.note()));
  }

  @PostMapping("/{id}/mark-paid")
  public ApiResponse<SalaryRecordResponse> markPaid(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryService.markPaid(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/lock")
  public ApiResponse<SalaryRecordResponse> lockRecord(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(salaryService.lockRecord(authService.requireUser(authorization), id));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    salaryService.delete(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }
}
