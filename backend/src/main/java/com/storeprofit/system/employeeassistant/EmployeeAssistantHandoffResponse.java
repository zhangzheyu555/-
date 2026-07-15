package com.storeprofit.system.employeeassistant;

import java.time.LocalDateTime;

public record EmployeeAssistantHandoffResponse(
    String id,
    String storeId,
    String question,
    String category,
    String status,
    long requestedBy,
    String requestedByName,
    Long handledBy,
    String handledByName,
    String resolution,
    LocalDateTime createdAt,
    LocalDateTime claimedAt,
    LocalDateTime respondedAt,
    LocalDateTime closedAt,
    LocalDateTime expiresAt
) {
}
