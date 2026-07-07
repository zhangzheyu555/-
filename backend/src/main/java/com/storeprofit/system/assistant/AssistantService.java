package com.storeprofit.system.assistant;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.finance.ProfitSummaryResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class AssistantService {
  private static final int CONTEXT_LIMIT = 20000;
  private static final int HISTORY_LIMIT = 8;
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

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
      "操作日志", "系统", "登录", "老板", "店长", "报表", "数据助手", "趋势", "各月"
  );

  private final DeepSeekProperties properties;
  private final FinanceService financeService;

  public AssistantService(DeepSeekProperties properties, FinanceService financeService) {
    this.properties = properties;
    this.financeService = financeService;
  }

  public AssistantChatResponse chat(AuthUser user, AssistantChatRequest request) {
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

    boolean hasFrontContext = !clean(request.dataContext()).isBlank();
    String dataContext = buildDataContext(user, message, request.dataContext());
    if (!properties.hasApiKey()) {
      return new AssistantChatResponse(localAnswer(user, message, false), false, false,
          hasFrontContext ? "backend-finance-fallback-with-frontend-context" : "backend-finance");
    }

    try {
      String answer = sanitizeAnswer(callDeepSeek(request, message, dataContext));
      return new AssistantChatResponse(answer, true, false,
          hasFrontContext ? "deepseek-frontend-context" : "deepseek-backend-finance");
    } catch (RestClientException | IllegalStateException ex) {
      return new AssistantChatResponse(localAnswer(user, message, false), false, false,
          hasFrontContext ? "backend-finance-fallback-with-frontend-context" : "backend-finance-fallback");
    }
  }

  private String localAnswer(AuthUser user, String message, boolean hasFrontContext) {
    List<String> months = targetMonths(user, message);
    String sourcePrefix = hasFrontContext ? "" : "后端财务库口径：\n";
    if (mentionsRanking(message)) {
      return sourcePrefix + rankingAnswer(user, message, months);
    }
    if (message.contains("亏损")) {
      return sourcePrefix + lossAnswer(user, months);
    }
    Optional<ProfitEntryResponse> storeEntry = findMentionedStore(user, message, months);
    if (storeEntry.isPresent()) {
      return sourcePrefix + storeAnswer(user, message, storeEntry.get().storeId(), months);
    }
    return sourcePrefix + overviewAnswer(user, months.getFirst());
  }

  private String rankingAnswer(AuthUser user, String message, List<String> months) {
    String metric = metric(message);
    Map<String, StoreAggregate> aggregates = new LinkedHashMap<>();
    for (String month : months) {
      for (ProfitEntryResponse entry : financeService.entries(user, month, null, null)) {
        StoreAggregate aggregate = aggregates.computeIfAbsent(entry.storeId(), key -> new StoreAggregate(entry));
        aggregate.add(month, amount(entry, metric));
      }
    }
    List<StoreAggregate> rows = aggregates.values().stream()
        .sorted(Comparator.comparing(StoreAggregate::value).reversed())
        .limit(10)
        .toList();
    if (rows.isEmpty()) {
      return "当前系统暂无这些月份的利润数据。";
    }
    String label = metricLabel(metric);
    StringBuilder answer = new StringBuilder("数据助手已按").append(label).append("为你汇总排名");
    answer.append(months.size() == 1 ? "（" + months.getFirst() + "）" : "（" + months.getFirst() + " 至 " + months.getLast() + "）");
    answer.append("：\n");
    for (int i = 0; i < rows.size(); i++) {
      StoreAggregate row = rows.get(i);
      answer.append(i + 1).append(". ")
          .append(row.storeName).append("（").append(row.brandName).append("）：")
          .append(money(row.value())).append("元，覆盖 ").append(row.months.size()).append(" 个月\n");
    }
    return answer.toString().trim();
  }

  private String lossAnswer(AuthUser user, List<String> months) {
    List<ProfitEntryResponse> rows = months.stream()
        .flatMap(month -> financeService.entries(user, month, null, null).stream())
        .filter(entry -> entry.net().compareTo(BigDecimal.ZERO) < 0)
        .sorted(Comparator.comparing(ProfitEntryResponse::net))
        .limit(12)
        .toList();
    if (rows.isEmpty()) {
      return "所选月份内没有亏损门店。你可以继续问“7月净利润排名”或指定某个门店查看明细。";
    }
    StringBuilder answer = new StringBuilder("数据助手查到以下亏损记录：\n");
    for (int i = 0; i < rows.size(); i++) {
      ProfitEntryResponse row = rows.get(i);
      answer.append(i + 1).append(". ")
          .append(row.month()).append(" ")
          .append(row.storeName()).append("（").append(row.brandName()).append("）：净利润 ")
          .append(money(row.net())).append("元，实收收入 ")
          .append(money(row.income())).append("元，费用合计 ")
          .append(money(row.expenseSum())).append("元\n");
    }
    return answer.toString().trim();
  }

  private String storeAnswer(AuthUser user, String message, String storeId, List<String> months) {
    List<ProfitEntryResponse> rows = months.stream()
        .map(month -> financeService.entries(user, month, null, storeId).stream().findFirst())
        .flatMap(Optional::stream)
        .toList();
    if (rows.isEmpty()) {
      return "当前系统暂无该门店在所选月份的利润数据。";
    }
    StringBuilder answer = new StringBuilder("数据助手已查询 ").append(rows.getFirst().storeName()).append(" 的经营数据：\n");
    for (ProfitEntryResponse row : rows) {
      answer.append(row.month()).append("：营业总收入 ").append(money(row.sales())).append("元，实收收入 ")
          .append(money(row.income())).append("元，成本合计 ").append(money(row.costSum())).append("元，毛利润 ")
          .append(money(row.gross())).append("元，费用合计 ").append(money(row.expenseSum())).append("元，净利润 ")
          .append(money(row.net())).append("元，净利率 ").append(percent(row.margin())).append("。\n");
    }
    if (message.contains("建议") || message.contains("分析")) {
      answer.append("建议优先检查费用占比、原材料损耗和人工排班，异常月份可进入利润录入页查看明细字段。");
    }
    return answer.toString().trim();
  }

  private String overviewAnswer(AuthUser user, String month) {
    ProfitSummaryResponse summary = financeService.dashboard(user, month, null).summary();
    return "数据助手为你汇总 " + summary.month() + " 的经营概况：营业总收入 " + money(summary.sales())
        + "元，实收收入 " + money(summary.income())
        + "元，成本合计 " + money(summary.costSum())
        + "元，费用合计 " + money(summary.expenseSum())
        + "元，净利润 " + money(summary.net())
        + "元，净利率 " + percent(summary.margin())
        + "。当前有 " + summary.entryCount() + " 条利润记录，覆盖 " + summary.storeCount()
        + " 家门店，其中风险门店 " + summary.riskStoreCount() + " 家。";
  }

  private String buildDataContext(AuthUser user, String message, String frontContext) {
    String extra = clean(frontContext);
    if (!extra.isBlank()) {
      return limitContext("前端当前可见数据上下文（优先级最高；未指定月份时按前端说明的默认月份回答）：\n" + extra);
    }

    StringBuilder context = new StringBuilder();
    for (String month : targetMonths(user, message).stream().limit(6).toList()) {
      ProfitSummaryResponse summary = financeService.dashboard(user, month, null).summary();
      context.append("月份：").append(month)
          .append("；营业总收入：").append(money(summary.sales()))
          .append("；实收收入：").append(money(summary.income()))
          .append("；净利润：").append(money(summary.net()))
          .append("；净利率：").append(percent(summary.margin()))
          .append("；风险门店数：").append(summary.riskStoreCount()).append("\n");
      financeService.entries(user, month, null, null).stream()
          .sorted(Comparator.comparing(ProfitEntryResponse::net).reversed())
          .limit(50)
          .forEach(entry -> context.append("- ")
              .append(entry.storeName()).append("（").append(entry.brandName()).append("，").append(entry.area()).append("）")
              .append("：营业总收入 ").append(money(entry.sales()))
              .append("，实收 ").append(money(entry.income()))
              .append("，成本 ").append(money(entry.costSum()))
              .append("，费用 ").append(money(entry.expenseSum()))
              .append("，净利润 ").append(money(entry.net()))
              .append("，净利率 ").append(percent(entry.margin()))
              .append("，状态 ").append(entry.risk()).append("\n"));
    }
    return limitContext(context.toString());
  }

  private String limitContext(String value) {
    return value.length() > CONTEXT_LIMIT ? value.substring(0, CONTEXT_LIMIT) + "\n...（数据上下文已截断）" : value;
  }

  private String callDeepSeek(AssistantChatRequest request, String message, String dataContext) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int timeoutMs = Math.toIntExact(Math.min(properties.getTimeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(timeoutMs);
    factory.setReadTimeout(timeoutMs);

    RestClient client = RestClient.builder()
        .baseUrl(properties.getBaseUrl())
        .requestFactory(factory)
        .build();

    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt(dataContext)));
    appendHistory(messages, request.history());
    messages.add(Map.of("role", "user", "content", message));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.getModel());
    body.put("messages", messages);
    body.put("temperature", properties.getTemperature());
    body.put("max_tokens", properties.getMaxTokens());
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
    return """
        你是“门店利润系统”的数据助手，只能回答当前系统范围内的问题。

        系统范围包括：利润概览、利润表、门店详情、督导巡店、数据助手、数据录入、报销栏、数据导出、门店管理、用户权限、员工工资、操作日志，以及下方当前数据上下文里的门店经营数据。

        回答规则：
        1. 只回答门店利润系统相关内容；用户问系统外问题时，礼貌说明无法回答，并引导回系统功能。
        2. 不能编造数据。上下文没有的数据，明确说“当前系统暂无该数据”。
        3. 不输出违法、色情、暴力、攻击、破解、隐私密钥或账号密码相关内容。
        4. 用中文数据助手口吻，先给结论，再给关键数据明细。不要只回答一个数字。
        5. 涉及金额时保留系统数据口径，不要自行改公式。
        6. 排名类回答请使用清晰编号列表，格式为“1. 门店名：金额元”。
        7. 查询某店某月经营时，同时给出营业总收入、实收收入、成本合计、毛利润、费用合计、净利润、净利率。
        8. 查询各月趋势时，逐月列出数据，并补充合计、最高月、最低月。
        9. 用户没有明确说“各月、每月、趋势、区间、1-5月”等多月份意图时，只回答上下文说明的默认月份，不要主动改成各月趋势。

        当前数据上下文：
        """ + (dataContext.isBlank() ? "暂无可用数据。" : dataContext);
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

  private Optional<ProfitEntryResponse> findMentionedStore(AuthUser user, String message, List<String> months) {
    return months.stream()
        .flatMap(month -> financeService.entries(user, month, null, null).stream())
        .filter(entry -> containsAny(message, entry.storeName(), entry.storeCode(), entry.storeId()))
        .findFirst();
  }

  private List<String> targetMonths(AuthUser user, String message) {
    List<String> availableMonths = dataMonths(user);
    LinkedHashSet<String> months = new LinkedHashSet<>();
    int year = availableMonths.stream()
        .findFirst()
        .map(YearMonth::parse)
        .map(YearMonth::getYear)
        .orElse(YearMonth.now(BUSINESS_ZONE).getYear());

    Matcher range = Pattern.compile("(?<!\\d)(1[0-2]|[1-9])\\s*[-到至~]\\s*(1[0-2]|[1-9])\\s*月").matcher(message);
    while (range.find()) {
      int start = Integer.parseInt(range.group(1));
      int end = Integer.parseInt(range.group(2));
      for (int month = Math.min(start, end); month <= Math.max(start, end); month++) {
        months.add(YearMonth.of(year, month).toString());
      }
    }

    Matcher full = Pattern.compile("(20\\d{2})[-年](1[0-2]|0?[1-9])月?").matcher(message);
    while (full.find()) {
      months.add(YearMonth.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2))).toString());
    }

    Matcher shortMonth = Pattern.compile("(?<!\\d)(1[0-2]|[1-9])\\s*月").matcher(message);
    while (shortMonth.find()) {
      months.add(YearMonth.of(year, Integer.parseInt(shortMonth.group(1))).toString());
    }

    if (months.isEmpty() && (message.contains("各月") || message.contains("趋势"))) {
      months.addAll(availableMonths.stream().limit(6).toList());
    }
    if (months.isEmpty()) {
      months.add(availableMonths.stream().findFirst().orElse(YearMonth.now(BUSINESS_ZONE).toString()));
    }
    return new ArrayList<>(months);
  }

  private List<String> dataMonths(AuthUser user) {
    return financeService.months(user).stream()
        .filter(month -> !financeService.entries(user, month, null, null).isEmpty())
        .distinct()
        .toList();
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

  private boolean mentionsRanking(String message) {
    return message.contains("排名") || message.contains("排行") || message.contains("前") || message.toLowerCase(Locale.ROOT).contains("top");
  }

  private String metric(String message) {
    if (message.contains("营业") || message.contains("营收") || message.contains("收入")) {
      return "income";
    }
    return "net";
  }

  private String metricLabel(String metric) {
    return "income".equals(metric) ? "实收收入" : "净利润";
  }

  private BigDecimal amount(ProfitEntryResponse entry, String metric) {
    return "income".equals(metric) ? entry.income() : entry.net();
  }

  private boolean containsAny(String message, String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank() && message.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private String money(BigDecimal value) {
    return MONEY.format(value == null ? BigDecimal.ZERO : value);
  }

  private String percent(BigDecimal value) {
    BigDecimal safe = value == null ? BigDecimal.ZERO : value;
    return safe.multiply(new BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP) + "%";
  }

  private String clean(String text) {
    if (text == null) {
      return "";
    }
    return text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
  }

  private String sanitizeAnswer(String text) {
    return clean(text)
        .replace("**", "")
        .replaceAll("(?m)^#{1,6}\\s*", "")
        .trim();
  }

  private static final class StoreAggregate {
    private final String storeName;
    private final String brandName;
    private final Set<String> months = new LinkedHashSet<>();
    private BigDecimal value = BigDecimal.ZERO;

    private StoreAggregate(ProfitEntryResponse entry) {
      this.storeName = entry.storeName();
      this.brandName = entry.brandName();
    }

    private void add(String month, BigDecimal amount) {
      months.add(month);
      value = value.add(amount == null ? BigDecimal.ZERO : amount);
    }

    private BigDecimal value() {
      return value;
    }
  }
}
