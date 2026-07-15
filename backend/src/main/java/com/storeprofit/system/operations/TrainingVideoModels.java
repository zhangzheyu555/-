package com.storeprofit.system.operations;

import java.math.BigDecimal;

public final class TrainingVideoModels {
  private TrainingVideoModels() {
  }

  public record VideoResponse(
      Long id,
      String videoCode,
      Long courseId,
      String courseTitle,
      String title,
      String category,
      String description,
      String fileName,
      String contentType,
      Long fileSize,
      BigDecimal durationSeconds,
      Boolean enabled,
      Integer sortOrder,
      String createdAt,
      BigDecimal myWatchedSeconds,
      BigDecimal myLastPosition,
      BigDecimal myPercent,
      Boolean myCompleted
  ) {
  }

  public record ProgressReportRequest(BigDecimal positionSeconds, BigDecimal durationSeconds) {
  }

  public record ProgressResponse(
      Long videoId,
      BigDecimal watchedSeconds,
      BigDecimal lastPosition,
      BigDecimal percent,
      Boolean completed
  ) {
  }

  public record ViewerProgressRow(
      Long userId,
      String userName,
      String storeId,
      String storeName,
      Long videoId,
      String videoTitle,
      String videoCategory,
      BigDecimal watchedSeconds,
      BigDecimal percent,
      Boolean completed,
      String lastWatchedAt
  ) {
  }
}
