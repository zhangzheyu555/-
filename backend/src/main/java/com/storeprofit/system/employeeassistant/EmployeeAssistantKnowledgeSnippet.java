package com.storeprofit.system.employeeassistant;

/** Approved, tenant-local knowledge excerpt that may be supplied to the employee-only provider. */
record EmployeeAssistantKnowledgeSnippet(long knowledgeId, int version, String category, String title, String answer) {
}
