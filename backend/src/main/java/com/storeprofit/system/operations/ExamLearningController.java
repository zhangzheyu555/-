package com.storeprofit.system.operations;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.operations.ExamLearningModels.AttemptReviewRequest;
import com.storeprofit.system.operations.ExamLearningModels.CourseRequest;
import com.storeprofit.system.operations.ExamLearningModels.CourseResponse;
import com.storeprofit.system.operations.ExamLearningModels.EncodingCheckResponse;
import com.storeprofit.system.operations.ExamLearningModels.ExamResultResponse;
import com.storeprofit.system.operations.ExamLearningModels.MaterialRequest;
import com.storeprofit.system.operations.ExamLearningModels.MaterialResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewDetailResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewTaskResponse;
import com.storeprofit.system.operations.ExamLearningModels.WrongQuestionResponse;
import com.storeprofit.system.platform.auth.AuthService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exam-center")
public class ExamLearningController {
  private final AuthService authService;
  private final ExamLearningService service;

  public ExamLearningController(AuthService authService, ExamLearningService service) {
    this.authService = authService;
    this.service = service;
  }

  @GetMapping("/courses")
  public ApiResponse<List<CourseResponse>> courses(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.courses(authService.requireUser(authorization)));
  }

  @PostMapping("/courses")
  public ApiResponse<CourseResponse> saveCourse(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody CourseRequest request
  ) {
    return ApiResponse.ok(service.saveCourse(authService.requireUser(authorization), request));
  }

  @GetMapping("/materials")
  public ApiResponse<List<MaterialResponse>> materials(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.materials(authService.requireUser(authorization)));
  }

  @PostMapping("/materials")
  public ApiResponse<MaterialResponse> saveMaterial(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody MaterialRequest request
  ) {
    return ApiResponse.ok(service.saveMaterial(authService.requireUser(authorization), request));
  }

  @GetMapping("/question-categories")
  public ApiResponse<List<QuestionCategoryResponse>> questionCategories(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.questionCategories(authService.requireUser(authorization)));
  }

  @PostMapping("/question-categories")
  public ApiResponse<QuestionCategoryResponse> saveQuestionCategory(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody QuestionCategoryRequest request
  ) {
    return ApiResponse.ok(service.saveQuestionCategory(authService.requireUser(authorization), request));
  }

  @DeleteMapping("/question-categories/{categoryId}")
  public ApiResponse<Map<String, Boolean>> deleteQuestionCategory(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long categoryId
  ) {
    service.deleteQuestionCategory(authService.requireUser(authorization), categoryId);
    return ApiResponse.ok(Map.of("deleted", true));
  }

  @GetMapping("/questions")
  public ApiResponse<List<QuestionBankResponse>> questions(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String keyword
  ) {
    return ApiResponse.ok(service.questions(authService.requireUser(authorization), categoryId, keyword));
  }

  @PostMapping("/questions")
  public ApiResponse<QuestionBankResponse> saveQuestion(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody QuestionBankRequest request
  ) {
    return ApiResponse.ok(service.saveQuestion(authService.requireUser(authorization), request));
  }

  @GetMapping("/reviews")
  public ApiResponse<List<ReviewTaskResponse>> reviews(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.reviewTasks(authService.requireUser(authorization)));
  }

  @GetMapping("/reviews/{attemptId}")
  public ApiResponse<ReviewDetailResponse> reviewDetail(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long attemptId
  ) {
    return ApiResponse.ok(service.reviewDetail(authService.requireUser(authorization), attemptId));
  }

  @PostMapping("/reviews/{attemptId}")
  public ApiResponse<ReviewDetailResponse> review(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long attemptId,
      @RequestBody AttemptReviewRequest request
  ) {
    return ApiResponse.ok(service.review(authService.requireUser(authorization), attemptId, request));
  }

  @GetMapping("/results")
  public ApiResponse<List<ExamResultResponse>> results(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.results(authService.requireUser(authorization)));
  }

  @GetMapping("/wrong-questions")
  public ApiResponse<List<WrongQuestionResponse>> wrongQuestions(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.wrongQuestions(authService.requireUser(authorization)));
  }

  @PostMapping("/wrong-questions/{wrongId}/mastered")
  public ApiResponse<Map<String, Boolean>> markWrongQuestion(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long wrongId,
      @RequestBody(required = false) Map<String, Boolean> request
  ) {
    boolean mastered = request == null || !Boolean.FALSE.equals(request.get("mastered"));
    service.markWrongQuestion(authService.requireUser(authorization), wrongId, mastered);
    return ApiResponse.ok(Map.of("mastered", mastered));
  }

  @GetMapping("/encoding-check")
  public ApiResponse<EncodingCheckResponse> encodingCheck(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(service.encodingCheck(authService.requireUser(authorization)));
  }
}
