package com.storeprofit.system.employeeassistant;

/**
 * Browser-safe availability states for the independent employee assistant upstream.
 *
 * <p>The state intentionally contains no endpoint, token, upstream response, or implementation
 * details. It only tells the employee-facing page whether sending a general-service question is
 * currently possible.</p>
 */
public enum EmployeeAssistantState {
  UNCONFIGURED,
  AUTH_FAILED,
  UNAVAILABLE,
  READY
}
