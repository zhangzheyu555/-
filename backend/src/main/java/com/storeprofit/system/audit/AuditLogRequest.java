package com.storeprofit.system.audit;

public record AuditLogRequest(
    String action,
    String targetType,
    String targetId,
    String storeId,
    String month,
    String reason,
    String beforeJson,
    String afterJson
) {
}
