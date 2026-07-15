package com.storeprofit.system.employeeassistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

/**
 * Narrow transport boundary for the employee assistant only.
 *
 * <p>Providers receive a sanitized general-service question and an opaque conversation ID. They
 * never receive an authenticated user, store data, financial data, attachments, or any business
 * assistant prompt/query.</p>
 */
interface EmployeeAssistantProvider {
  boolean configured();

  HttpRequest healthRequest(Duration timeout);

  HttpRequest chatRequest(
      String sanitizedMessage,
      String conversationId,
      List<EmployeeAssistantKnowledgeSnippet> knowledgeSnippets,
      Duration timeout,
      ObjectMapper objectMapper
  ) throws JsonProcessingException;

  Answer parseChatResponse(String body, ObjectMapper objectMapper) throws IOException;

  record Answer(String answer, boolean needsHuman) {
  }
}
