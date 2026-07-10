package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantChatResponseTest {
  @Test
  void exposesCompatibleFieldsAndTrustMetadata() {
    List<String> storeScope = new ArrayList<>(List.of("rg1"));
    List<String> warnings = new ArrayList<>(List.of("回答基于当前页面可见数据"));

    AssistantChatResponse response = new AssistantChatResponse(
        "answer",
        true,
        false,
        "deepseek-frontend-context",
        "AI_ENRICHED_FRONTEND_CONTEXT",
        "2026-07",
        storeScope,
        warnings
    );

    storeScope.add("other");
    warnings.add("other warning");

    assertThat(response.answer()).isEqualTo("answer");
    assertThat(response.aiUsed()).isTrue();
    assertThat(response.blocked()).isFalse();
    assertThat(response.source()).isEqualTo("deepseek-frontend-context");
    assertThat(response.dataSource()).isEqualTo("AI_ENRICHED_FRONTEND_CONTEXT");
    assertThat(response.month()).isEqualTo("2026-07");
    assertThat(response.storeScope()).containsExactly("rg1");
    assertThat(response.warnings()).containsExactly("回答基于当前页面可见数据");
  }
}
