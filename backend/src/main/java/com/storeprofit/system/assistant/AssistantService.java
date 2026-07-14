package com.storeprofit.system.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AuthUser;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
  private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
  static final String PROMPT_VERSION = "business-analysis-v3-structured-actions";
  private static final Duration CACHE_TTL = Duration.ofMinutes(5);
  private static final Set<String> ACTION_OWNER_ROLES = Set.of(
      "BOSS", "FINANCE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "OPERATIONS"
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

    ModeSelection selection = selectMode(request.modeOrDefault(), question);
    String effectiveMode = selection.mode();
    String correlationId = UUID.randomUUID().toString().substring(0, 8);
    AssistantDataEngine.Result data = dataEngine.build(user, request, question);

    if ("LOCAL".equals(effectiveMode)) {
      log.info("Assistant LOCAL mode requestId={} storeId={} month={} intent=FACT_QUERY",
          correlationId, data.storeId(), data.month());
      return AssistantChatResponse.localOnly(question, data.localData(), selection.reason());
    }

    if (!properties.isConfigured()) {
      String code = properties.isEnabled() ? "DEEPSEEK_NOT_CONFIGURED" : "DEEPSEEK_DISABLED";
      String message = properties.isEnabled()
          ? "AI分析暂时不可用，请管理员完成服务配置。"
          : "AI分析服务已禁用。";
      properties.markFailure(code);
      log.warn("DeepSeek unavailable: code={} requestId={} storeId={} month={}",
          code, correlationId, data.storeId(), data.month());
      return AssistantChatResponse.aiUnavailable(
          question, data.localData(), selection.reason(), code, message
      );
    }

    String key = cacheKey(user, data, question);
    CachedAnalysis cached = analysisCache.get(key);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return new AssistantChatResponse(
          question, "AI", selection.reason(), data.localData(), cached.analysis(), false, null
      );
    }

    long startedAt = System.nanoTime();
    try {
      String modelQuestion = redactSensitiveForModel(question);
      DeepSeekCallResult modelResponse = deepSeekClient.analyze(
          systemPrompt(data.modelContext()), modelQuestion
      );
      ParsedAnalysis parsed = parseAnalysis(modelResponse, data.limitations());
      QualityResult quality = qualityGate(data.localData(), parsed);

      if (!quality.passed()) {
        log.warn("DeepSeek quality retry requestId={} model={} storeId={} month={} reason={}",
            correlationId, properties.getModel(), data.storeId(), data.month(), quality.code());
        modelResponse = deepSeekClient.analyze(
            repairPrompt(data.modelContext(), quality), modelQuestion
        );
        parsed = parseAnalysis(modelResponse, data.limitations());
        quality = qualityGate(data.localData(), parsed);
        if (!quality.passed()) {
          properties.markFailure("DEEPSEEK_QUALITY_INSUFFICIENT");
          return AssistantChatResponse.aiUnavailable(
              question,
              data.localData(),
              selection.reason(),
              "DEEPSEEK_QUALITY_INSUFFICIENT",
              "AI分析质量不足，请重新分析。"
          );
        }
      }

      AssistantChatResponse.AiAnalysis analysis = parsed.analysis();
      properties.markSuccess();
      putCache(key, analysis);
      log.info("DeepSeek success requestId={} elapsedMs={} model={} storeId={} month={} intent=BUSINESS_ANALYSIS",
          correlationId, analysis.latencyMs(), analysis.model(), data.storeId(), data.month());
      return new AssistantChatResponse(
          question, "AI", selection.reason(), data.localData(), analysis, false, null
      );
    } catch (DeepSeekException ex) {
      long latencyMs = elapsedMillis(startedAt);
      properties.markFailure(ex.getCode());
      log.error("DeepSeek error requestId={} code={} elapsedMs={} status={} storeId={} month={}",
          correlationId, ex.getCode(), latencyMs, ex.getHttpStatus(), data.storeId(), data.month());
      return AssistantChatResponse.aiUnavailable(
          question, data.localData(), selection.reason(), ex.getCode(), ex.getUserMessage()
      );
    }
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
        "多少", "营业额", "净利润", "净利率", "营收", "收入", "查询", "排名", "亏损",
        "本月", "上月", "这个月", "各月营收", "成本多少", "费用多少");
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
    try {
      String json = result.content().trim();
      if (json.startsWith("```") || json.endsWith("```")) {
        return ParsedAnalysis.invalid("SCHEMA_MARKDOWN", "模型输出包含Markdown代码围栏");
      }
      JsonNode root = objectMapper.readTree(json);
      if (!root.isObject()) return ParsedAnalysis.invalid("SCHEMA_ROOT", "模型输出不是JSON对象");

      List<String> findings = requiredTextList(root.path("findings"), 5);
      List<AssistantChatResponse.Risk> risks = riskList(root.path("risks"));
      List<AssistantChatResponse.PossibleCause> causes = causeList(root.path("possibleCauses"));
      List<AssistantChatResponse.Action> actions = actionList(root.path("actions"));
      String confidence = requireConfidence(root.path("confidence"));
      List<String> limitations = new ArrayList<>(requiredTextList(root.path("limitations"), 6));
      if (localLimitations != null) {
        localLimitations.stream().filter(value -> !limitations.contains(value)).forEach(limitations::add);
      }
      AssistantChatResponse.AiAnalysis analysis = new AssistantChatResponse.AiAnalysis(
          true,
          "DeepSeek",
          result.model(),
          result.requestId(),
          result.latencyMs(),
          root.path("summary").asText(""),
          findings,
          risks,
          causes,
          actions,
          confidence,
          limitations
      );
      return new ParsedAnalysis(analysis, "", "");
    } catch (Exception ex) {
      return ParsedAnalysis.invalid("SCHEMA_INVALID", "模型返回格式不符合分析协议");
    }
  }

  private List<String> requiredTextList(JsonNode node, int limit) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("字段必须是数组");
    }
    return textList(node, limit);
  }

  private List<String> textList(JsonNode node, int limit) {
    if (node == null || !node.isArray()) return List.of();
    List<String> values = new ArrayList<>();
    node.forEach(item -> {
      if (values.size() >= limit) return;
      String value = clean(item.asText(""));
      if (!value.isBlank()) values.add(value);
    });
    return List.copyOf(values);
  }

  private List<AssistantChatResponse.Risk> riskList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("risks必须是数组");
    if (node.size() > 3) throw new IllegalArgumentException("risks最多3项");
    List<AssistantChatResponse.Risk> values = new ArrayList<>();
    node.forEach(item -> {
      if (values.size() >= 3 || !item.isObject()) return;
      values.add(new AssistantChatResponse.Risk(
          item.path("title").asText(""),
          item.path("evidence").asText(""),
          requireConfidence(item.path("severity"))
      ));
    });
    return List.copyOf(values);
  }

  private List<AssistantChatResponse.PossibleCause> causeList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("possibleCauses必须是数组");
    List<AssistantChatResponse.PossibleCause> values = new ArrayList<>();
    node.forEach(item -> {
      if (values.size() >= 5 || !item.isObject()) return;
      values.add(new AssistantChatResponse.PossibleCause(
          item.path("cause").asText(""),
          requireConfidence(item.path("confidence")),
          item.path("basis").asText("")
      ));
    });
    return List.copyOf(values);
  }

  private String requireConfidence(JsonNode node) {
    String value = node == null ? "" : clean(node.asText("")).toUpperCase(Locale.ROOT);
    if (!List.of("HIGH", "MEDIUM", "LOW").contains(value)) {
      throw new IllegalArgumentException("可信度必须是HIGH、MEDIUM或LOW");
    }
    return value;
  }

  private List<AssistantChatResponse.Action> actionList(JsonNode node) {
    if (node == null || !node.isArray()) throw new IllegalArgumentException("actions必须是数组");
    if (node.size() != 3) throw new IllegalArgumentException("actions必须恰好3项");
    List<AssistantChatResponse.Action> values = new ArrayList<>();
    node.forEach(item -> {
      if (values.size() >= 3 || !item.isObject()) return;
      values.add(new AssistantChatResponse.Action(
          item.path("action").asText(""),
          item.path("ownerRole").asText(""),
          item.path("deadline").asText(""),
          item.path("expectedImpact").asText(""),
          item.path("verificationMetric").asText("")
      ));
    });
    return List.copyOf(values);
  }

  private QualityResult qualityGate(
      AssistantChatResponse.LocalData localData,
      ParsedAnalysis parsed
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
    if (analysis.findings().isEmpty() || analysis.risks().isEmpty()
        || analysis.possibleCauses().isEmpty() || analysis.actions().size() != 3) {
      return QualityResult.failed("REQUIRED_SECTIONS", "缺少发现、风险、原因或三条行动建议");
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
    if (textSimilarity(localData.summary(), analysis.summary()) >= 0.82d) {
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
    Set<String> allowed = new LinkedHashSet<>();
    localData.metrics().forEach(metric -> allowed.add(normalizeNumber(metric.displayValue())));
    Matcher matcher = Pattern.compile("(?:¥\\s*[-+]?\\d[\\d,.]*|[-+]?\\d+(?:\\.\\d+)?%)")
        .matcher(analysisText);
    while (matcher.find()) {
      if (!allowed.contains(normalizeNumber(matcher.group()))) return false;
    }
    return true;
  }

  private String cacheKey(AuthUser user, AssistantDataEngine.Result data, String question) {
    return String.join("|",
        String.valueOf(user.tenantId()),
        user.role(),
        data.storeId(),
        data.month(),
        clean(question),
        data.dataVersion(),
        data.localData().calculationVersion(),
        properties.getBaseUrl(),
        properties.getModel(),
        String.valueOf(properties.getTemperature()),
        String.valueOf(properties.getMaxTokens()),
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

  private String systemPrompt(String dataContext) {
    return """
        你是门店经营分析引擎。数据库事实已经由本地数据引擎计算，你只负责解释异常、推测原因并提出行动方案。

        必须遵守：
        1. 不复述全部原始数据；第一句直接给经营结论。
        2. risks最多3项，每项必须包含title、evidence和severity；evidence只能引用输入中的数据证据。
        3. possibleCauses必须明确写成待核实的推测，每项包含cause、confidence和basis，禁止当作事实。
        4. actions必须恰好给出3条本周可执行建议，每项包含action、ownerRole、deadline、expectedImpact和verificationMetric。
           ownerRole只能使用正式角色代码：BOSS、FINANCE、STORE_MANAGER、SUPERVISOR、WAREHOUSE、OPERATIONS。
        5. 每项推测的confidence以及整体confidence只能为HIGH、MEDIUM或LOW。
        6. 数据不足时写入limitations，禁止编造不存在的同比、环比、原因、金额或比例。
        7. 金额和比例必须与输入完全一致，禁止重新计算或改写数值。
        8. 禁止输出或索要密码、Token、手机号、员工姓名、员工工资明细、API Key等敏感信息。
        9. 仅输出一个合法JSON对象，不要Markdown代码块，不要额外解释。

        JSON结构：
        {"summary":"经营结论","findings":["关键发现"],"risks":[{"title":"风险标题","evidence":"数据证据","severity":"HIGH|MEDIUM|LOW"}],"possibleCauses":[{"cause":"待核实的可能原因","confidence":"HIGH|MEDIUM|LOW","basis":"推测依据"}],"actions":[{"action":"本周动作","ownerRole":"负责人角色","deadline":"建议期限","expectedImpact":"预期改善","verificationMetric":"验证指标"}],"limitations":["数据限制"],"confidence":"HIGH|MEDIUM|LOW"}

        当前权限过滤后的经营数据：
        """ + (dataContext == null || dataContext.isBlank() ? "暂无可用数据。" : dataContext);
  }

  private String repairPrompt(String dataContext, QualityResult quality) {
    return systemPrompt(dataContext) + "\n\n"
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

  private String normalizeNumber(String value) {
    return clean(value).replace(",", "").replace(" ", "");
  }

  private String clean(String value) {
    return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private record ModeSelection(String mode, String reason) {}

  private record ParsedAnalysis(
      AssistantChatResponse.AiAnalysis analysis,
      String errorCode,
      String errorMessage
  ) {
    private boolean valid() {
      return analysis != null && (errorCode == null || errorCode.isBlank());
    }

    private static ParsedAnalysis invalid(String code, String message) {
      return new ParsedAnalysis(null, code, message);
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
}
