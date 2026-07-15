package com.storeprofit.system.employeeassistant;

public record EmployeeAssistantKnowledgeDraftRequest(
    String category,
    String title,
    String keywords,
    String standardAnswer
) {
}
