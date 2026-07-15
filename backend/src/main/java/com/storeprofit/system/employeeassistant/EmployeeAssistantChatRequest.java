package com.storeprofit.system.employeeassistant;

/**
 * Employee-facing assistant input.  Attachments and business records are intentionally not part
 * of this contract, so they can never be forwarded to the external service.
 */
public record EmployeeAssistantChatRequest(String sessionId, String message) {
}
