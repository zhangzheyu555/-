package com.storeprofit.system.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
  private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
  static final String PROMPT_VERSION = "business-analysis-v4-strict-json-data-limited";
  private static final Duration CACHE_TTL = Duration.ofMinutes(5);
  private static final Set<String> ACTION_OWNER_ROLES = Set.of(
      "BOSS", "FINANCE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "OPERATIONS"
  );
  private static final Set<String> ANALYSIS_TOP_LEVEL_FIELDS = Set.of(
      "analysisType", "summary", "findings", "risks", "possibleCauses", "actions", "limitations", "confidence"
  );
  private static final List<String> REQUIRED_ANALYSIS_FIELDS = List.of(
      "analysisType", "summary", "findings", "risks", "possibleCauses", "actions", "limitations", "confidence"
  );
  private static final List<String> ANALYSIS_ARRAY_FIELDS = List.of(
      "findings", "risks", "possibleCauses", "actions", "limitations"
  );
  private static final Set<String> ANALYSIS_TYPES = Set.of("FULL", "DATA_LIMITED");
  private static final Map<String, String> COMPATIBLE_ACTION_OWNER_ROLES = Map.of(
      "老板", "BOSS",
      "财务", "FINANCE",
      "店长", "STORE_MANAGER",
      "督导", "SUPERVISOR",
      "仓库管理员", "WAREHOUSE",
      "运营", "OPERATIONS"
  );
  private static final Pattern WHOLE_JSON_FENCE = Pattern.compile(
      "\\A```(?:(?i:json))?[\\t ]*\\R(.*)\\R```\\z", Pattern.DOTALL
  );
  private static final Pattern JSON_SUMMARY_FIELD = Pattern.compile(
      "\"summary\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL
  );
  private static final Pattern NUMERIC_REFERENCE = Pattern.compile(
      "(?<![\\d.])(?:¥\\s*[-+]?\\d[\\d,]*(?:\\.\\d+)?|[-+]?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:元|块)|[-+]?\\d[\\d,]*(?:\\.\\d+)?\\s*%)(?![\\d.])"
  );
  private static final List<String> DEFAULT_BLOCKED_WORDS = List.of(
      "赌博", "博彩", "色情", "黄色", "裸聊", "约炮", "毒品", "枪支", "爆炸", "恐怖",
      "诈骗", "洗钱", "黑客", "攻击", "木马", "病毒", "破解", "脱库", "撞库",
      "身份证号", "银行卡", "api key", "apikey", "access token", "密钥", "令牌"
  );
  private static final List<String> SYSTEM_TERMS = List.of(
      "门店", "经营", "表现", "利润", "利润表", "营业额", "营业收入", "营收", "流水", "实收",
      "净利", "净利润", "毛利", "成本", "费用", "房租", "人工", "水电", "佣金", "工资",
      "员工", "品牌", "排名", "亏损", "月份", "数据", "录入", "导出", "报销", "巡店", "督导",
      "报表", "趋势", "异常", "原因", "建议", "改善", "风险", "仓库", "库存", "叫货", "采购",
      "入库", "出库", "退货", "配送", "预警", "整改", "平台", "同步", "运营"
  );

  private final DeepSeekProperties properties;
  private final AssistantDataEngine dataEngine;
  private final DeepSeekClient deepSeekClient;
  private final ObjectMapper objectMapper;
  private final Map<String, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();
  private final Map<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();

  @Autowired
  public AssistantService(
      DeepSeekProperties properties,
      AssistantDataEngine dataEngine,
      DeepSeekClient deepSeekClient,
      ObjectMapper objectMapper
  ) {
    this.properties = properties;
    this.dataEngine = dataEngine;
    this.deepSeekClient = deepSeekClient;
    this.objectMapper = objectMapper;
  }

  /**
   * Rebuilds a read-only operating snapshot. The identifier remains stable while the source
   * facts and requested scope remain unchanged, which lets the UI truthfully report refresh
   * results without presenting an implementation timestamp as a business change.
   */
  public OperatingSnapshot operatingSnapshot(AuthUser user, String storeId, String month) {
    AssistantChatRequest request = new AssistantChatRequest(
        "查询经营快照", List.of(), "", "LOCAL", storeId, month
    );
    AssistantDataEngine.Result result = dataEngine.build(user, request, request.message());
    OperatingSnapshot snapshot = result.snapshot();
    if (snapshot == null) {
      throw new IllegalStateException("Operating snapshot was not produced by the data engine");
    }
    cacheSnapshot(user, result);
    return snapshot;
  }

  /** Compatibility constructor retained for focused tests. */
  public AssistantService(DeepSeekProperties properties, FinanceService financeService) {
    this(properties, new AssistantDataEngine(financeService),
        new DeepSeekClient(properties, new ObjectMapper()), new ObjectMapper());
  }

  /** Compatibility constructor retained for tests that inject the data engine. */
  public AssistantService(
      DeepSeekProperties properties,
      FinanceService ignoredFinanceService,
      AssistantDataEngine dataEngine
  ) {
    this(properties, dataEngine, new DeepSeekClient(properties, new ObjectMapper()), new ObjectMapper());
  }

  public AssistantChatResponse chat(AuthUser user, AssistantChatRequest request) {
    String question = clean(request.message());
    if (hasBlockedWord(question)) {
      return blockedResponse(
          question,
          "BLOCKED_WORD",
          "这个问题包含系统屏蔽词，我只能协助处理门店经营系统内的业务问题。"
      );
    }
    if (!isInScope(question)) {
      return blockedResponse(
          question,
          "OUT_OF_SCOPE",
          "我只能回答门店经营系统相关问题，例如利润、营收、成本、门店、工资、仓库、巡检和经营建议。"
      );
    }

    Optional<String> boundary = roleBoundaryAnswer(user, question);
    if (boundary.isPresent()) {
      return blockedResponse(question, "FORBIDDEN_SCOPE", boundary.get());
    }
    if (roleOnlyFallback(user)) {
      AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
          roleFallbackAnswer(user),
          List.of(),
          request.month(),
          "当前账号业务范围",
          "后端权限规则"
      );
      return AssistantChatResponse.localOnly(
          question, localData, "当前角色仅使用受权限限制的本地业务查询"
      );
    }

    String requestedMode = request.modeOrDefault();
    ModeSelection selection = selectMode(requestedMode, question);
    String effectiveMode = selection.mode();
    boolean fastProfile = "AUTO".equals(requestedMode);
    String correlationId = UUID.randomUUID().toString().substring(0, 8);
    SnapshotResolution resolution = resolveSnapshot(user, request, question);
    if (resolution.errorResponse() != null) return resolution.errorResponse();
    AssistantDataEngine.Result data = resolution.data();
    AssistantChatResponse.LocalData localData = localDataFor(data, "NOT_REQUESTED", null);

    if ("LOCAL".equals(effectiveMode)) {
      log.info("Assistant local query completed requestId={}", correlationId);
      return AssistantChatResponse.localOnly(question, localData, selection.reason());
    }

    if (!properties.isConfigured()) {
      String code = properties.isEnabled() ? "DEEPSEEK_NOT_CONFIGURED" : "DEEPSEEK_DISABLED";
      String message = properties.isEnabled()
          ? "AI分析暂时不可用，请管理员完成服务配置。"
          : "AI分析服务已禁用。";
      properties.markFailure(code);
      log.warn("DeepSeek unavailable errorCode={} requestId={}", code, correlationId);
      return AssistantChatResponse.aiUnavailable(
          question, localDataFor(data, "NOT_CALLED_UNAVAILABLE", null), selection.reason(), code, message
      );
    }

    String key = cacheKey(user, data, question, fastProfile);
    CachedAnalysis cached = analysisCache.get(key);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return new AssistantChatResponse(
          question, "AI", selection.reason(), localDataFor(data, "CACHE_HIT", null), cached.analysis(), false, null
      );
    }

    long startedAt = System.nanoTime();
    AnalysisExpectation expectation = analysisExpectation(localData);
    try {
      String modelQuestion = redactSensitiveForModel(question);
      DeepSeekCallResult modelResponse = analyzeWithinBudget(
          systemPrompt(data.modelContext(), expectation), modelQuestion, startedAt, fastProfile
      );
      ParsedAnalysis parsed = parseAnalysis(modelResponse, data.limitations());
      logSchemaInvalidDiagnostic(parsed, startedAt);
      QualityResult quality = qualityGate(localData, parsed, expectation);

      if (!quality.passed()) {
        log.warn("DeepSeek quality retry errorCode={} elapsedMs={}",
            quality.code(), elapsedMillis(startedAt));
        // When there are no basic operating metrics to analyze,
        // do NOT make a second model request. Use analysisFailure for user-facing message.
        if (expectation.dataLimited()) {
          AnalysisFailure failure = analysisFailure(quality, expectation);
          properties.markAnalysisResponseRejected(failure.code());
          return AssistantChatResponse.aiUnavailable(
              question, localDataFor(data, "FAILED", null), selection.reason(),
              failure.code(), failure.message()
          );
        }
        modelResponse = analyzeWithinBudget(
            repairPrompt(data.modelContext(), quality, expectation), modelQuestion, startedAt, fastProfile
        );
        parsed = parseAnalysis(modelResponse, data.limitations());
        logSchemaInvalidDiagnostic(parsed, startedAt);
        quality = qualityGate(localData, parsed, expectation);
        if (!quality.passed()) {
          AnalysisFailure failure = analysisFailure(quality, expectation);
          properties.markAnalysisResponseRejected(failure.code());
          log.warn("DeepSeek analysis repair exhausted errorCode={} elapsedMs={}",
              failure.code(), elapsedMillis(startedAt));
          return AssistantChatResponse.aiUnavailable(
              question,
              localDataFor(data, "FAILED", null),
              selection.reason(),
              failure.code(),
              failure.message()
          );
        }
      }

      AssistantChatResponse.AiAnalysis analysis = parsed.analysis();
      properties.markAnalysisReady();
      putCache(key, analysis);
      log.info("DeepSeek analysis succeeded requestId={} elapsedMs={}",
          safeRequestId(analysis.requestId()), elapsedMillis(startedAt));
      return new AssistantChatResponse(
          question, "AI", selection.reason(), localDataFor(data, "LIVE", null), analysis, false, null
      );
    } catch (DeepSeekException ex) {
      long latencyMs = elapsedMillis(startedAt);
      properties.markAnalysisUpstreamError(ex.getCode());
      log.error("DeepSeek analysis failed requestId={} errorCode={} elapsedMs={}",
          correlationId, ex.getCode(), latencyMs);
      return AssistantChatResponse.aiUnavailable(
          question, localDataFor(data, "FAILED", null), selection.reason(), ex.getCode(), ex.getUserMessage()
      );
    }
  }

  private SnapshotResolution resolveSnapshot(AuthUser user, AssistantChatRequest request, String question) {
    String requestedId = request.snapshotId() == null ? "" : request.snapshotId().trim();
    if (requestedId.isBlank()) {
      AssistantDataEngine.Result built = dataEngine.build(user, request, question);
      cacheSnapshot(user, built);
      return SnapshotResolution.success(built);
    }
    CachedSnapshot cached = snapshotCache.get(requestedId);
    if (cached == null || cached.expiresAt().isBefore(Instant.now()) || cached.tenantId() != user.tenantId()
        || cached.userId() != user.id()) {
      snapshotCache.remove(requestedId);
      return SnapshotResolution.failure(snapshotExpiredResponse(question));
    }
    return SnapshotResolution.success(cached.data());
  }

  private void cacheSnapshot(AuthUser user, AssistantDataEngine.Result data) {
    if (data == null || data.snapshot() == null || data.snapshot().snapshotId().isBlank()) return;
    if (snapshotCache.size() >= 128) {
      Instant now = Instant.now();
      snapshotCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
      if (snapshotCache.size() >= 128) snapshotCache.clear();
    }
    snapshotCache.put(data.snapshot().snapshotId(), new CachedSnapshot(
        user.tenantId(), user.id(), data, Instant.now().plus(CACHE_TTL)
    ));
  }

  private AssistantChatResponse.LocalData localDataFor(
      AssistantDataEngine.Result data,
      String invocation,
      AssistantChatResponse.InsufficientData insufficient
  ) {
    AssistantChatResponse.LocalData base = data.localData();
    return base.withSnapshot(data.snapshot(), insufficient, invocation);
  }

  private AssistantChatResponse insufficientDataResponse(
      String question,
      String selectionReason,
      AssistantDataEngine.Result data
  ) {
    OperatingSnapshot snapshot = data.snapshot();
    List<String> facts = new ArrayList<>();
    if (snapshot.capabilities().canComputeKPI()) {
      facts.add("当前范围已有可验证的收入、成本、费用和经营利润汇总。");
    }
    if (snapshot.storeCoverage().reportedStoreCount() > 0) {
      facts.add("已报门店 " + snapshot.storeCoverage().reportedStoreCount()
          + " / " + snapshot.storeCoverage().expectedStoreCount() + " 家。");
    }
    List<String> cannotDetermine = new ArrayList<>();
    if (!snapshot.capabilities().canCompare()) cannotDetermine.add("无法按同门店、同营业日、同天数和同口径进行环比。");
    if (!snapshot.capabilities().canAttributeCause()) cannotDetermine.add("当前数据不足以支持经营原因、趋势或门店结论归因。");
    List<String> nextSteps = new ArrayList<>();
    if (!snapshot.storeCoverage().missingStoreIds().isEmpty()) nextSteps.add("补齐缺失门店的当月经营数据。");
    if (snapshot.missingFields().contains("costOfSalesDetail")
        || snapshot.missingFields().contains("operatingExpenseDetail")) {
      nextSteps.add("核对并补录成本、费用明细后再进行原因分析。");
    }
    if (snapshot.isMTD()) nextSteps.add("待具备同营业日数据后再启用环比和趋势分析。");
    if (nextSteps.isEmpty()) nextSteps.add("补齐可比期或日级经营数据后重新分析。");
    AssistantChatResponse.InsufficientData insufficient = new AssistantChatResponse.InsufficientData(
        "INSUFFICIENT_DATA", facts, cannotDetermine, snapshot.missingFields(), nextSteps, false
    );
    return new AssistantChatResponse(
        question,
        "LOCAL",
        selectionReason + "；当前快照仅返回可验证事实，未调用 AI 模型。",
        localDataFor(data, "NOT_CALLED_INSUFFICIENT", insufficient),
        AssistantChatResponse.AiAnalysis.unavailable(),
        false,
        null
    );
  }

  private AssistantChatResponse snapshotExpiredResponse(String question) {
    AssistantChatResponse.InsufficientData insufficient = new AssistantChatResponse.InsufficientData(
        "INSUFFICIENT_DATA", List.of(), List.of("当前页面快照已过期，不能将新旧数据混在同一回答中。"),
        List.of("snapshotId"), List.of("请重新拉取经营数据后再提问。"), false
    );
    AssistantChatResponse.LocalData localData = new AssistantChatResponse.LocalData(
        "当前经营快照已过期，请重新拉取数据。", List.of(), "", "", "",
        "", "", Instant.now(), "", null, insufficient, "NOT_CALLED_SNAPSHOT_EXPIRED"
    );
    return new AssistantChatResponse(
        question,
        "LOCAL",
        "当前快照已过期，未重新查询或调用 AI。",
        localData,
        AssistantChatResponse.AiAnalysis.unavailable(),
        false,
        new AssistantChatResponse.AssistantError("SNAPSHOT_EXPIRED", "经营数据已更新或快照已过期，请重新拉取。")
    );
  }

  private AssistantChatResponse blockedResponse(String question, String code, String message) {
    return new AssistantChatResponse(
        question,
        "LOCAL",
        "请求被安全或权限规则阻止",
        new AssistantChatResponse.LocalData(message, List.of(), "", "当前账号业务范围", "系统安全规则"),
        AssistantChatResponse.AiAnalysis.unavailable(),
        false,
        new AssistantChatResponse.AssistantError(code, message)
    );
  }

  private ModeSelection selectMode(String requestedMode, String question) {
    if ("LOCAL".equals(requestedMode)) {
      return new ModeSelection("LOCAL", "用户手动选择查数据，仅查询MySQL经营事实");
    }
    if ("AI".equals(requestedMode)) {
      return new ModeSelection("AI", "用户手动选择AI分析，必须调用真实模型");
    }
    String selected = detectMode(question);
    return "LOCAL".equals(selected)
        ? new ModeSelection("LOCAL", "自动模式识别为金额或指标事实查询")
        : new ModeSelection("AI", "自动模式识别为趋势、异常、原因或改善建议分析");
  }

  private String detectMode(String question) {
    String lower = question.toLowerCase(Locale.ROOT);
    boolean analysis = containsAny(lower,
        "为什么", "原因", "异常", "趋势", "对比", "建议", "改善", "风险", "怎么办", "分析",
        "最近三个月", "近三个月", "变化", "表现");
    if (analysis) return "AI";
    boolean fact = containsAny(lower,
        "多少", "金额", "营业额", "销售额", "净利润", "净利率", "利润", "毛利", "营收", "收入", "实收",
        "成本", "费用", "查询", "排名", "亏损", "本月", "上月", "这个月", "各月营收");
    return fact ? "LOCAL" : "AI";
  }

  private Optional<String> roleBoundaryAnswer(AuthUser user, String question) {
    String role = user.role();
    if ("STORE_MANAGER".equals(role) && containsAny(question,
        "全部门店", "所有门店", "全门店", "各店", "门店排名", "哪家店", "哪些门店", "其他门店", "全公司")) {
      return Optional.of("本店经营助手只能查看和回答你绑定门店的数据。跨门店排名和全公司汇总请由授权账号查看。");
    }
    if ("WAREHOUSE".equals(role) && containsAny(question,
        "利润", "净利", "营业额", "营收", "工资", "薪资", "报销", "费用异常", "成本异常")) {
      return Optional.of("仓库数据助手只回答库存、叫货、采购入库、配送出库、批次、退货和仓库单据。");
    }
    if ("SUPERVISOR".equals(role) && containsAny(question,
        "采购成本", "入库单价", "供应商成本", "工资明细", "薪资明细")) {
      return Optional.of("巡店数据助手无权读取采购成本、入库单价和工资明细。");
    }
    return Optional.empty();
  }

  private boolean roleOnlyFallback(AuthUser user) {
    return List.of("WAREHOUSE", "SUPERVISOR", "OPERATIONS").contains(user.role());
  }

  private String roleFallbackAnswer(AuthUser user) {
    return switch (user.role()) {
      case "WAREHOUSE" -> "仓库数据助手需要仓库页面上下文才能回答库存和叫货明细。";
      case "SUPERVISOR" -> "巡店数据助手需要巡店记录上下文才能回答整改和得分明细。";
      case "OPERATIONS" -> "运营数据助手可以协助查看数据导入、平台同步、门店配置和经营异常。";
      default -> "当前账号没有可用的经营分析数据。";
    };
  }

  private ParsedAnalysis parseAnalysis(
      DeepSeekCallResult result,
      List<String> localLimitations
  ) {
    String json = normalizeModelJsonContent(result.content());
    JsonNode root;
    try {
      root = objectMapper.readTree(json);
    } catch (Exception ex) {
      return ParsedAnalysis.valid(textAnalysisFallback(result, localLimitations), 0);
    }
    if (root == null || !root.isObject()) {
      return ParsedAnalysis.valid(textAnalysisFallback(result, localLimitations), 0);
    }
    SchemaDiagnostic diagnostic = SchemaDiagnostic.from(root);

    try {
      rejectUnknownTopLevelFields(root);
      requireAllTopLevelFields(root);
      String analysisType = requireAnalysisType(root.path("analysisType"));
      String summary = requireText(root.path("summary"));
      List<String> findings = requiredTextList(root.path("findings"), 5);
      List<AssistantChatResponse.Risk> risks = riskList(root.path("risks"));
      List<AssistantChatResponse.PossibleCause> causes = causeList(root.path("possibleCauses"));
      List<AssistantChatResponse.Action> actions = actionList(root.path("actions"));
      String confidence = requireConfidence(root.path("confidence"));
      List<String> modelLimitations = requiredTextList(root.path("limitations"), 6);
      List<String> limitations = new ArrayList<>(modelLimitations);
      if (localLimitations != null) {
        localLimitations.stream().filter(value -> !limitations.contains(value)).forEach(limitations::add);
      }
      AssistantChatResponse.AiAnalysis analysis = new AssistantChatResponse.AiAnalysis(
          true,
          "DeepSeek",
          result.model(),
          result.requestId(),
          result.latencyMs(),
          analysisType,
          summary,
          findings,
          risks,
          causes,
          actions,
          confidence,
          limitations
      );
      return ParsedAnalysis.valid(analysis, modelLimitations.size());
    } catch (InvalidActionOwnerRoleException ex) {
      return ParsedAnalysis.invalidActionOwnerRole(diagnostic);
    } catch (Exception ex) {
      return ParsedAnalysis.invalidSchema(diagnostic);
    }
  }

  /**
   * Removes only a leading BOM and an all-content single JSON fence. It never tries to discover a
   * JSON fragment embedded in prose, which would allow an untrusted answer to become a result.
   */
  private String normalizeModelJsonContent(String value) {
    String normalized = value == null ? "" : value;
    int index = 0;
    while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) index++;
    while (index < normalized.length() && normalized.charAt(index) == '\uFEFF') index++;
    normalized = normalized.substring(index).trim();
    Matcher fence = WHOLE_JSON_FENCE.matcher(normalized);
    return fence.matches() ? fence.group(1).trim() : normalized;
  }

  private AssistantChatResponse.AiAnalysis textAnalysisFallback(
      DeepSeekCallResult result,
      List<String> localLimitations
  ) {
    String content = cleanModelText(result.content());
    String summary = bestEffortSummaryText(content);
    summary = summary.isBlank()
        ? "\u0044\u0065\u0065\u0070\u0053\u0065\u0065\u006b\u5df2\u8fd4\u56de\u5206\u6790\uff0c\u4f46\u672a\u4ea7\u751f\u53ef\u5c55\u793a\u6587\u672c\u3002"
        : summary;
    List<String> limitations = new ArrayList<>();
    if (localLimitations != null) {
      localLimitations.stream()
          .map(this::clean)
          .filter(value -> !value.isBlank())
          .filter(value -> !limitations.contains(value))
          .forEach(limitations::add);
    }
    limitations.add("\u6a21\u578b\u672a\u8fd4\u56de\u6807\u51c6\u7ed3\u6784\u5316\u004a\u0053\u004f\u004e\uff0c\u672c\u6b21\u6309\u539f\u59cb\u6587\u672c\u7ed3\u8bba\u5c55\u793a\u3002");
    return new AssistantChatResponse.AiAnalysis(
        true,
        "DeepSeek",
        result.model(),
        result.requestId(),
        result.latencyMs(),
        "FULL",
        summary,
        firstTextLines(summary, 5),
        List.of(),
        List.of(),
        List.of(),
        "MEDIUM",
        limitations
    );
  }

  private String cleanModelText(String value) {
    String text = clean(value);
    if (text.isBlank()) return "";
    text = WHOLE_JSON_FENCE.matcher(text).matches() ? normalizeModelJsonContent(text) : text;
    return text.length() <= 2000 ? text : text.substring(0, 2000);
  }

  private String bestEffortSummaryText(String value) {
    String text = clean(value);
    if (!text.stripLeading().startsWith("{")) return text;
    Matcher matcher = JSON_SUMMARY_FIELD.matcher(text);
    if (!matcher.find()) return text;
    return decodeJsonString(matcher.toMatchResult()).orElse(text);
  }

  private Optional<String> decodeJsonString(MatchResult match) {
    try {
      String decoded = objectMapper.readValue("\"" + match.group(1) + "\"", String.class);
      return Optional.of(clean(decoded));
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  private List<String> firstTextLines(String value, int limit) {
    List<String> lines = new ArrayList<>();
    for (String raw : value.split("\\R+")) {
      String line = clean(raw)
          .replaceFirst("^[-*\\d.\\s]+", "")
          .trim();
      if (!line.isBlank()) lines.add(line);
      if (lines.size() >= limit) break;
    }
    return lines.isEmpty() ? List.of(value) : List.copyOf(lines);
  }

  private boolean isTextAnalysisFallback(AssistantChatResponse.AiAnalysis analysis) {
    return analysis.limitations().stream()
        .anyMatch(value -> value.contains("\u539f\u59cb\u6587\u672c\u7ed3\u8bba"));
  }

  private void rejectUnknownTopLevelFields(JsonNode root) {
    java.util.Iterator<String> fields = root.fieldNames();
    while (fields.hasNext()) {
      if (!ANALYSIS_TOP_LEVEL_FIELDS.contains(fields.next())) {
        throw new IllegalArgumentException("存在未定义的顶层字段");
      }
    }
  }

  private void requireAllTopLevelFields(JsonNode root) {
    for (String field : REQUIRED_ANALYSIS_FIELDS) {
      if (!root.has(field) || root.get(field).isNull()) {
        throw new IllegalArgumentException("缺少必填字段");
      }
    }
  }

  private String requireAnalysisType(JsonNode node) {
    String value = requireText(node);
    if (!ANALYSIS_TYPES.contains(value)) {
      throw new IllegalArgumentException("analysisType不合法");
    }
    return value;
  }

  private String requireText(JsonNode node) {
    if (node == null || !node.isTextual()) {
      throw new IllegalArgumentException("字段必须是字符串");
    }
    return clean(node.textValue());
  }

  private DeepSeekCallResult analyzeWithinBudget(
      String systemPrompt,
      String modelQuestion,
      long startedAt,
      boolean fastProfile
  ) {
    Duration remaining = remainingAnalysisBudget(startedAt);
    return fastProfile
        ? deepSeekClient.analyzeFast(systemPrompt, modelQuestion, remaining)
        : deepSeekClient.analyze(systemPrompt, modelQuestion, remaining);
  }

  private Duration remainingAnalysisBudget(long startedAt) {
    Duration configured = properties.getAnalysisTimeout();
    long configuredNanos;
    try {
      configuredNanos = configured.toNanos();
    } catch (ArithmeticException ex) {
      return Duration.ofSeconds(30);
    }
    long elapsedNanos = System.nanoTime() - startedAt;
    return elapsedNanos >= configuredNanos
        ? Duration.ZERO
        : Duration.ofNanos(configuredNanos - elapsedNanos);
  }

  private void logSchemaInvalidDiagnostic(ParsedAnalysis parsed, long startedAt) {
    if (!"SCHEMA_INVALID".equals(parsed.errorCode())) return;
    SchemaDiagnostic diagnostic = parsed.schemaDiagnostic();
    log.warn(
        "DeepSeek schema diagnostic errorCode={} elapsedMs={} isJson={} topLevelFields={} missingFields={} arrayCounts={}",
        parsed.errorCode(),
        elapsedMillis(startedAt),
        diagnostic.isJson(),
        diagnostic.topLevelFields(),
        diagnostic.missingFields(),
        diagnostic.arrayCounts()
    );
  }

  private List<String> requiredTextList(JsonNode node, int limit) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("字段必须是数组");
    }
    List<String> values = new ArrayList<>();
    node.forEach(item -> {
      if (!item.isTextual()) throw new IllegalArgumentException("数组项必须是字符串");
      if (values.size() >= limit) throw new IllegalArgumentException("数组项超出数量上限");
      String value = clean(item.textValue());
      if (!value.isBlank()) values.add(value);
    });
    return List.copyOf(values);
  }

  private List<AssistantChatResponse.Risk> riskList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("risks必须是数组");
    if (node.size() > 3) throw new IllegalArgumentException("risks最多3项");
    List<AssistantChatResponse.Risk> values = new ArrayList<>();
    node.forEach(item -> {
      if (!item.isObject()) throw new IllegalArgumentException("risk项必须是对象");
      values.add(new AssistantChatResponse.Risk(
          requireTextField(item, "title"),
          requireTextField(item, "evidence"),
          requireConfidence(item.path("severity"))
      ));
    });
    return List.copyOf(values);
  }

  private List<AssistantChatResponse.PossibleCause> causeList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("possibleCauses必须是数组");
    if (node.size() > 5) throw new IllegalArgumentException("possibleCauses最多5项");
    List<AssistantChatResponse.PossibleCause> values = new ArrayList<>();
    node.forEach(item -> {
      if (!item.isObject()) throw new IllegalArgumentException("possibleCause项必须是对象");
      values.add(new AssistantChatResponse.PossibleCause(
          requireTextField(item, "cause"),
          requireConfidence(item.path("confidence")),
          requireTextField(item, "basis")
      ));
    });
    return List.copyOf(values);
  }

  private String requireConfidence(JsonNode node) {
    String value = requireText(node);
    if (!List.of("HIGH", "MEDIUM", "LOW").contains(value)) {
      throw new IllegalArgumentException("可信度必须是HIGH、MEDIUM或LOW");
    }
    return value;
  }

  private List<AssistantChatResponse.Action> actionList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("actions必须是数组");
    if (node.size() > 3) throw new IllegalArgumentException("actions最多3项");
    List<AssistantChatResponse.Action> values = new ArrayList<>();
    node.forEach(item -> {
      if (!item.isObject()) throw new IllegalArgumentException("action项必须是对象");
      values.add(new AssistantChatResponse.Action(
          requireTextField(item, "action"),
          normalizeActionOwnerRole(requireTextField(item, "ownerRole")),
          requireTextField(item, "deadline"),
          requireTextField(item, "expectedImpact"),
          requireTextField(item, "verificationMetric")
      ));
    });
    return List.copyOf(values);
  }

  private String requireTextField(JsonNode object, String field) {
    if (object == null || !object.isObject() || !object.has(field)) {
      throw new IllegalArgumentException("对象字段缺失");
    }
    return requireText(object.get(field));
  }

  private String normalizeActionOwnerRole(String value) {
    if (ACTION_OWNER_ROLES.contains(value)) return value;
    String compatible = COMPATIBLE_ACTION_OWNER_ROLES.get(value);
    if (compatible != null) return compatible;
    throw new InvalidActionOwnerRoleException();
  }

  /**
   * Converts internal quality-gate reasons to a small, safe response contract.  We deliberately
   * do not return a model fragment, a prompt, or the raw failed rule: callers only need an
   * actionable category while logs retain the controlled internal code.
   */
  private AnalysisFailure analysisFailure(QualityResult quality, AnalysisExpectation expectation) {
    if ("ANALYSIS_TYPE".equals(quality.code()) && expectation.dataLimited()) {
      return new AnalysisFailure(
          "DATA_LIMITED_REQUIRED",
          "经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据。"
      );
    }
    return switch (quality.code()) {
      case "SCHEMA_INVALID" -> new AnalysisFailure(
          "SCHEMA_INVALID",
          "模型返回格式异常，已自动重试仍未成功，请稍后重试"
      );
      case "ACTION_OWNER_ROLE", "ACTION_OWNER_ROLE_INVALID" -> new AnalysisFailure(
          "ANALYSIS_ACTION_ROLE_INVALID",
          "模型建议的处理角色不符合系统职责范围，系统已拦截该结果，请稍后重新分析。"
      );
      case "UNKNOWN_AMOUNT" -> new AnalysisFailure(
          "ANALYSIS_UNKNOWN_NUMERIC",
          "模型引用了当前经营数据中没有的金额或比例，系统已拦截该结果，请核对数据后重新分析。"
      );
      case "CONTRADICTION" -> new AnalysisFailure(
          "ANALYSIS_SNAPSHOT_CONTRADICTION",
          "模型结论与当前经营数据不一致，系统已拦截该结果，请核对数据后重新分析。"
      );
      case "DATA_LIMITATIONS", "DATA_ACTIONS", "DATA_LIMITED_CAUSES" -> new AnalysisFailure(
          "DATA_LIMITED_REQUIRED",
          "经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据。"
      );
      default -> new AnalysisFailure(
          "ANALYSIS_QUALITY_REJECTED",
          "模型结果未通过必要的完整性校验，未展示不可靠结论，请稍后重新分析。"
      );
    };
  }

  private QualityResult qualityGate(
      AssistantChatResponse.LocalData localData,
      ParsedAnalysis parsed,
      AnalysisExpectation expectation
  ) {
    if (!parsed.valid()) return QualityResult.failed(parsed.errorCode(), parsed.errorMessage());
    AssistantChatResponse.AiAnalysis analysis = parsed.analysis();
    if (analysis == null || !analysis.available() || analysis.summary().isBlank()) {
      return QualityResult.failed("EMPTY_CONTENT", "核心判断为空");
    }
    if (analysis.model().isBlank()) {
      return QualityResult.failed("MODEL_MISSING", "提供商响应未返回实际模型标识");
    }
    if (analysis.requestId().isBlank()) {
      return QualityResult.failed("REQUEST_ID_MISSING", "提供商响应未返回请求编号");
    }
    if (!expectation.analysisType().equals(analysis.analysisType())) {
      return QualityResult.failed("ANALYSIS_TYPE", "当前经营数据要求的分析类型不匹配");
    }
    if (isTextAnalysisFallback(analysis)) {
      return QualityResult.success();
    }
    if (analysis.findings().isEmpty()) {
      return QualityResult.failed("REQUIRED_SECTIONS", "缺少关键发现");
    }
    if ("FULL".equals(analysis.analysisType()) && (analysis.risks().isEmpty()
        || analysis.possibleCauses().isEmpty() || analysis.actions().size() != 3)) {
      return QualityResult.failed("REQUIRED_SECTIONS", "完整分析缺少风险、待核实原因或三条行动建议");
    }
    if ("DATA_LIMITED".equals(analysis.analysisType())) {
      if (parsed.modelLimitationsCount() <= 0 || analysis.limitations().isEmpty()) {
        return QualityResult.failed("DATA_LIMITATIONS", "数据不足分析必须说明数据限制");
      }
      if (analysis.actions().isEmpty() || analysis.actions().size() > 3) {
        return QualityResult.failed("DATA_ACTIONS", "数据不足分析必须给出一至三条补数建议");
      }
      if (!analysis.possibleCauses().isEmpty()) {
        return QualityResult.failed("DATA_LIMITED_CAUSES", "数据不足时不得输出经营原因推测");
      }
      if (analysis.actions().stream().anyMatch(action -> !isDataCompletionAction(action))) {
        return QualityResult.failed("DATA_ACTIONS", "数据不足分析的行动必须用于补全成本、费用或历史数据");
      }
    }
    if (analysis.risks().stream().anyMatch(risk -> risk.title().isBlank()
        || risk.evidence().isBlank())) {
      return QualityResult.failed("RISK_FIELDS", "风险缺少标题或数据证据");
    }
    if (analysis.possibleCauses().stream().anyMatch(cause -> cause.cause().isBlank()
        || cause.basis().isBlank())) {
      return QualityResult.failed("CAUSE_FIELDS", "可能原因缺少推测依据");
    }
    if (analysis.actions().stream().anyMatch(action -> action.action().isBlank()
        || action.ownerRole().isBlank() || action.deadline().isBlank()
        || action.expectedImpact().isBlank() || action.verificationMetric().isBlank())) {
      return QualityResult.failed("ACTION_FIELDS", "行动建议字段不完整");
    }
    if (analysis.actions().stream().anyMatch(action -> !ACTION_OWNER_ROLES.contains(action.ownerRole()))) {
      return QualityResult.failed("ACTION_OWNER_ROLE", "行动负责人不是系统正式角色代码");
    }
    if ("FULL".equals(analysis.analysisType())
        && textSimilarity(localData.summary(), analysis.summary()) >= 0.82d) {
      return QualityResult.failed("LOCAL_COPY", "AI核心判断与本地摘要高度相似");
    }
    String combined = analysisText(analysis);
    if (!referencedNumbersMatchLocalData(localData, combined)) {
      return QualityResult.failed("UNKNOWN_AMOUNT", "AI输出包含经营快照中不存在的金额或比例");
    }
    if (contradictsSnapshot(localData, combined)) {
      return QualityResult.failed("CONTRADICTION", "AI结论与本地经营快照矛盾");
    }
    return QualityResult.success();
  }

  private boolean isDataCompletionAction(AssistantChatResponse.Action action) {
    String text = clean(action.action());
    return containsAny(text, "补全", "录入", "核对", "完善")
        && containsAny(text, "成本", "费用", "历史", "月份", "经营数据", "数据");
  }

  private AnalysisExpectation analysisExpectation(AssistantChatResponse.LocalData localData) {
    boolean hasNoMetrics = localData == null || localData.metrics() == null || localData.metrics().isEmpty();
    boolean hasRevenue = metricValue(localData, "income").isPresent() || metricValue(localData, "sales").isPresent();
    boolean hasProfit = metricValue(localData, "net").isPresent();
    return hasNoMetrics || !hasRevenue || !hasProfit
        ? new AnalysisExpectation("DATA_LIMITED", true)
        : new AnalysisExpectation("FULL", false);
  }

  private Optional<BigDecimal> metricValue(AssistantChatResponse.LocalData localData, String key) {
    if (localData == null || localData.metrics() == null) return Optional.empty();
    return localData.metrics().stream()
        .filter(metric -> key.equals(metric.key()))
        .map(AssistantChatResponse.Metric::value)
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  private double textSimilarity(String left, String right) {
    Set<String> leftPairs = characterPairs(left);
    Set<String> rightPairs = characterPairs(right);
    if (leftPairs.isEmpty() || rightPairs.isEmpty()) return 0d;
    Set<String> intersection = new LinkedHashSet<>(leftPairs);
    intersection.retainAll(rightPairs);
    Set<String> union = new LinkedHashSet<>(leftPairs);
    union.addAll(rightPairs);
    return union.isEmpty() ? 0d : (double) intersection.size() / union.size();
  }

  private String analysisText(AssistantChatResponse.AiAnalysis analysis) {
    List<String> sections = new ArrayList<>();
    sections.add(analysis.summary());
    sections.addAll(analysis.findings());
    analysis.risks().forEach(risk -> {
      sections.add(risk.title());
      sections.add(risk.evidence());
    });
    analysis.possibleCauses().forEach(cause -> {
      sections.add(cause.cause());
      sections.add(cause.basis());
    });
    analysis.actions().forEach(action -> {
      sections.add(action.action());
      sections.add(action.expectedImpact());
      sections.add(action.verificationMetric());
    });
    sections.addAll(analysis.limitations());
    return String.join("\n", sections);
  }

  private boolean contradictsSnapshot(
      AssistantChatResponse.LocalData localData,
      String analysisText
  ) {
    Map<String, AssistantChatResponse.Metric> metrics = new LinkedHashMap<>();
    localData.metrics().forEach(metric -> metrics.put(metric.key(), metric));
    AssistantChatResponse.Metric net = metrics.get("net");
    if (net != null) {
      if (net.value().signum() < 0 && containsAny(analysisText, "净利润为正", "实现盈利", "保持盈利")) {
        return true;
      }
      if (net.value().signum() > 0 && containsAny(analysisText, "净利润为负", "经营亏损", "已经亏损")) {
        return true;
      }
    }
    AssistantChatResponse.Metric mom = metrics.get("momNetChange");
    if (mom != null && mom.value().signum() != 0) {
      if (mom.value().signum() > 0 && containsAny(analysisText, "净利润环比下降", "净利润较上月下降")) return true;
      if (mom.value().signum() < 0 && containsAny(analysisText, "净利润环比上升", "净利润较上月增长")) return true;
    }
    return false;
  }

  private Set<String> characterPairs(String value) {
    String normalized = clean(value).replaceAll("[\\s，。；：、,.%¥元]", "");
    Set<String> pairs = new LinkedHashSet<>();
    for (int index = 0; index + 1 < normalized.length(); index++) {
      pairs.add(normalized.substring(index, index + 2));
    }
    return pairs;
  }

  private boolean referencedNumbersMatchLocalData(
      AssistantChatResponse.LocalData localData,
      String analysisText
  ) {
    Map<String, List<BigDecimal>> allowed = allowedNumericValues(localData);
    Matcher matcher = NUMERIC_REFERENCE.matcher(analysisText == null ? "" : analysisText);
    while (matcher.find()) {
      NumericReference reference = NumericReference.parse(matcher.group());
      if (reference == null) return false;
      boolean matchesLocalValue = allowed.getOrDefault(reference.unit(), List.of()).stream()
          .anyMatch(value -> value.compareTo(reference.value()) == 0);
      if (!matchesLocalValue) return false;
    }
    return true;
  }

  private Map<String, List<BigDecimal>> allowedNumericValues(AssistantChatResponse.LocalData localData) {
    Map<String, List<BigDecimal>> allowed = new LinkedHashMap<>();
    allowed.put("CNY", new ArrayList<>());
    allowed.put("PERCENT", new ArrayList<>());
    if (localData == null || localData.metrics() == null) return allowed;
    localData.metrics().forEach(metric -> {
      if (metric == null || metric.value() == null) return;
      String unit = clean(metric.unit()).toUpperCase(Locale.ROOT);
      if ("CNY".equals(unit)) {
        allowed.get("CNY").add(metric.value());
      } else if ("PERCENT".equals(unit)) {
        allowed.get("PERCENT").add(metric.value().multiply(BigDecimal.valueOf(100)));
      }
    });
    return allowed;
  }

  private String cacheKey(
      AuthUser user,
      AssistantDataEngine.Result data,
      String question,
      boolean fastProfile
  ) {
    OperatingSnapshot snapshot = data.snapshot();
    String environment = System.getProperty("app.env");
    if (environment == null || environment.isBlank()) environment = System.getenv("APP_ENV");
    if (environment == null || environment.isBlank()) environment = "LOCAL";
    String scope = snapshot == null
        ? data.storeId()
        : String.join(",", snapshot.storeScope().storeIds());
    String asOf = snapshot == null ? "" : (snapshot.asOf() == null ? "UNKNOWN" : snapshot.asOf().toString());
    String sourceVersion = snapshot == null ? data.dataVersion() : snapshot.dataSourceVersion();
    return String.join("|",
        environment.trim().toUpperCase(Locale.ROOT),
        String.valueOf(user.tenantId()),
        user.role(),
        scope,
        data.month(),
        asOf,
        snapshot == null ? "" : snapshot.snapshotId(),
        sourceVersion,
        clean(question),
        data.localData().calculationVersion(),
        properties.getBaseUrl(),
        fastProfile ? properties.getFastModel() : properties.getModel(),
        String.valueOf(properties.getTemperature()),
        String.valueOf(fastProfile ? properties.getFastMaxTokens() : properties.getMaxTokens()),
        PROMPT_VERSION
    );
  }

  private void putCache(String key, AssistantChatResponse.AiAnalysis analysis) {
    if (analysisCache.size() >= 128) {
      Instant now = Instant.now();
      analysisCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
      if (analysisCache.size() >= 128) analysisCache.clear();
    }
    analysisCache.put(key, new CachedAnalysis(analysis, Instant.now().plus(CACHE_TTL)));
  }

  private String systemPrompt(String dataContext, AnalysisExpectation expectation) {
    String typeInstruction = expectation.dataLimited()
        ? """
            当前快照缺少收入或利润等基础经营指标，无法做可靠归因。
            必须输出 analysisType=DATA_LIMITED：summary 和 findings 只说明无法完成原因或趋势判断；
            limitations 至少一项；risks 可为空；possibleCauses 必须为空；actions 只能给出1至3条补全基础经营指标的具体动作。
            不得编造经营原因、金额、比例或趋势。
            """
        : """
            当前快照已有基础经营指标，必须直接进行分析。必须输出 analysisType=FULL，并完整提供发现、风险、待核实原因和恰好3条行动建议。
            如果存在当前月未完结、缺少日级覆盖、缺少同比或环比等限制，只能写入 limitations，不能因此拒绝分析。
            """;
    return """
        你是门店经营分析引擎。数据库事实已经由本地数据引擎计算，你只负责解释异常、推测原因并提出行动方案。

        必须遵守：
        1. 不复述全部原始数据；第一句直接给经营结论。
        2. risks最多3项，每项必须包含title、evidence和severity；evidence只能引用输入中的数据证据。
        3. possibleCauses必须明确写成待核实的推测，每项包含cause、confidence和basis，禁止当作事实。
        4. FULL 的 actions必须恰好给出3条本周可执行建议；DATA_LIMITED 的 actions必须给出1至3条数据补全建议。每项包含action、ownerRole、deadline、expectedImpact和verificationMetric。
           ownerRole只能使用正式角色代码：BOSS、FINANCE、STORE_MANAGER、SUPERVISOR、WAREHOUSE、OPERATIONS。
        5. 每项推测的confidence以及整体confidence只能为HIGH、MEDIUM或LOW。
        6. 数据不足时写入limitations，禁止编造不存在的同比、环比、原因、金额或比例。
        7. 金额和比例必须与输入完全一致，禁止重新计算或改写数值。
        8. 禁止输出或索要密码、Token、手机号、员工姓名、员工工资明细、API Key等敏感信息。
        9. 仅输出一个合法JSON对象，不要Markdown代码块，不要额外解释；每个字段类型必须严格符合下方结构。

        JSON结构：

        {"analysisType":"FULL|DATA_LIMITED","summary":"经营结论","findings":["关键发现"],"risks":[{"title":"风险标题","evidence":"数据证据","severity":"HIGH|MEDIUM|LOW"}],"possibleCauses":[{"cause":"待核实的可能原因","confidence":"HIGH|MEDIUM|LOW","basis":"推测依据"}],"actions":[{"action":"本周动作","ownerRole":"负责人角色","deadline":"建议期限","expectedImpact":"预期改善","verificationMetric":"验证指标"}],"limitations":["数据限制"],"confidence":"HIGH|MEDIUM|LOW"}

        当前分析类型要求：
        """ + typeInstruction + "\n当前权限过滤后的经营数据：\n"
        + (dataContext == null || dataContext.isBlank() ? "暂无可用数据。" : dataContext);
  }

  private String repairPrompt(
      String dataContext,
      QualityResult quality,
      AnalysisExpectation expectation
  ) {
    return systemPrompt(dataContext, expectation) + "\n\n"
        + "上次输出未通过质量门禁（" + quality.code() + "：" + quality.message() + "）。"
        + "请重新生成完整JSON；不要解释错误，不要使用Markdown围栏，不要遗漏任何必填字段。";
  }

  private boolean hasBlockedWord(String value) {
    String lower = clean(value).toLowerCase(Locale.ROOT);
    List<String> words = new ArrayList<>(DEFAULT_BLOCKED_WORDS);
    words.addAll(properties.getBlockedWords());
    return words.stream()
        .filter(word -> word != null && !word.isBlank())
        .map(word -> word.toLowerCase(Locale.ROOT))
        .anyMatch(lower::contains);
  }

  private boolean isInScope(String value) {
    String lower = clean(value).toLowerCase(Locale.ROOT);
    if (lower.length() <= 12 && containsAny(lower, "你好", "您好", "你是谁", "帮助", "怎么用", "hi", "hello")) {
      return true;
    }
    return SYSTEM_TERMS.stream().anyMatch(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
  }

  private String redactSensitiveForModel(String value) {
    return clean(value)
        .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[已移除手机号]")
        .replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "[已移除身份证号]")
        .replaceAll("(?i)(api[_ -]?key|access[_ -]?token|session)\\s*[:：=]\\s*\\S+", "$1=[已移除]");
  }

  private boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank() && value.contains(candidate)) return true;
    }
    return false;
  }

  private String clean(String value) {
    return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
  }

  private String safeRequestId(String value) {
    String sanitized = clean(value).replaceAll("[^A-Za-z0-9._:-]", "");
    return sanitized.isBlank() ? "unavailable" : sanitized.substring(0, Math.min(48, sanitized.length()));
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private record ModeSelection(String mode, String reason) {}

  private record ParsedAnalysis(
      AssistantChatResponse.AiAnalysis analysis,
      String errorCode,
      String errorMessage,
      SchemaDiagnostic schemaDiagnostic,
      int modelLimitationsCount
  ) {
    private boolean valid() {
      return analysis != null && (errorCode == null || errorCode.isBlank());
    }

    private boolean schemaInvalid() {
      return "SCHEMA_INVALID".equals(errorCode);
    }

    private static ParsedAnalysis valid(
        AssistantChatResponse.AiAnalysis analysis,
        int modelLimitationsCount
    ) {
      return new ParsedAnalysis(
          analysis,
          "",
          "",
          SchemaDiagnostic.notApplicable(),
          Math.max(0, modelLimitationsCount)
      );
    }

    private static ParsedAnalysis invalidSchema(SchemaDiagnostic schemaDiagnostic) {
      return new ParsedAnalysis(
          null,
          "SCHEMA_INVALID",
          "模型返回格式不符合分析协议",
          schemaDiagnostic,
          0
      );
    }

    private static ParsedAnalysis invalidActionOwnerRole(SchemaDiagnostic schemaDiagnostic) {
      return new ParsedAnalysis(
          null,
          "ACTION_OWNER_ROLE_INVALID",
          "模型给出的行动负责人不是系统正式角色",
          schemaDiagnostic,
          0
      );
    }
  }

  /** Safe schema-only diagnostic. It intentionally contains no provider content or business data. */
  private record SchemaDiagnostic(
      boolean isJson,
      List<String> topLevelFields,
      List<String> missingFields,
      Map<String, Integer> arrayCounts
  ) {
    private SchemaDiagnostic {
      topLevelFields = topLevelFields == null ? List.of() : List.copyOf(topLevelFields);
      missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
      arrayCounts = arrayCounts == null ? Map.of() : Map.copyOf(arrayCounts);
    }

    private static SchemaDiagnostic notApplicable() {
      return new SchemaDiagnostic(false, List.of(), List.of(), Map.of());
    }

    private static SchemaDiagnostic notJson() {
      return new SchemaDiagnostic(false, List.of(), List.of(), Map.of());
    }

    private static SchemaDiagnostic nonObject() {
      return new SchemaDiagnostic(true, List.of(), REQUIRED_ANALYSIS_FIELDS, Map.of());
    }

    private static SchemaDiagnostic from(JsonNode root) {
      List<String> fields = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      Map<String, Integer> counts = new LinkedHashMap<>();
      root.fieldNames().forEachRemaining(field -> fields.add(
          ANALYSIS_TOP_LEVEL_FIELDS.contains(field) ? field : "UNKNOWN"
      ));
      REQUIRED_ANALYSIS_FIELDS.stream()
          .filter(field -> !root.has(field) || root.get(field).isNull())
          .forEach(missing::add);
      ANALYSIS_ARRAY_FIELDS.forEach(field -> {
        JsonNode value = root.get(field);
        if (value != null && value.isArray()) counts.put(field, value.size());
      });
      return new SchemaDiagnostic(true, fields, missing, counts);
    }
  }

  private record AnalysisExpectation(String analysisType, boolean dataLimited) {}

  private record AnalysisFailure(String code, String message) {}

  private static final class InvalidActionOwnerRoleException extends IllegalArgumentException {
    private InvalidActionOwnerRoleException() {
      super("行动负责人不是系统正式角色代码");
    }
  }

  private record NumericReference(String unit, BigDecimal value) {
    private static NumericReference parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      String normalized = raw.replace(",", "").replace(" ", "");
      String unit = normalized.contains("%") ? "PERCENT" : "CNY";
      String numeric = normalized.replace("¥", "").replace("元", "")
          .replace("块", "").replace("%", "");
      try {
        return new NumericReference(unit, new BigDecimal(numeric));
      } catch (NumberFormatException ex) {
        return null;
      }
    }
  }

  private record QualityResult(boolean passed, String code, String message) {
    private static QualityResult success() {
      return new QualityResult(true, "", "");
    }

    private static QualityResult failed(String code, String message) {
      return new QualityResult(false, code, message);
    }
  }

  private record CachedAnalysis(AssistantChatResponse.AiAnalysis analysis, Instant expiresAt) {}

  private record CachedSnapshot(long tenantId, long userId, AssistantDataEngine.Result data, Instant expiresAt) {}

  private record SnapshotResolution(
      AssistantDataEngine.Result data,
      AssistantChatResponse errorResponse
  ) {
    private static SnapshotResolution success(AssistantDataEngine.Result data) {
      return new SnapshotResolution(data, null);
    }

    private static SnapshotResolution failure(AssistantChatResponse response) {
      return new SnapshotResolution(null, response);
    }
  }
}
