package com.storeprofit.system.dailyloss;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/daily-loss")
public class DailyLossController {
  private final AccessControlService accessControl;
  private final DailyLossService dailyLossService;

  public DailyLossController(AccessControlService accessControl, DailyLossService dailyLossService) {
    this.accessControl = accessControl;
    this.dailyLossService = dailyLossService;
  }

  @GetMapping("/items")
  public ApiResponse<List<DailyLossItemResponse>> items(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(dailyLossService.activeItems(user(authorization)));
  }

  @GetMapping("/records")
  public ApiResponse<List<DailyLossResponse>> records(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(dailyLossService.list(user(authorization), storeId, date, status));
  }

  @GetMapping("/reports")
  public ApiResponse<List<DailyLossReportResponse>> reports(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String month
  ) {
    return ApiResponse.ok(dailyLossService.reports(user(authorization), storeId, month));
  }

  @GetMapping("/reports/today")
  public ApiResponse<DailyLossReportResponse> today(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(dailyLossService.today(user(authorization)));
  }

  @GetMapping("/records/{id}")
  public ApiResponse<DailyLossResponse> record(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(dailyLossService.get(user(authorization), id));
  }

  @PostMapping("/records")
  public ApiResponse<DailyLossResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody DailyLossCreateRequest request
  ) {
    return ApiResponse.ok(dailyLossService.create(user(authorization), request));
  }

  @PostMapping(value = "/reports", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<DailyLossReportResponse> saveReport(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody DailyLossReportSaveRequest request
  ) {
    return ApiResponse.ok(dailyLossService.saveReport(user(authorization), request));
  }

  @PostMapping(value = "/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<DailyLossReportResponse> saveReportWithAttachments(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestPart("payload") DailyLossReportSaveRequest request,
      @RequestParam(value = "files", required = false) List<MultipartFile> files
  ) {
    return ApiResponse.ok(dailyLossService.saveReport(user(authorization), request, files));
  }

  @PostMapping("/records/{id}/submit")
  public ApiResponse<DailyLossResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(dailyLossService.submit(user(authorization), id));
  }

  @PostMapping("/reports/{id}/submit")
  public ApiResponse<DailyLossReportResponse> submitReport(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(dailyLossService.submitReport(user(authorization), id));
  }

  @PostMapping("/records/{id}/approve")
  public ApiResponse<DailyLossResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) DailyLossReviewRequest request
  ) {
    return ApiResponse.ok(dailyLossService.approve(user(authorization), id, request));
  }

  @PostMapping("/reports/{id}/review")
  public ApiResponse<DailyLossReportResponse> reviewReport(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) DailyLossReviewRequest request
  ) {
    return ApiResponse.ok(dailyLossService.reviewReport(user(authorization), id, request));
  }

  @PostMapping(value = "/records/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<DailyLossResponse> uploadAttachments(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestParam("files") List<MultipartFile> files
  ) {
    return ApiResponse.ok(dailyLossService.uploadAttachments(user(authorization), id, files));
  }

  @PostMapping(value = "/reports/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<DailyLossReportResponse> uploadReportAttachments(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestParam("files") List<MultipartFile> files
  ) {
    return ApiResponse.ok(dailyLossService.uploadReportAttachments(user(authorization), id, files));
  }

  @GetMapping("/exports/monthly.xlsx")
  public ResponseEntity<byte[]> exportMonthlyExcel(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String month,
      @RequestParam(required = false) String storeId
  ) {
    DailyLossMonthlyExcelExport export = dailyLossService.exportMonthlyExcel(user(authorization), storeId, month);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(export.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
        .body(export.content());
  }

  private AuthUser user(String authorization) {
    return accessControl.requireUser(authorization);
  }
}
