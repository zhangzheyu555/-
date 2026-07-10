package com.storeprofit.system.operations;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCampaignDetailResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCenterOverviewResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperEditorResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamPublishRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamSubmissionRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-center")
public class ExamCenterController {
  private final AuthService authService;
  private final ExamCenterService examCenterService;

  public ExamCenterController(AuthService authService, ExamCenterService examCenterService) {
    this.authService = authService;
    this.examCenterService = examCenterService;
  }

  @GetMapping("/overview")
  public ApiResponse<ExamCenterOverviewResponse> overview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(examCenterService.overview(authService.requireUser(authorization)));
  }

  @GetMapping("/papers/{paperId}")
  public ApiResponse<ExamPaperEditorResponse> paperForEdit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long paperId
  ) {
    return ApiResponse.ok(examCenterService.paperForEdit(authService.requireUser(authorization), paperId));
  }

  @PostMapping("/papers")
  public ApiResponse<ExamPaperEditorResponse> savePaper(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody ExamPaperSaveRequest request
  ) {
    return ApiResponse.ok(examCenterService.savePaper(authService.requireUser(authorization), request));
  }

  @PostMapping("/campaigns")
  public ApiResponse<ExamCampaignDetailResponse> publish(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody ExamPublishRequest request
  ) {
    return ApiResponse.ok(examCenterService.publish(authService.requireUser(authorization), request));
  }

  @GetMapping("/campaigns/{campaignId}")
  public ApiResponse<ExamCampaignDetailResponse> campaign(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long campaignId
  ) {
    return ApiResponse.ok(examCenterService.campaignDetail(authService.requireUser(authorization), campaignId));
  }

  @GetMapping("/assignments/{assignmentId}/paper")
  public ApiResponse<ExamPaperResponse> assignmentPaper(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long assignmentId
  ) {
    return ApiResponse.ok(examCenterService.assignmentPaper(authService.requireUser(authorization), assignmentId));
  }

  @PostMapping("/assignments/{assignmentId}/submit")
  public ApiResponse<ExamAttemptResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long assignmentId,
      @RequestBody ExamSubmissionRequest request
  ) {
    return ApiResponse.ok(examCenterService.submit(authService.requireUser(authorization), assignmentId, request));
  }

  @GetMapping("/campaigns/{campaignId}/results.csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long campaignId
  ) {
    byte[] content = examCenterService.exportCampaign(authService.requireUser(authorization), campaignId);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
    headers.setContentDisposition(ContentDisposition.attachment()
        .filename("exam-results-" + campaignId + ".csv", StandardCharsets.UTF_8)
        .build());
    return ResponseEntity.ok().headers(headers).body(content);
  }
}
