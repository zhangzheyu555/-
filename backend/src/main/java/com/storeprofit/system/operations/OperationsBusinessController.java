package com.storeprofit.system.operations;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationsBusinessController {
  private final AuthService authService;
  private final OperationsBusinessService operationsBusinessService;

  public OperationsBusinessController(AuthService authService, OperationsBusinessService operationsBusinessService) {
    this.authService = authService;
    this.operationsBusinessService = operationsBusinessService;
  }

  @GetMapping("/api/operations/inventory-checks")
  public ApiResponse<List<InventoryCheckResponse>> inventoryChecks(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(operationsBusinessService.inventoryChecks(authService.requireUser(authorization)));
  }

  @GetMapping("/api/operations/inventory-checks/{id}")
  public ApiResponse<InventoryCheckResponse> inventoryCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.inventoryCheck(authService.requireUser(authorization), id));
  }

  @PostMapping("/api/operations/inventory-checks")
  public ApiResponse<InventoryCheckResponse> saveInventoryCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody InventoryCheckRequest request
  ) {
    return ApiResponse.ok(operationsBusinessService.saveInventoryCheck(authService.requireUser(authorization), request));
  }

  @PostMapping("/api/operations/inventory-checks/{id}/submit")
  public ApiResponse<InventoryCheckResponse> submitInventoryCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.submitInventoryCheck(authService.requireUser(authorization), id));
  }

  @PostMapping("/api/operations/inventory-checks/{id}/review")
  public ApiResponse<InventoryCheckResponse> reviewInventoryCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.reviewInventoryCheck(authService.requireUser(authorization), id));
  }

  @PostMapping("/api/operations/inventory-checks/{id}/cancel")
  public ApiResponse<InventoryCheckResponse> cancelInventoryCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.cancelInventoryCheck(authService.requireUser(authorization), id));
  }

  @GetMapping("/api/operations/exam-papers")
  @Deprecated(since = "0.2.0", forRemoval = false)
  public ResponseEntity<ApiResponse<List<ExamPaperResponse>>> examPapers(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return legacyReadOnly(
        ApiResponse.ok(operationsBusinessService.examPapers(authService.requireUser(authorization))),
        "/api/exam-center/overview"
    );
  }

  @GetMapping("/api/operations/exam-papers/{id}")
  @Deprecated(since = "0.2.0", forRemoval = false)
  public ResponseEntity<ApiResponse<ExamPaperResponse>> examPaper(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return legacyReadOnly(
        ApiResponse.ok(operationsBusinessService.examPaper(authService.requireUser(authorization), id)),
        "/api/exam-center/papers/" + id
    );
  }

  @PostMapping("/api/operations/exam-attempts")
  @Deprecated(since = "0.2.0", forRemoval = true)
  public ApiResponse<ExamAttemptResponse> submitExamAttempt(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody ExamAttemptRequest request
  ) {
    authService.requireUser(authorization);
    throw new BusinessException(
        "LEGACY_EXAM_WRITE_DISABLED",
        "旧考试提交入口已停用，请从考试中心的分配任务进入答题",
        HttpStatus.GONE
    );
  }

  @GetMapping("/api/operations/exam-attempts")
  @Deprecated(since = "0.2.0", forRemoval = false)
  public ResponseEntity<ApiResponse<List<ExamAttemptResponse>>> examAttempts(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return legacyReadOnly(
        ApiResponse.ok(operationsBusinessService.examAttempts(authService.requireUser(authorization))),
        "/api/exam-center/results"
    );
  }

  @GetMapping("/api/operations/exam-attempts/{id}")
  @Deprecated(since = "0.2.0", forRemoval = false)
  public ResponseEntity<ApiResponse<ExamAttemptResponse>> examAttempt(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return legacyReadOnly(
        ApiResponse.ok(operationsBusinessService.examAttempt(authService.requireUser(authorization), id)),
        "/api/exam-center/results"
    );
  }

  @GetMapping("/api/operations/training-materials")
  public ApiResponse<List<TrainingMaterialResponse>> trainingMaterials(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(operationsBusinessService.trainingMaterials(authService.requireUser(authorization)));
  }

  @PostMapping("/api/operations/training-materials/{id}/learned")
  public ApiResponse<List<TrainingMaterialResponse>> markTrainingLearned(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.markMaterialLearned(authService.requireUser(authorization), id));
  }

  @GetMapping("/api/operations/training-learning-records")
  public ApiResponse<List<TrainingLearningRecordResponse>> trainingLearningRecords(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(operationsBusinessService.learningRecords(authService.requireUser(authorization)));
  }

  private <T> ResponseEntity<ApiResponse<T>> legacyReadOnly(ApiResponse<T> body, String successor) {
    return ResponseEntity.ok()
        .header("Deprecation", "true")
        .header(HttpHeaders.LINK, "<" + successor + ">; rel=\"successor-version\"")
        .body(body);
  }
}
