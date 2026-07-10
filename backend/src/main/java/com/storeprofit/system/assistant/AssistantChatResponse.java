package com.storeprofit.system.assistant;

import java.time.Instant;
import java.util.List;

public record AssistantChatResponse(
    String answer,
    String localAnswer,
    String deepSeekAnswer,
    boolean deepSeekAvailable,
    String deepSeekError,
    String resolvedStoreId,
    String resolvedStoreName,
    String resolvedMonth,
    String intent,
    boolean aiUsed,
    boolean blocked,
    String source,
    String dataSource,
    String month,
    List<String> storeScope,
    List<String> warnings,
    String model,
    boolean fallback,
    String fallbackReason,
    String requestId,
    Instant generatedAt
) {
  public AssistantChatResponse(
      String answer,
      boolean aiUsed,
      boolean blocked,
      String source,
      String dataSource,
      String month,
      List<String> storeScope,
      List<String> warnings
  ) {
    this(
        answer,
        aiUsed ? "" : answer,
        aiUsed ? answer : null,
        aiUsed,
        null,
        "",
        "",
        month,
        "",
        aiUsed,
        blocked,
        source,
        dataSource,
        month,
        storeScope,
        warnings,
        null,
        !aiUsed,
        null,
        null,
        Instant.now()
    );
  }

  public AssistantChatResponse {
    storeScope = storeScope == null ? List.of() : List.copyOf(storeScope);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    localAnswer = localAnswer == null ? "" : localAnswer;
    resolvedStoreId = resolvedStoreId == null ? "" : resolvedStoreId;
    resolvedStoreName = resolvedStoreName == null ? "" : resolvedStoreName;
    resolvedMonth = resolvedMonth == null ? "" : resolvedMonth;
    intent = intent == null ? "" : intent;
    model = model == null ? "" : model;
    generatedAt = generatedAt == null ? Instant.now() : generatedAt;
  }
}