package com.storeprofit.system.operations;

import java.math.BigDecimal;
import java.util.List;

public final class OperationsBusinessModels {
  private OperationsBusinessModels() {
  }

  public record InventoryCheckLineRequest(
      String itemName,
      String itemCode,
      String category,
      String spec,
      String unit,
      BigDecimal packageQuantity,
      BigDecimal unitPrice,
      BigDecimal unitPriceEach,
      BigDecimal countedQuantity,
      String note
  ) {
  }

  public record InventoryCheckRequest(
      Long id,
      String storeId,
      String checkDate,
      String note,
      List<InventoryCheckLineRequest> lines
  ) {
  }

  public record InventoryCheckLineResponse(
      Long id,
      String itemName,
      String itemCode,
      String category,
      String spec,
      String unit,
      BigDecimal packageQuantity,
      BigDecimal unitPrice,
      BigDecimal unitPriceEach,
      BigDecimal countedQuantity,
      BigDecimal amount,
      String note
  ) {
  }

  public record InventoryCheckResponse(
      Long id,
      String checkNo,
      String storeId,
      String storeName,
      String checkDate,
      String status,
      String statusLabel,
      BigDecimal totalAmount,
      Long submittedBy,
      Long reviewedBy,
      String reviewedAt,
      String note,
      String createdAt,
      String updatedAt,
      List<InventoryCheckLineResponse> lines
  ) {
  }

  public record ExamQuestionResponse(
      Long id,
      String questionType,
      String questionText,
      List<String> options,
      BigDecimal score,
      Integer sortOrder
  ) {
  }

  public record ExamPaperResponse(
      Long id,
      String paperCode,
      String paperName,
      String roleScope,
      BigDecimal passScore,
      Boolean enabled,
      List<ExamQuestionResponse> questions
  ) {
  }

  public record ExamAnswerRequest(
      Long questionId,
      String userAnswer
  ) {
  }

  public record ExamAttemptRequest(
      Long paperId,
      String examineeName,
      String storeId,
      Boolean violated,
      List<ExamAnswerRequest> answers
  ) {
  }

  public record ExamAnswerResponse(
      Long questionId,
      String questionText,
      String userAnswer,
      Boolean correct,
      BigDecimal score
  ) {
  }

  public record ExamAttemptResponse(
      Long id,
      Long paperId,
      String paperName,
      String examineeName,
      String examineeRole,
      String storeId,
      String storeName,
      BigDecimal score,
      Boolean passed,
      Boolean violated,
      Long submittedBy,
      String submittedAt,
      List<ExamAnswerResponse> answers
  ) {
  }

  public record TrainingMaterialResponse(
      Long id,
      String materialCode,
      String title,
      String category,
      List<String> imageUrls,
      String content,
      Boolean enabled,
      Integer sortOrder,
      Boolean learned,
      String learnedAt
  ) {
  }

  public record TrainingLearningRecordResponse(
      Long id,
      Long materialId,
      String materialTitle,
      String userName,
      String storeId,
      Boolean learned,
      String learnedAt
  ) {
  }
}
