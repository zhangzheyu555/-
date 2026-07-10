package com.storeprofit.system.audit;

public record OperationLogResponse(
    long id,
    Long operatorId,
    String operatorName,
    String action,
    String targetType,
    String targetId,
    String storeId,
    String month,
    String reason,
    String createdAt
) {
}
