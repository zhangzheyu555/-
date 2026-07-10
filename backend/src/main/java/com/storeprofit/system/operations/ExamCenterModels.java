package com.storeprofit.system.operations;

import java.math.BigDecimal;
import java.util.List;

public final class ExamCenterModels {
  private ExamCenterModels() {
  }

  public record ExamPaperSummaryResponse(
      Long id,
      String paperCode,
      String paperName,
      String roleScope,
      BigDecimal passScore,
      Boolean enabled,
      Integer questionCount
  ) {
  }

  public record ExamQuestionSaveRequest(
      String questionType,
      String questionText,
      List<String> options,
      String standardAnswer,
      String acceptKeywords,
      BigDecimal score
  ) {
  }

  public record ExamQuestionEditorResponse(
      Long id,
      String questionType,
      String questionText,
      List<String> options,
      String standardAnswer,
      String acceptKeywords,
      BigDecimal score,
      Integer sortOrder
  ) {
  }

  public record ExamPaperSaveRequest(
      Long id,
      String paperCode,
      String paperName,
      String roleScope,
      BigDecimal passScore,
      Boolean enabled,
      List<ExamQuestionSaveRequest> questions
  ) {
  }

  public record ExamPaperEditorResponse(
      Long id,
      String paperCode,
      String paperName,
      String roleScope,
      BigDecimal passScore,
      Boolean enabled,
      List<ExamQuestionEditorResponse> questions
  ) {
  }

  public record ExamPublishRequest(
      Long paperId,
      String title,
      String startAt,
      String dueAt,
      List<String> storeIds,
      List<String> targetRoles,
      List<Long> userIds
  ) {
  }

  public record ExamCandidateResponse(
      Long userId,
      String displayName,
      String role,
      String roleLabel,
      String storeId,
      String storeName
  ) {
  }

  public record ExamCampaignResponse(
      Long id,
      Long paperId,
      String paperName,
      String title,
      String status,
      String statusLabel,
      String startAt,
      String dueAt,
      String targetRoles,
      Integer assignedCount,
      Integer completedCount,
      BigDecimal completionRate,
      Integer passedCount,
      BigDecimal passRate,
      Integer overdueCount,
      BigDecimal averageScore,
      String publishedAt
  ) {
  }

  public record ExamAssignmentResponse(
      Long id,
      Long campaignId,
      Long paperId,
      String examTitle,
      String paperName,
      Long userId,
      String examineeName,
      String examineeRole,
      String storeId,
      String storeName,
      String status,
      String statusLabel,
      String startAt,
      String dueAt,
      String completedAt,
      Long attemptId,
      BigDecimal score,
      Boolean passed
  ) {
  }

  public record ExamCampaignDetailResponse(
      ExamCampaignResponse campaign,
      List<ExamAssignmentResponse> assignments
  ) {
  }

  public record ExamCenterOverviewResponse(
      String accessMode,
      Boolean canManage,
      Boolean canExport,
      List<ExamPaperSummaryResponse> papers,
      List<ExamCampaignResponse> campaigns,
      List<ExamAssignmentResponse> assignments,
      List<ExamCandidateResponse> candidates
  ) {
  }

  public record ExamAnswerInput(
      Long questionId,
      String userAnswer
  ) {
  }

  public record ExamSubmissionRequest(
      Boolean violated,
      List<ExamAnswerInput> answers
  ) {
  }

  public record ExamAggregate(
      Integer activeExamCount,
      Integer assignedCount,
      Integer completedCount,
      Integer passedCount,
      Integer overdueCount,
      BigDecimal averageScore
  ) {
  }

  public record ExamStoreAggregate(
      String storeId,
      String storeName,
      Integer assignedCount,
      Integer completedCount,
      Integer passedCount,
      Integer overdueCount,
      BigDecimal averageScore
  ) {
  }
}
