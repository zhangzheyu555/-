package com.storeprofit.system.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class AssistantService {
  private static final int CONTEXT_LIMIT = 14000;
  private static final int HISTORY_LIMIT = 8;

  private static final List<String> DEFAULT_BLOCKED_WORDS = List.of(
      "赌博", "博彩", "色情", "黄色", "裸聊", "约炮", "毒品", "枪支", "爆炸", "恐怖",
      "诈骗", "洗钱", "黑客", "攻击", "木马", "病毒", "破解", "脱库", "撞库",
      "身份证号", "银行卡", "api key", "apikey", "access token", "密钥", "令牌"
  );

  private static final List<String> SYSTEM_TERMS = List.of(
      "门店", "利润", "利润表", "营业额", "营业收入", "营收", "流水", "实收", "净利", "净利润",
      "毛利", "成本", "费用", "房租", "人工", "水电", "佣金", "工资", "员工", "人员", "名单",
      "品牌", "霸王茶姬", "瑞幸", "瑞幸咖啡", "茹果", "保利", "荆州之星", "排名", "亏损",
      "月份", "数据", "录入", "导出", "报销", "巡店", "督导", "门店详情", "用户权限",
      "操作日志", "系统", "登录", "管理员", "店长", "报表", "数据助手"
  );

  private final DeepSeekProperties properties;

  public AssistantService(DeepSeekProperties properties) {
    this.properties = properties;
  }

  public AssistantChatResponse chat(AssistantChatRequest request) {
    String message = clean(request.message());
    if (hasBlockedWord(message)) {
      return new AssistantChatResponse(
          "这个问题包含系统屏蔽词，我只能协助处理门店利润系统内的经营数据、人员工资、报表和操作问题。",
          false,
          true,
          "blocked-word"
      );
    }

    if (!isInScope(message)) {
      return new AssistantChatResponse(
          "我只能回答门店利润系统相关问题，例如利润、营收、成本、门店、员工工资、数据录入、报表导出和权限操作。",
          false,
          true,
          "out-of-scope"
      );
    }

    if (!properties.hasApiKey()) {
      return new AssistantChatResponse(
          "数据助手还没有配置 DeepSeek API Key。你可以先使用本地数据查询，配置 Key 后我就能按当前系统数据回答。",
          false,
          false,
          "missing-api-key"
      );
    }

    try {
      String answer = callDeepSeek(request, message);
      return new AssistantChatResponse(answer, true, false, "deepseek");
    } catch (RestClientException | IllegalStateException ex) {
      return new AssistantChatResponse(
          "数据助手暂时连接失败。你可以先用门店、月份、指标进行本地查询，或稍后重试。",
          false,
          false,
          "deepseek-error"
      );
    }
  }

  private String callDeepSeek(AssistantChatRequest request, String message) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int timeoutMs = Math.toIntExact(Math.min(properties.getTimeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(timeoutMs);
    factory.setReadTimeout(timeoutMs);

    RestClient client = RestClient.builder()
        .baseUrl(properties.getBaseUrl())
        .requestFactory(factory)
        .build();

    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt(request.dataContext())));
    appendHistory(messages, request.history());
    messages.add(Map.of("role", "user", "content", message));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.getModel());
    body.put("messages", messages);
    body.put("temperature", properties.getTemperature());
    body.put("max_tokens", properties.getMaxTokens());
    body.put("thinking", Map.of("type", "disabled"));
    body.put("stream", false);

    Map<String, Object> response = client.post()
        .uri("/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + properties.getApiKey())
        .body(body)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});

    String content = extractContent(response);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("DeepSeek response has no content");
    }
    return content.trim();
  }

  private void appendHistory(List<Map<String, String>> messages, List<AssistantChatTurn> history) {
    if (history == null || history.isEmpty()) {
      return;
    }
    int start = Math.max(0, history.size() - HISTORY_LIMIT);
    for (AssistantChatTurn turn : history.subList(start, history.size())) {
      String role = "assistant".equals(turn.role()) ? "assistant" : "user";
      String content = clean(turn.content());
      if (!content.isBlank() && !hasBlockedWord(content)) {
        messages.add(Map.of("role", role, "content", content));
      }
    }
  }

  private String systemPrompt(String dataContext) {
    String context = clean(dataContext);
    if (context.length() > CONTEXT_LIMIT) {
      context = context.substring(0, CONTEXT_LIMIT) + "\n...（数据上下文已截断）";
    }
    return """
        你是“门店利润系统”的数据助手，只能回答当前系统范围内的问题。

        系统范围包括：利润概览、利润表、门店详情、督导巡店、数据助手、数据录入、报销栏、数据导出、门店管理、用户权限、员工工资、操作日志，以及下方数据上下文里的门店经营数据。

        回答规则：
        1. 只回答门店利润系统相关内容；用户问系统外问题时，礼貌说明无法回答，并引导回系统功能。
        2. 不能编造数据。上下文没有的数据，明确说“当前系统暂无该数据”。
        3. 不输出违法、色情、暴力、攻击、破解、隐私密钥或账号密码相关内容。
        4. 用中文数据助手口吻，先给结论，再给关键数据明细。不要只回答一个数字。
        5. 涉及金额时保留系统数据口径，不要自行改公式。
        6. 排名类回答请使用清晰的编号列表，格式为“1. 门店名：金额元”，不要使用 Markdown 粗体符号。
        7. 查询某店某月经营时，尽量同时给出营业总收入、实收收入、成本合计、毛利润、费用合计、净利润、净利率。上下文缺失的数据要说明缺失。
        8. 查询各月趋势时，逐月列出数据，并补充合计、最高月、最低月。

        当前系统数据上下文：
        """ + (context.isBlank() ? "暂无前端传入的数据上下文。" : context);
  }

  @SuppressWarnings("unchecked")
  private String extractContent(Map<String, Object> response) {
    if (response == null) {
      return null;
    }
    Object choicesObj = response.get("choices");
    if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
      return null;
    }
    Object first = choices.getFirst();
    if (!(first instanceof Map<?, ?> choice)) {
      return null;
    }
    Object messageObj = choice.get("message");
    if (!(messageObj instanceof Map<?, ?> msg)) {
      return null;
    }
    Object content = msg.get("content");
    return content == null ? null : String.valueOf(content);
  }

  private boolean hasBlockedWord(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    List<String> words = new ArrayList<>(DEFAULT_BLOCKED_WORDS);
    words.addAll(properties.getBlockedWords());
    return words.stream()
        .filter(w -> w != null && !w.isBlank())
        .map(w -> w.toLowerCase(Locale.ROOT))
        .anyMatch(lower::contains);
  }

  private boolean isInScope(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.length() <= 12 && List.of("你好", "您好", "你是谁", "帮助", "怎么用", "hi", "hello").stream().anyMatch(lower::contains)) {
      return true;
    }
    return SYSTEM_TERMS.stream().anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
  }

  private String clean(String text) {
    if (text == null) {
      return "";
    }
    return text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
  }
}
