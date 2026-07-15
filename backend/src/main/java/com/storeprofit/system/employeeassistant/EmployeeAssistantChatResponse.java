package com.storeprofit.system.employeeassistant;

/** Controlled subset of an external assistant response exposed to the browser. */
public record EmployeeAssistantChatResponse(
    String answer,
    boolean configured,
    String requestId,
    String sessionId,
    boolean needsHuman,
    EmployeeAssistantAnswerSource answerSource,
    Long knowledgeId,
    Integer knowledgeVersion,
    String knowledgeTitle,
    String handoffCategory
) {
}
