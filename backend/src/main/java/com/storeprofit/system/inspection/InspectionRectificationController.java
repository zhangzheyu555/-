package com.storeprofit.system.inspection;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import jakarta.validation.Valid;
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
@RequestMapping("/api/inspections")
public class InspectionRectificationController {
  private final AccessControlService accessControl;
  private final InspectionRectificationService rectificationService;

  public InspectionRectificationController(
      AccessControlService accessControl,
      InspectionRectificationService rectificationService
  ) {
    this.accessControl = accessControl;
    this.rectificationService = rectificationService;
  }

  @GetMapping("/rectifications/mine")
  public ApiResponse<List<InspectionRectificationResponse>> mine(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(rectificationService.mine(user));
  }

  @PostMapping(value = "/{recordId}/rectification/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<InspectionRectificationEvidenceResponse> uploadEvidence(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String recordId,
      @RequestParam("file") MultipartFile file
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(rectificationService.uploadEvidence(user, recordId, file));
  }

  @PostMapping("/{recordId}/rectification")
  public ApiResponse<InspectionRectificationResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String recordId,
      @Valid @RequestBody InspectionRectificationSubmitRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(rectificationService.submit(user, recordId, request));
  }

  @GetMapping("/rectifications/reviews")
  public ApiResponse<List<InspectionRectificationResponse>> reviewQueue(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(rectificationService.reviewQueue(user));
  }

  @PostMapping("/{recordId}/rectification/review")
  public ApiResponse<InspectionRectificationResponse> review(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String recordId,
      @Valid @RequestBody InspectionRectificationReviewRequest request
  ) {
    AuthUser user = accessControl.requireUser(authorization);
    return ApiResponse.ok(rectificationService.review(user, recordId, request));
  }
}
