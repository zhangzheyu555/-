package com.storeprofit.system.operations;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
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
  public ApiResponse<List<ExamPaperResponse>> examPapers(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(operationsBusinessService.examPapers(authService.requireUser(authorization)));
  }

  @GetMapping("/api/operations/exam-papers/{id}")
  public ApiResponse<ExamPaperResponse> examPaper(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.examPaper(authService.requireUser(authorization), id));
  }

  @PostMapping("/api/operations/exam-attempts")
  public ApiResponse<ExamAttemptResponse> submitExamAttempt(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody ExamAttemptRequest request
  ) {
    return ApiResponse.ok(operationsBusinessService.submitExamAttempt(authService.requireUser(authorization), request));
  }

  @GetMapping("/api/operations/exam-attempts")
  public ApiResponse<List<ExamAttemptResponse>> examAttempts(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(operationsBusinessService.examAttempts(authService.requireUser(authorization)));
  }

  @GetMapping("/api/operations/exam-attempts/{id}")
  public ApiResponse<ExamAttemptResponse> examAttempt(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(operationsBusinessService.examAttempt(authService.requireUser(authorization), id));
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
}
