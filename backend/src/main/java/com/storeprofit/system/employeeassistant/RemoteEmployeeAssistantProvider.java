package com.storeprofit.system.employeeassistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Existing dedicated employee-assistant upstream: /api/v1/health and /api/v1/chat. */
final class RemoteEmployeeAssistantProvider implements EmployeeAssistantProvider {
  private final String upstreamUrl;
  private final String apiToken;
  private final boolean configured;

  RemoteEmployeeAssistantProvider(String upstreamUrl, String apiToken) {
    this.upstreamUrl = EmployeeAssistantProviderSupport.trimTrailingSlash(upstreamUrl);
    this.apiToken = EmployeeAssistantProviderSupport.trim(apiToken);
    this.configured = !this.apiToken.isBlank()
        && EmployeeAssistantProviderSupport.isSupportedHttpUrl(this.upstreamUrl);
  }

  @Override
  public boolean configured() {
    return configured;
  }

  @Override
  public String target() {
    return upstreamUrl;
  }

  @Override
  public HttpRequest healthRequest(Duration timeout) {
    return request("/api/v1/health", timeout).GET().build();
  }

  @Override
  public HttpRequest chatRequest(
      String sanitizedMessage,
      String conversationId,
      List<EmployeeAssistantKnowledgeSnippet> knowledgeSnippets,
      Duration timeout,
      ObjectMapper objectMapper
  ) throws JsonProcessingException {
    String payload = objectMapper.writeValueAsString(new LinkedHashMap<>(Map.of(
        "message", providerMessage(sanitizedMessage, knowledgeSnippets),
        "conversation_id", conversationId
    )));
    return request("/api/v1/chat", timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        .build();
  }

  @Override
  public Answer parseChatResponse(String body, ObjectMapper objectMapper) throws IOException {
    JsonNode root = objectMapper.readTree(body == null ? "" : body);
    if (root == null || !root.isObject() || !root.path("answer").isTextual()) {
      throw new IOException("remote response does not contain answer");
    }
    return new Answer(root.path("answer").asText(""), root.path("needs_human").asBoolean(false));
  }

  private HttpRequest.Builder request(String path, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(upstreamUrl + path))
        .timeout(timeout)
        .header("Authorization", "Bearer " + apiToken);
  }

  private String providerMessage(String question, List<EmployeeAssistantKnowledgeSnippet> snippets) {
    if (snippets == null || snippets.isEmpty()) return question;
    StringBuilder message = new StringBuilder("员工问题：").append(question).append("\n\n已发布标准话术参考：");
    snippets.stream().limit(3).forEach(snippet -> message
        .append("\n- ").append(snippet.title()).append("：")
        .append(trim(snippet.answer(), 800)));
    return message.toString();
  }

  private String trim(String value, int maximum) {
    String normalized = value == null ? "" : value.trim();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }
}
