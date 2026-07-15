package com.storeprofit.system.dailyloss;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
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

  @PostMapping("/records/{id}/submit")
  public ApiResponse<DailyLossResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(dailyLossService.submit(user(authorization), id));
  }

  @PostMapping("/records/{id}/approve")
  public ApiResponse<DailyLossResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) DailyLossReviewRequest request
  ) {
    return ApiResponse.ok(dailyLossService.approve(user(authorization), id, request));
  }

  @PostMapping(value = "/records/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<DailyLossResponse> uploadAttachments(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestParam("files") List<MultipartFile> files
  ) {
    return ApiResponse.ok(dailyLossService.uploadAttachments(user(authorization), id, files));
  }

  private AuthUser user(String authorization) {
    return accessControl.requireUser(authorization);
  }
}
