package com.storeprofit.system.assistant;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.finance.ProfitSummaryResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AssistantService {
  private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
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
      "操作日志", "系统", "登录", "老板", "店长", "报表", "数据助手", "趋势", "各月",
      "仓库", "库存", "叫货", "采购", "入库", "出库", "批次", "退货", "配送", "预警",
      "单据", "巡检", "整改", "红线", "平台", "同步", "运营"
  );

  private final DeepSeekProperties properties;
  private final FinanceService financeService;

  public AssistantService(DeepSeekProperties properties, FinanceService financeService) {
    this.properties = properties;
    this.financeService = financeService;
  }

  public AssistantChatResponse chat(AuthUser user, AssistantChatRequest request) {
    String message = clean(request.message());
    String frontContext = clean(request.dataContext());
    boolean hasFrontContext = frontContextAllowed(user, frontContext) && !frontContext.isBlank();
    if (hasBlockedWord(message)) {
      return response(
          "这个问题包含系统屏蔽词，我只能协助处理门店利润系统内的经营数据、人员工资、报表和操作问题。",
          false,
          true,
          "blocked-word",
          hasFrontContext,
          message,
          user
      );
    }

    if (!isInScope(message) && !(hasFrontContext && isInScope(frontContext))) {
      return response(
          "我只能回答门店利润系统相关问题，例如利润、营收、成本、门店、员工工资、数据录入、报表导出和权限操作。",
          false,
          true,
          "out-of-scope",
          hasFrontContext,
          message,
          user
      );
    }

    Optional<String> roleBoundary = roleBoundaryAnswer(user, message);
    if (roleBoundary.isPresent()) {
      return response(
          roleBoundary.get(),
          false,
          true,
          "role-boundary",
          hasFrontContext,
          message,
          user
      );
    }

    AssistantContext assistantContext = assistantContext(message, frontContext);
    String effectiveMode = request.modeOrDefault();
    if ("AUTO".equals(effectiveMode)) {
      effectiveMode = detectMode(message);
    }
    String requestId = UUID.randomUUID().toString().substring(0, 8);

    String localAnswer = buildLocalAnswer(user, message, frontContext, assistantContext);
    String resolvedMonth = resolveEffectiveMonth(user, message, frontContext, assistantContext);
    String resolvedStoreId = resolveEffectiveStoreId(user, request, assistantContext);
    LocalData local = new LocalData(localAnswer, resolvedStoreId, resolvedMonth);

    if ("LOCAL".equals(effectiveMode)) {
      log.info("Assistant LOCAL mode requestId={} storeId={} month={} intent={}",
          requestId, resolvedStoreId, resolvedMonth, assistantContext.intent());
      return localOnlyResponse(local, assistantContext, hasFrontContext, message, user, requestId);
    }

    String dataContext = buildBusinessAnalysisContext(user, message, frontContext, local);
    if (!properties.isConfigured()) {
      String errorCode = !properties.isEnabled() ? "DEEPSEEK_DISABLED" : "DEEPSEEK_NOT_CONFIGURED";
      String errorMsg = !properties.isEnabled() ? "AI 服务已禁用。" : "AI 服务尚未配置。";
      log.warn("DeepSeek unavailable: code={} requestId={} storeId={} month={}",
          errorCode, requestId, resolvedStoreId, resolvedMonth);
      properties.markFailure(errorCode);
      return aiFallbackResponse(local, assistantContext, hasFrontContext, message, user,
          errorMsg, errorCode, requestId);
    }

    long deepSeekStartedAt = System.nanoTime();
    try {
      String aiAnswer = sanitizeAnswer(callDeepSeekWithRetry(request, message, dataContext));
      long elapsedMs = elapsedMillis(deepSeekStartedAt);
      String activeModel = properties.getModel();
      log.info("DeepSeek success requestId={} elapsedMs={} model={} storeId={} month={} intent={}",
          requestId, elapsedMs, activeModel, resolvedStoreId, resolvedMonth, assistantContext.intent());
      properties.markSuccess();
      return aiSuccessResponse(localAnswer, aiAnswer, assistantContext, hasFrontContext,
          message, user, activeModel, resolvedMonth, requestId);
    } catch (DeepSeekException ex) {
      long elapsedMs = elapsedMillis(deepSeekStartedAt);
      log.error("DeepSeek error requestId={} code={} elapsedMs={} status={} storeId={} month={}",
          requestId, ex.getCode(), elapsedMs, ex.getHttpStatus(), resolvedStoreId, resolvedMonth);
      properties.markFailure(ex.getCode());
      return aiFallbackResponse(local, assistantContext, hasFrontContext, message, user,
          ex.getUserMessage(), ex.getCode(), requestId);
    }
  }

  private AssistantChatResponse response(
      String answer,
      boolean aiUsed,
      boolean blocked,
      String source,
      boolean hasFrontContext,
      String message,
      AuthUser user
  ) {
    List<String> months = blocked || (source != null && source.contains("role-fallback")) ? List.of() : targetMonths(user, message);
    return new AssistantChatResponse(
        answer,
        aiUsed,
        blocked,
        source,
        assistantDataSource(source, hasFrontContext),
        months.isEmpty() ? "" : months.getFirst(),
        List.of(),
        assistantWarnings(source, hasFrontContext)
    );
  }

  // ========== Mode detection & routing helpers ==========

  private String detectMode(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    boolean isSimpleQuery = lower.contains("多少") || lower.contains("营业额") || lower.contains("净利润")
        || lower.contains("净利率") || lower.contains("营收") || lower.contains("收入") || lower.contains("查询")
        || lower.contains("排名") || lower.contains("亏损") || lower.contains("本月") || lower.contains("上月")
        || lower.contains("这个月") || lower.contains("各月营收") || lower.contains("成本多少") || lower.contains("费用多少");
    boolean isAnalysisQuery = lower.contains("为什么") || lower.contains("原因") || lower.contains("异常")
        || lower.contains("趋势") || lower.contains("对比") || lower.contains("建议") || lower.contains("改善")
        || lower.contains("风险") || lower.contains("怎么办") || lower.contains("分析") || lower.contains("最近三个月")
        || lower.contains("近三个月") || lower.contains("变化") || lower.contains("表现");
    if (isAnalysisQuery && !isSimpleQuery) return "AI";
    if (isSimpleQuery) return "LOCAL";
    return "AI";
  }

  private String buildLocalAnswer(AuthUser user, String message, String frontContext, AssistantContext assistantContext) {
    if (!assistantContext.localAnswer().isBlank()) {
      return assistantContext.localAnswer();
    }
    boolean hasFrontContext = frontContextAllowed(user, frontContext) && !frontContext.isBlank();
    return roleFallbackAnswer(user, message, hasFrontContext);
  }

  private String resolveEffectiveMonth(AuthUser user, String message, String frontContext, AssistantContext assistantContext) {
    if (!assistantContext.resolvedMonth().isBlank()) return assistantContext.resolvedMonth();
    if (!roleOnlyFallback(user)) {
      return targetMonths(user, message, frontContext).stream().findFirst().orElse("");
    }
    return "";
  }

  private String resolveEffectiveStoreId(AuthUser user, AssistantChatRequest request, AssistantContext assistantContext) {
    if (request.storeId() != null && !request.storeId().isBlank()) return request.storeId();
    if (user.storeId() != null && !user.storeId().isBlank()) return user.storeId();
    return assistantContext.resolvedStoreId();
  }

  private record LocalData(String answer, String storeId, String month) {
    private LocalData {
      answer = answer == null ? "" : answer;
      storeId = storeId == null ? "" : storeId;
      month = month == null ? "" : month;
    }
  }

  private AssistantChatResponse localOnlyResponse(LocalData local, AssistantContext assistantContext,
      boolean hasFrontContext, String message, AuthUser user, String requestId) {
    String source = roleOnlyFallback(user)
        ? (hasFrontContext ? "role-fallback-with-frontend-context" : "role-fallback")
        : (hasFrontContext ? "local-frontend-context" : "local-backend-finance");
    return assistantResponseFull(local.answer(), local.answer(), null, false, null, false, false,
        source, hasFrontContext, message, user, assistantContext, local.month(),
        null, false, null, requestId);
  }

  private AssistantChatResponse aiSuccessResponse(String localAnswer, String aiAnswer,
      AssistantContext assistantContext, boolean hasFrontContext, String message, AuthUser user,
      String model, String resolvedMonth, String requestId) {
    String source = hasFrontContext ? "deepseek-frontend-context" : "deepseek-backend-finance";
    return assistantResponseFull(aiAnswer, localAnswer, aiAnswer, true, null, true, false,
        source, hasFrontContext, message, user, assistantContext, resolvedMonth,
        model, false, null, requestId);
  }

  private AssistantChatResponse aiFallbackResponse(LocalData local, AssistantContext assistantContext,
      boolean hasFrontContext, String message, AuthUser user,
      String errorMsg, String errorCode, String requestId) {
    String source = roleOnlyFallback(user)
        ? (hasFrontContext ? "role-fallback-with-frontend-context" : "role-fallback")
        : (hasFrontContext ? "local-frontend-context-fallback" : "local-backend-finance-fallback");
    return assistantResponseFull(local.answer(), local.answer(), null, false,
        errorMsg, false, false, source, hasFrontContext, message, user,
        assistantContext, local.month(), null, true, errorCode, requestId);
  }

  // ========== Business analysis context for AI mode ==========

  private String buildBusinessAnalysisContext(AuthUser user, String message, String frontContext, LocalData local) {
    String extra = frontContextAllowed(user, frontContext) ? clean(frontContext) : "";
    if (!extra.isBlank()) {
      return limitContext(roleContextPrefix(user) + "\n前端当前可见数据上下文：\n" + extra
          + "\n请基于以上数据进行经营分析，给出趋势判断、异常识别和行动建议。不要重复罗列数据。");
    }

    StringBuilder context = new StringBuilder(roleContextPrefix(user)).append("\n");
    String effectiveStoreId = local.storeId();
    String effectiveMonth = local.month();

    List<String> allMonths = dataMonths(user);
    List<String> trend6 = allMonths.stream().sorted().limit(6).toList();
    List<String> trend3 = allMonths.stream().sorted().limit(3).toList();

    // Current month detail
    context.append("=== 当前月份明细 ===\n");
    context.append("月份：").append(effectiveMonth).append("\n");
    ProfitSummaryResponse summary = financeService.dashboard(user, effectiveMonth, null).summary();
    context.append("营业总收入：").append(money(summary.sales())).append("元\n");
    context.append("实收收入：").append(money(summary.income())).append("元\n");
    context.append("成本合计：").append(money(summary.costSum())).append("元\n");
    context.append("费用合计：").append(money(summary.expenseSum())).append("元\n");
    context.append("净利润：").append(money(summary.net())).append("元\n");
    context.append("净利率：").append(percent(summary.margin())).append("\n");
    context.append("门店数：").append(summary.storeCount()).append("，风险门店：").append(summary.riskStoreCount()).append("\n");

    // Cost breakdown
    context.append("\n=== 成本费用结构 ===\n");
    BigDecimal totalRevenue = summary.income();
    if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
      context.append("成本占收入比：").append(percent(ratio(summary.costSum(), totalRevenue))).append("\n");
      context.append("费用占收入比：").append(percent(ratio(summary.expenseSum(), totalRevenue))).append("\n");
    }

    // Previous month comparison
    if (trend6.size() > 1) {
      String prevMonth = trend6.size() > 1 ? trend6.get(trend6.size() - 2) : trend6.get(0);
      if (!prevMonth.equals(effectiveMonth)) {
        ProfitSummaryResponse prevSummary = financeService.dashboard(user, prevMonth, null).summary();
        context.append("\n=== 与上月对比（").append(prevMonth).append("）===\n");
        context.append("上月净利润：").append(money(prevSummary.net())).append("元\n");
        context.append("上月净利率：").append(percent(prevSummary.margin())).append("\n");
        BigDecimal netChange = summary.net().subtract(prevSummary.net());
        context.append("净利润变化：").append(money(netChange)).append("元\n");
      }
    }

    // 3-month and 6-month trends
    context.append("\n=== 近三个月趋势 ===\n");
    for (String month : trend3) {
      ProfitSummaryResponse ms = financeService.dashboard(user, month, null).summary();
      context.append(month).append("：净利润 ").append(money(ms.net()))
          .append("，净利率 ").append(percent(ms.margin())).append("\n");
    }

    if (trend6.size() > 3) {
      context.append("\n=== 近六个月趋势 ===\n");
      for (String month : trend6) {
        ProfitSummaryResponse ms = financeService.dashboard(user, month, null).summary();
        context.append(month).append("：净利润 ").append(money(ms.net()))
            .append("，净利率 ").append(percent(ms.margin())).append("\n");
      }
    }

    // Top/bottom stores
    context.append("\n=== 门店排名（当月净利润）===\n");
    List<ProfitEntryResponse> entries = financeService.entries(user, effectiveMonth, null, null);
    List<ProfitEntryResponse> sorted = entries.stream()
        .sorted(Comparator.comparing(ProfitEntryResponse::net).reversed())
        .limit(10).toList();
    for (int i = 0; i < sorted.size(); i++) {
      ProfitEntryResponse e = sorted.get(i);
      context.append(i + 1).append(". ").append(e.storeName()).append("（").append(e.brandName()).append("）：")
          .append("净利润 ").append(money(e.net())).append("，净利率 ").append(percent(e.margin()))
          .append("，状态 ").append(e.risk()).append("\n");
    }

    // Risk entries
    List<ProfitEntryResponse> risks = entries.stream()
        .filter(e -> !"健康".equals(e.risk()))
        .limit(5).toList();
    if (!risks.isEmpty()) {
      context.append("\n=== 经营异常门店 ===\n");
      for (ProfitEntryResponse e : risks) {
        context.append("- ").append(e.storeName()).append("（").append(e.brandName()).append("）：")
            .append("净利润 ").append(money(e.net())).append("，状态 ").append(e.risk()).append("\n");
      }
    }

    context.append("\n用户问题：").append(message);
    context.append("\n请进行经营分析，不要重复罗列以上所有数据。先给结论，再指出关键发现，最后给出行动建议。");
    return limitContext(context.toString());
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
    return numerator.divide(denominator, 4, java.math.RoundingMode.HALF_UP);
  }

  private AssistantChatResponse assistantResponse(
      String answer,
      String localAnswer,
      String deepSeekAnswer,
      boolean deepSeekAvailable,
      String deepSeekError,
      boolean aiUsed,
      boolean blocked,
      String source,
      boolean hasFrontContext,
      String message,
      AuthUser user,
      AssistantContext assistantContext,
      String resolvedMonth
  ) {
    List<String> months = blocked || (source != null && source.contains("role-fallback"))
        ? List.of()
        : targetMonths(user, message, "");
    String month = !clean(resolvedMonth).isBlank()
        ? clean(resolvedMonth)
        : months.stream().findFirst().orElse("");
    return new AssistantChatResponse(
        answer,
        localAnswer,
        deepSeekAnswer,
        deepSeekAvailable,
        deepSeekError,
        assistantContext.resolvedStoreId(),
        assistantContext.resolvedStoreName(),
        month,
        assistantContext.intent(),
        aiUsed,
        blocked,
        source,
        assistantDataSource(source, hasFrontContext),
        month,
        List.of(),
        assistantWarnings(source, hasFrontContext),
        "",
        !aiUsed,
        null,
        null,
        Instant.now()
    );
  }

  private AssistantChatResponse assistantResponseFull(
      String answer,
      String localAnswer,
      String deepSeekAnswer,
      boolean deepSeekAvailable,
      String deepSeekError,
      boolean aiUsed,
      boolean blocked,
      String source,
      boolean hasFrontContext,
      String message,
      AuthUser user,
      AssistantContext assistantContext,
      String resolvedMonth,
      String model,
      boolean fallback,
      String fallbackReason,
      String requestId
  ) {
    List<String> months = blocked || (source != null && source.contains("role-fallback"))
        ? List.of()
        : targetMonths(user, message, "");
    String month = !clean(resolvedMonth).isBlank()
        ? clean(resolvedMonth)
        : months.stream().findFirst().orElse("");
    String finalModel = model != null && !model.isBlank() ? model : "";
    String finalRequestId = requestId != null && !requestId.isBlank() ? requestId : "";
    return new AssistantChatResponse(
        answer,
        localAnswer,
        deepSeekAnswer,
        deepSeekAvailable,
        deepSeekError,
        assistantContext.resolvedStoreId(),
        assistantContext.resolvedStoreName(),
        month,
        assistantContext.intent(),
        aiUsed,
        blocked,
        source,
        assistantDataSource(source, hasFrontContext),
        month,
        List.of(),
        assistantWarnings(source, hasFrontContext),
        finalModel,
        fallback,
        fallbackReason,
        finalRequestId,
        Instant.now()
    );
  }

  private String assistantDataSource(String source, boolean hasFrontContext) {
    if (source != null && (source.contains("blocked") || source.contains("out-of-scope") || source.contains("role-boundary"))) {
      return "SYSTEM_GUARDRAIL";
    }
    if (source != null && source.contains("role-fallback")) {
      return hasFrontContext ? "FRONTEND_CONTEXT" : "LOCAL_RULES";
    }
    if (source != null && source.contains("deepseek")) {
      return hasFrontContext ? "AI_ENRICHED_FRONTEND_CONTEXT" : "AI_ENRICHED_BACKEND_FINANCE";
    }
    if (source != null && source.contains("frontend-context")) {
      return "FRONTEND_CONTEXT";
    }
    if (source != null && source.contains("backend-finance")) {
      return "BACKEND_FINANCE";
    }
    return hasFrontContext ? "FRONTEND_CONTEXT" : "LOCAL_RULES";
  }

  private String assistantFallbackSource(AuthUser user, boolean hasFrontContext, boolean aiFallback) {
    if (roleOnlyFallback(user)) {
      return hasFrontContext ? "role-fallback-with-frontend-context" : "role-fallback";
    }
    if (aiFallback) {
      return hasFrontContext ? "backend-finance-fallback-with-frontend-context" : "backend-finance-fallback";
    }
    return hasFrontContext ? "backend-finance-fallback-with-frontend-context" : "backend-finance";
  }

  private boolean roleOnlyFallback(AuthUser user) {
    return List.of("WAREHOUSE", "SUPERVISOR", "OPERATIONS").contains(user.role());
  }

  private List<String> assistantWarnings(String source, boolean hasFrontContext) {
    List<String> warnings = new ArrayList<>();
    if (source != null && (source.contains("blocked") || source.contains("out-of-scope") || source.contains("role-boundary"))) {
      warnings.add("已启用系统安全边界，仅回答门店利润系统内的问题。");
      return warnings;
    }
    if (hasFrontContext) {
      warnings.add("回答基于当前页面可见数据。");
    } else {
      warnings.add("回答基于后端财务库和当前用户权限范围。");
    }
    if (source != null && source.contains("fallback")) {
      warnings.add("AI 暂不可用，已使用本地规则或后端财务数据回答。");
    }
    return warnings;
  }

  private boolean frontContextAllowed(AuthUser user, String frontContext) {
    if (!"STORE_MANAGER".equals(user.role())) {
      return true;
    }
    String storeId = user.storeId();
    return storeId != null
        && !storeId.isBlank()
        && containsAny(frontContext, "storeId：" + storeId, "storeId:" + storeId);
  }

  private AssistantContext assistantContext(String message, String frontContext) {
    String localAnswer = contextBlock(frontContext, "本地基础回答");
    return new AssistantContext(
        localAnswer,
        contextLine(frontContext, "resolvedStoreId"),
        contextLine(frontContext, "resolvedStoreName"),
        contextLine(frontContext, "resolvedMonth"),
        contextLine(frontContext, "intent")
    );
  }

  private String contextLine(String context, String label) {
    if (context == null || context.isBlank()) {
      return "";
    }
    Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(label) + "\\s*[:：]\\s*(.*)$");
    Matcher matcher = pattern.matcher(context);
    return matcher.find() ? clean(matcher.group(1)) : "";
  }

  private String contextBlock(String context, String label) {
    if (context == null || context.isBlank()) {
      return "";
    }
    String markerCn = label + "：";
    String markerEn = label + ":";
    int markerIndex = context.indexOf(markerCn);
    int markerLength = markerCn.length();
    if (markerIndex < 0) {
      markerIndex = context.indexOf(markerEn);
      markerLength = markerEn.length();
    }
    if (markerIndex < 0) {
      return "";
    }
    int start = markerIndex + markerLength;
    if (start < context.length() && context.charAt(start) == '\r') {
      start++;
    }
    if (start < context.length() && context.charAt(start) == '\n') {
      start++;
    }
    int end = context.length();
    for (String next : List.of("\n查询数据CSV", "\n各月趋势CSV", "\n请求上下文", "\nDeepSeek上下文")) {
      int index = context.indexOf(next, start);
      if (index >= 0) {
        end = Math.min(end, index);
      }
    }
    return clean(context.substring(start, end));
  }

  private Optional<String> roleBoundaryAnswer(AuthUser user, String message) {
    String role = user.role();
    if ("STORE_MANAGER".equals(role) && containsAny(message, "全部门店", "所有门店", "全门店", "各店", "门店排名", "哪家店", "哪些门店", "其他门店", "全公司")) {
      return Optional.of("本店经营助手只能查看和回答你绑定门店的数据。跨门店排名、全公司汇总和其他门店数据请由老板、财务或运营账号查看。");
    }
    if ("WAREHOUSE".equals(role) && containsAny(message, "利润", "净利", "净利润", "净利率", "营业额", "营收", "工资", "薪资", "报销", "费用异常", "成本异常")) {
      return Optional.of("仓库数据助手只回答库存、叫货、采购入库、配送出库、批次、退货和仓库单据。利润、工资、报销和财务异常请使用老板或财务账号查看。");
    }
    if ("SUPERVISOR".equals(role) && containsAny(message, "采购成本", "入库单价", "供应商成本", "工资明细", "薪资明细")) {
      return Optional.of("巡店数据助手只回答巡店、整改、红线问题和负责门店范围。采购成本、入库单价和工资明细请由对应授权角色查看。");
    }
    return Optional.empty();
  }

  private String roleFallbackAnswer(AuthUser user, String message, boolean hasFrontContext) {
    if ("WAREHOUSE".equals(user.role())) {
      return hasFrontContext
          ? "仓库数据助手已读取当前页面仓库上下文。你可以继续围绕库存不足、叫货待处理、采购入库、配送出库、批次和退货单提问。"
          : "仓库数据助手需要仓库页面上下文才能回答库存和叫货明细。请先进入仓库中心刷新数据后再提问。";
    }
    if ("SUPERVISOR".equals(user.role())) {
      return hasFrontContext
          ? "巡店数据助手已读取当前页面巡店上下文。你可以继续围绕巡店未通过、整改待处理、红线问题和门店得分提问。"
          : "巡店数据助手需要巡店记录上下文才能回答整改和得分明细。请先进入督导巡店页面刷新数据后再提问。";
    }
    if ("OPERATIONS".equals(user.role())) {
      return hasFrontContext
          ? "运营数据助手已读取当前页面上下文。你可以继续围绕数据导入、平台同步、门店配置和经营异常提问。"
          : "运营数据助手可以协助查看数据导入、平台同步、门店配置和经营异常；当前没有更多页面上下文。";
    }
    return localAnswer(user, message, hasFrontContext);
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
    boolean ascending = mentionsLowest(message);
    Map<String, StoreAggregate> aggregates = new LinkedHashMap<>();
    for (String month : months) {
      for (ProfitEntryResponse entry : financeService.entries(user, month, null, null)) {
        StoreAggregate aggregate = aggregates.computeIfAbsent(entry.storeId(), key -> new StoreAggregate(entry));
        aggregate.add(month, amount(entry, metric));
      }
    }
    List<StoreAggregate> rows = aggregates.values().stream()
        .sorted(ascending ? Comparator.comparing(StoreAggregate::value) : Comparator.comparing(StoreAggregate::value).reversed())
        .limit(10)
        .toList();
    if (rows.isEmpty()) {
      return "当前系统暂无这些月份的利润数据。";
    }
    String label = metricLabel(metric);
    StringBuilder answer = new StringBuilder("数据助手已按").append(label).append(ascending ? "从低到高" : "从高到低").append("为你汇总排名");
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
    String extra = frontContextAllowed(user, frontContext) ? clean(frontContext) : "";
    if (!extra.isBlank()) {
      return limitContext(roleContextPrefix(user) + "\n前端当前可见数据上下文（未指定月份时按前端说明的默认月份回答）：\n" + extra);
    }

    StringBuilder context = new StringBuilder(roleContextPrefix(user)).append("\n");
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

  private String roleContextPrefix(AuthUser user) {
    return switch (user.role()) {
      case "BOSS", "ADMIN" -> "当前用户：老板。权限范围：可查看全公司、多门店汇总、异常、排名、趋势和待办。";
      case "FINANCE" -> "当前用户：财务。权限范围：可查看利润、成本、报销和财务报表。";
      case "STORE_MANAGER" -> "当前用户：店长。权限范围：只能查看本人绑定门店"
          + (user.storeId() == null || user.storeId().isBlank() ? "" : "（" + user.storeId() + "）")
          + "，不能回答其他门店或全公司数据。";
      case "WAREHOUSE" -> "当前用户：仓库管理员。权限范围：仓库、库存、叫货、入库、出库、批次、退货和仓库单据；不回答利润、工资、报销。";
      case "SUPERVISOR" -> "当前用户：督导。权限范围：巡店、整改、红线和负责门店；不回答采购成本、入库单价和工资明细。";
      case "OPERATIONS" -> "当前用户：运营。权限范围：数据导入、平台同步、门店配置和经营异常。";
      default -> "当前用户：" + user.role() + "。请按当前用户权限范围回答。";
    };
  }

  private String limitContext(String value) {
    return value.length() > CONTEXT_LIMIT ? value.substring(0, CONTEXT_LIMIT) + "\n...（数据上下文已截断）" : value;
  }

  private String callDeepSeekWithRetry(AssistantChatRequest request, String message, String dataContext) {
    int maxAttempts = 3;
    int attempt = 0;
    Exception lastException = null;
    int lastStatus = 0;

    while (attempt < maxAttempts) {
      attempt++;
      try {
        return callDeepSeekOnce(request, message, dataContext);
      } catch (DeepSeekException ex) {
        lastException = ex;
        lastStatus = ex.getHttpStatus();
        if (!isRetryable(ex.getCode(), lastStatus)) {
          throw ex;
        }
        if (attempt < maxAttempts) {
          long backoffMs = (long) Math.pow(2, attempt) * 500L;
          log.warn("DeepSeek retry {}/{}: code={} status={} backoffMs={} model={}",
              attempt, maxAttempts, ex.getCode(), lastStatus, backoffMs, properties.getModel());
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ex;
          }
        }
      }
    }
    throw lastException != null ? (DeepSeekException) lastException
        : new DeepSeekException("DEEPSEEK_UNAVAILABLE", "AI 服务暂时不可用。", 0);
  }

  private boolean isRetryable(String code, int httpStatus) {
    return "DEEPSEEK_RATE_LIMITED".equals(code)
        || "DEEPSEEK_UNAVAILABLE".equals(code)
        || "DEEPSEEK_TIMEOUT".equals(code);
  }

  private String callDeepSeekOnce(AssistantChatRequest request, String message, String dataContext) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int connectTimeoutMs = Math.toIntExact(Math.min(properties.getConnectTimeout().toMillis(), Integer.MAX_VALUE));
    int readTimeoutMs = Math.toIntExact(Math.min(properties.getTimeout().toMillis(), Integer.MAX_VALUE));
    factory.setConnectTimeout(connectTimeoutMs);
    factory.setReadTimeout(readTimeoutMs);

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

    try {
      Map<String, Object> response = client.post()
          .uri("/chat/completions")
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .header("Authorization", "Bearer " + properties.getApiKey())
          .body(body)
          .retrieve()
          .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
            byte[] bodyBytes = res.getBody().readAllBytes();
            String bodyStr = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            int status = res.getStatusCode().value();
            switch (status) {
              case 401 -> throw new DeepSeekException("DEEPSEEK_AUTH_FAILED",
                  "AI 服务认证失败，请管理员检查配置。", status);
              case 402 -> throw new DeepSeekException("DEEPSEEK_BALANCE_INSUFFICIENT",
                  "AI 账户余额不足。", status);
              case 422 -> throw new DeepSeekException("DEEPSEEK_INVALID_REQUEST",
                  "AI 请求参数错误。", status);
              case 429 -> throw new DeepSeekException("DEEPSEEK_RATE_LIMITED",
                  "请求过于频繁，请稍后再试。", status);
              default -> throw new DeepSeekException("DEEPSEEK_INVALID_REQUEST",
                  "AI 请求错误（HTTP " + status + "）。", status);
            }
          })
          .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
            int status = res.getStatusCode().value();
            throw new DeepSeekException("DEEPSEEK_UNAVAILABLE",
                "AI 服务暂时不可用。", status);
          })
          .body(new ParameterizedTypeReference<>() {});

      String content = extractContent(response);
      if (content == null || content.isBlank()) {
        throw new DeepSeekException("DEEPSEEK_EMPTY_RESPONSE",
            "AI 返回内容为空。", 200);
      }
      return content.trim();
    } catch (DeepSeekException ex) {
      throw ex;
    } catch (ResourceAccessException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof SocketTimeoutException) {
        throw new DeepSeekException("DEEPSEEK_TIMEOUT",
            "AI 服务响应超时。", 0);
      }
      if (cause instanceof ConnectException) {
        throw new DeepSeekException("DEEPSEEK_TIMEOUT",
            "AI 服务连接超时。", 0);
      }
      throw new DeepSeekException("DEEPSEEK_UNAVAILABLE",
          "AI 服务网络连接失败。", 0);
    } catch (RestClientException ex) {
      throw new DeepSeekException("DEEPSEEK_RESPONSE_INVALID",
          "AI 返回格式无法解析。", 0);
    }
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
        你是"门店利润系统"的经营分析助手，专门为老板和店长提供数据驱动的经营洞察和行动建议。
        
        系统范围：利润概览、利润表、门店详情、督导巡店、数据录入、报销、数据导出、门店管理、员工工资、操作日志。
        
        核心职责：
        - 解释经营数据背后的原因，而不是重复罗列数据。
        - 识别趋势、异常和风险。
        - 给出有优先级的、可执行的行动建议。
        
        回答规则：
        1. 不要逐条复述上下文中的数据清单。系统已经展示了这些数据，你只需要分析和解释。
        2. 先给出经营结论（一句话概括当前状况）。
        3. 再指出最多三个最重要的变化或风险，每个必须引用具体数据。
        4. 明确区分三类信息：
           - 【数据事实】标为"数据"
           - 【基于数据的判断】标为"判断"
           - 【需要进一步核实的原因】标为"待核实"
        5. 数据不足时必须明确说明"当前系统暂无该数据"，不得编造。
        6. 行动建议必须包含：建议动作、建议负责人（老板/财务/店长/运营）、建议完成时间、需要观察的指标。
        7. 建议按高、中、低优先级排列，使用数字编号。
        8. 不输出"加强管理""提高效率"等空泛建议。
        9. 不输出违法、色情、暴力、攻击、破解、隐私密钥或账号密码相关内容。
        10. 用中文经营顾问口吻，内容简洁，适合老板和店长直接阅读。
        
        推荐输出结构：
        
        经营结论
        一句话说明当前经营状况。
        
        关键发现
        1. 发现一（数据：XX）
        2. 发现二（数据：XX）
        3. 发现三（数据：XX）
        
        可能原因
        - 【判断】已被数据支持的原因
        - 【待核实】需要进一步核实的原因
        
        行动建议
        【高优先级】
        1. 动作描述 | 负责人：XX | 期限：XX | 观察指标：XX
        【中优先级】
        1. 动作描述 | 负责人：XX | 期限：XX | 观察指标：XX
        
        需要补充的数据
        - 缺少哪些数据会影响判断
        
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
    return targetMonths(user, message, "");
  }

  private List<String> targetMonths(AuthUser user, String message, String frontContext) {
    List<String> availableMonths = dataMonths(user);
    LinkedHashSet<String> months = new LinkedHashSet<>();
    YearMonth defaultMonth = defaultMonth(user, frontContext, availableMonths);
    int year = defaultMonth.getYear();
    String text = clean(message);

    if (text.contains("最近三个月")) {
      months.add(defaultMonth.minusMonths(2).toString());
      months.add(defaultMonth.minusMonths(1).toString());
      months.add(defaultMonth.toString());
    } else if (text.contains("上上月") || text.contains("上上个月")) {
      months.add(defaultMonth.minusMonths(2).toString());
    } else if (text.contains("上月") || text.contains("上个月")) {
      months.add(defaultMonth.minusMonths(1).toString());
    } else if (text.contains("本月") || text.contains("这个月") || text.contains("当前月") || text.contains("当月")) {
      months.add(defaultMonth.toString());
    }

    Matcher fullRange = Pattern.compile("(20\\d{2})[-/.年]\\s*(1[0-2]|0?[1-9])\\s*(?:-|到|至|~)\\s*(20\\d{2})[-/.年]\\s*(1[0-2]|0?[1-9])").matcher(text);
    while (fullRange.find()) {
      YearMonth start = YearMonth.of(Integer.parseInt(fullRange.group(1)), Integer.parseInt(fullRange.group(2)));
      YearMonth end = YearMonth.of(Integer.parseInt(fullRange.group(3)), Integer.parseInt(fullRange.group(4)));
      addMonthRange(months, start, end);
    }

    Matcher range = Pattern.compile("(?:(20\\d{2})\\s*年?)?\\s*(1[0-2]|0?[1-9]|[一二两三四五六七八九十]{1,3})\\s*[-到至~]\\s*(?:(20\\d{2})\\s*年?)?\\s*(1[0-2]|0?[1-9]|[一二两三四五六七八九十]{1,3})\\s*月").matcher(text);
    while (range.find()) {
      int rangeYear = Integer.parseInt(clean(range.group(1)).isBlank() ? (clean(range.group(3)).isBlank() ? String.valueOf(year) : range.group(3)) : range.group(1));
      int start = monthNumber(range.group(2));
      int end = monthNumber(range.group(4));
      if (start > 0 && end > 0) {
        addMonthRange(months, YearMonth.of(rangeYear, start), YearMonth.of(rangeYear, end));
      }
    }

    Matcher full = Pattern.compile("(20\\d{2})\\s*[-/.年]\\s*(1[0-2]|0?[1-9])\\s*月?").matcher(text);
    while (full.find()) {
      months.add(YearMonth.of(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2))).toString());
    }

    Matcher shortMonth = Pattern.compile("(?<!\\d)(1[0-2]|0?[1-9])\\s*月份?").matcher(text);
    while (shortMonth.find()) {
      months.add(YearMonth.of(year, Integer.parseInt(shortMonth.group(1))).toString());
    }

    Matcher chineseMonth = Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*月份?").matcher(text);
    while (chineseMonth.find()) {
      int month = monthNumber(chineseMonth.group(1));
      if (month > 0) {
        months.add(YearMonth.of(year, month).toString());
      }
    }

    if (months.isEmpty() && wantsAllMonths(text)) {
      List<String> dataMonths = availableMonths.stream().filter(month -> !month.isBlank()).sorted().toList();
      if (!dataMonths.isEmpty()) {
        months.addAll(dataMonths);
      }
    }
    if (months.isEmpty()) {
      months.add(defaultMonth.toString());
    }
    return new ArrayList<>(months);
  }

  private YearMonth defaultMonth(AuthUser user, String frontContext, List<String> availableMonths) {
    for (String label : List.of("defaultMonth", "resolvedMonth", "month")) {
      String value = contextLine(frontContext, label);
      if (!value.isBlank()) {
        try {
          return YearMonth.parse(value);
        } catch (Exception ignored) {
          // Ignore malformed front-end context and fall back to backend data months.
        }
      }
    }
    return availableMonths.stream()
        .findFirst()
        .map(YearMonth::parse)
        .orElse(YearMonth.now(BUSINESS_ZONE));
  }

  private void addMonthRange(LinkedHashSet<String> months, YearMonth start, YearMonth end) {
    YearMonth from = start.isBefore(end) ? start : end;
    YearMonth to = start.isBefore(end) ? end : start;
    for (YearMonth current = from; !current.isAfter(to); current = current.plusMonths(1)) {
      months.add(current.toString());
    }
  }

  private int monthNumber(String text) {
    String value = clean(text);
    if (value.matches("\\d+")) {
      int month = Integer.parseInt(value);
      return month >= 1 && month <= 12 ? month : 0;
    }
    Map<String, Integer> values = Map.ofEntries(
        Map.entry("一", 1),
        Map.entry("二", 2),
        Map.entry("两", 2),
        Map.entry("三", 3),
        Map.entry("四", 4),
        Map.entry("五", 5),
        Map.entry("六", 6),
        Map.entry("七", 7),
        Map.entry("八", 8),
        Map.entry("九", 9),
        Map.entry("十", 10),
        Map.entry("十一", 11),
        Map.entry("十二", 12)
    );
    return values.getOrDefault(value, 0);
  }

  private boolean wantsAllMonths(String message) {
    return message.contains("各月")
        || message.contains("每月")
        || message.contains("全部月份")
        || message.contains("所有月份")
        || message.contains("月趋势")
        || message.contains("趋势");
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
    return message.contains("排名")
        || message.contains("排行")
        || message.contains("前")
        || message.contains("最低")
        || message.contains("最高")
        || message.contains("最差")
        || message.contains("最好")
        || message.contains("哪家")
        || message.contains("哪些")
        || message.toLowerCase(Locale.ROOT).contains("top");
  }

  private boolean mentionsLowest(String message) {
    return message.contains("最低") || message.contains("最少") || message.contains("最差") || message.contains("垫底") || message.contains("最亏");
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

  private String compactLogValue(String value) {
    String text = clean(value);
    if (text.isBlank()) {
      return "";
    }
    return text.length() > 2000 ? text.substring(0, 2000) + "...(truncated)" : text;
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private record AssistantContext(
      String localAnswer,
      String resolvedStoreId,
      String resolvedStoreName,
      String resolvedMonth,
      String intent
  ) {
    private AssistantContext {
      localAnswer = localAnswer == null ? "" : localAnswer;
      resolvedStoreId = resolvedStoreId == null ? "" : resolvedStoreId;
      resolvedStoreName = resolvedStoreName == null ? "" : resolvedStoreName;
      resolvedMonth = resolvedMonth == null ? "" : resolvedMonth;
      intent = intent == null ? "" : intent;
    }
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
