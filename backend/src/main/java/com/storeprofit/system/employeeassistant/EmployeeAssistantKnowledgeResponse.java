package com.storeprofit.system.employeeassistant;

import java.time.LocalDateTime;

public record EmployeeAssistantKnowledgeResponse(
    long id,
    String category,
    String title,
    String keywords,
    String standardAnswer,
    String status,
    int currentVersion,
    Long createdBy,
    Long updatedBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
