package com.storeprofit.system.operations;

import java.math.BigDecimal;
import java.util.List;

public final class ExamLearningModels {
  private ExamLearningModels() {
  }

  public record CourseRequest(
      Long id,
      String courseCode,
      String title,
      String category,
      String description,
      String coverUrl,
      Integer durationMinutes,
      String requiredRoleScope,
      Boolean enabled,
      Integer sortOrder,
      List<Long> materialIds
  ) {
  }

  public record CourseResponse(
      Long id,
      String courseCode,
      String title,
      String category,
      String description,
      String coverUrl,
      Integer durationMinutes,
      String requiredRoleScope,
      Boolean enabled,
      Integer sortOrder,
      Integer materialCount,
      List<Long> materialIds
  ) {
  }

  public record MaterialRequest(
      Long id,
      String materialCode,
      String title,
      String category,
      List<String> imageUrls,
      String content,
      Boolean enabled,
      Integer sortOrder
  ) {
  }

  public record MaterialResponse(
      Long id,
      String materialCode,
      String title,
      String category,
      List<String> imageUrls,
      String content,
      Boolean enabled,
      Integer sortOrder,
      Integer learnedCount
  ) {
  }

  public record QuestionCategoryRequest(
      Long id,
      String categoryCode,
      String categoryName,
      String description,
      Boolean enabled,
      Integer sortOrder
  ) {
  }

  public record QuestionCategoryResponse(
      Long id,
      String categoryCode,
      String categoryName,
      String description,
      Boolean enabled,
      Integer sortOrder,
      Integer questionCount
  ) {
  }

  public record QuestionBankRequest(
      Long id,
      String questionCode,
      Long categoryId,
      String questionType,
      String questionText,
      List<String> options,
      String standardAnswer,
      String answerAnalysis,
      String acceptKeywords,
      String difficulty,
      BigDecimal defaultScore,
      Boolean enabled
  ) {
  }

  public record QuestionBankResponse(
      Long id,
      String questionCode,
      Long categoryId,
      String categoryName,
      String questionType,
      String questionText,
      List<String> options,
      String standardAnswer,
      String answerAnalysis,
      String acceptKeywords,
      String difficulty,
      BigDecimal defaultScore,
      Boolean enabled,
      Integer usedCount
  ) {
  }

  public record ReviewTaskResponse(
      Long attemptId,
      Long assignmentId,
      String examTitle,
      String paperName,
      String examineeName,
      String storeId,
      String storeName,
      BigDecimal autoScore,
      String submittedAt,
      String reviewStatus
  ) {
  }

  public record ReviewAnswerResponse(
      Long answerId,
      Long questionId,
      String questionType,
      String questionText,
      String standardAnswer,
      String userAnswer,
      BigDecimal maxScore,
      BigDecimal awardedScore,
      Boolean correct,
      String reviewComment
  ) {
  }

  public record ReviewDetailResponse(
      ReviewTaskResponse task,
      List<ReviewAnswerResponse> answers,
      String reviewNote
  ) {
  }

  public record ReviewAnswerRequest(
      Long answerId,
      BigDecimal awardedScore,
      String comment
  ) {
  }

  public record AttemptReviewRequest(
      String reviewNote,
      List<ReviewAnswerRequest> answers
  ) {
  }

  public record ExamResultResponse(
      Long attemptId,
      Long assignmentId,
      Long campaignId,
      String examTitle,
      String paperName,
      Long userId,
      String examineeName,
      String role,
      String storeId,
      String storeName,
      BigDecimal score,
      Boolean passed,
      Boolean violated,
      String reviewStatus,
      String submittedAt,
      String reviewedAt
  ) {
  }

  public record WrongQuestionResponse(
      Long id,
      Long attemptId,
      Long questionId,
      String paperName,
      String questionType,
      String questionText,
      String standardAnswer,
      String userAnswer,
      String answerAnalysis,
      Boolean mastered,
      String createdAt
  ) {
  }

  public record EncodingCheckResponse(
      String databaseCharset,
      String connectionCharset,
      Integer suspiciousPaperCount,
      Integer suspiciousQuestionCount,
      Integer suspiciousMaterialCount,
      List<String> suspiciousHexSamples
  ) {
  }
}
