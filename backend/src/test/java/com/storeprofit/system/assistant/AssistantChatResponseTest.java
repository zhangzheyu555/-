package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantChatResponseTest {
  @Test
  void serializesOnlyTheTrustOrientedProtocol() throws Exception {
    AssistantChatResponse response = new AssistantChatResponse(
        "7月净利润为什么变化",
        "AI",
        "自动识别为经营分析",
        new AssistantChatResponse.LocalData(
            "数据库事实",
            List.of(new AssistantChatResponse.Metric(
                "net", "净利润", new BigDecimal("100"), "CNY", "¥100.00", null, ""
            )),
            "2026-07",
            "测试门店",
            "MySQL 8 财务库"
        ),
        new AssistantChatResponse.AiAnalysis(
            true, "DeepSeek", "deepseek-test", "provider-id", 123,
            "经营结论", List.of("发现"),
            List.of(new AssistantChatResponse.Risk("风险", "证据", "HIGH")),
            List.of(new AssistantChatResponse.PossibleCause("待核实原因", "MEDIUM", "数据依据")),
            List.of(new AssistantChatResponse.Action("动作1", "STORE_MANAGER", "本周五", "改善", "净利率")),
            "MEDIUM", List.of()
        ),
        false,
        null
    );

    JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);

    assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
        "question", "selectedMode", "selectionReason", "localData", "aiAnalysis",
        "fallbackUsed", "error"
    );
    assertThat(json.path("localData").path("summary").asText()).isEqualTo("数据库事实");
    assertThat(json.path("aiAnalysis").path("requestId").asText()).isEqualTo("provider-id");
    assertThat(json.has("answer")).isFalse();
    assertThat(json.has("deepSeekAnswer")).isFalse();
  }

  @Test
  void unavailableAiNeverCopiesLocalSummary() {
    AssistantChatResponse response = AssistantChatResponse.aiUnavailable(
        "问题",
        new AssistantChatResponse.LocalData("本地答案", List.of(), "2026-07", "本店", "MySQL 8 财务库"),
        "DEEPSEEK_NOT_CONFIGURED",
        "AI分析暂时不可用"
    );

    assertThat(response.localData().summary()).isEqualTo("本地答案");
    assertThat(response.aiAnalysis().available()).isFalse();
    assertThat(response.aiAnalysis().summary()).isEmpty();
    assertThat(response.fallbackUsed()).isTrue();
  }

  @Test
  void nestedListsAreDefensivelyCopied() {
    List<AssistantChatResponse.Action> actions = new ArrayList<>(List.of(
        new AssistantChatResponse.Action("动作1", "STORE_MANAGER", "本周五", "改善", "净利率"),
        new AssistantChatResponse.Action("动作2", "FINANCE", "本周五", "改善", "费用率"),
        new AssistantChatResponse.Action("动作3", "BOSS", "本周日", "改善", "净利润")
    ));
    AssistantChatResponse.AiAnalysis analysis = new AssistantChatResponse.AiAnalysis(
        true, "DeepSeek", "model", "id", 1, "结论", List.of("发现"), List.of(),
        List.of(new AssistantChatResponse.PossibleCause("原因", "LOW", "依据")),
        actions, "MEDIUM", List.of()
    );
    actions.add(new AssistantChatResponse.Action("动作4", "BOSS", "下周", "改善", "净利润"));
    assertThat(analysis.actions()).hasSize(3);
  }
}
