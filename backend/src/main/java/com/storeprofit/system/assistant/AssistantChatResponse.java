package com.storeprofit.system.assistant;

import java.util.List;

public record AssistantChatResponse(
    String answer,
    boolean aiUsed,
    boolean blocked,
    String source,
    String dataSource,
    String month,
    List<String> storeScope,
    List<String> warnings
) {
  public AssistantChatResponse {
    storeScope = storeScope == null ? List.of() : List.copyOf(storeScope);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
