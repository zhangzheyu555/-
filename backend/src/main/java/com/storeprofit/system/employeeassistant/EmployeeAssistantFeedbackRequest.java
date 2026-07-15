package com.storeprofit.system.employeeassistant;

public record EmployeeAssistantFeedbackRequest(
    String answerSource,
    Long knowledgeId,
    Integer knowledgeVersion,
    boolean helpful,
    String reasonCode
) {
}
