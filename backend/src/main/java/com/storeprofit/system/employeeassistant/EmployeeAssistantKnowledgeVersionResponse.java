package com.storeprofit.system.employeeassistant;

import java.time.LocalDateTime;

public record EmployeeAssistantKnowledgeVersionResponse(
    long id,
    long knowledgeId,
    int version,
    String category,
    String title,
    String keywords,
    String standardAnswer,
    String publishAction,
    Long publishedBy,
    LocalDateTime publishedAt
) {
}
