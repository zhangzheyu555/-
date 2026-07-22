package com.storeprofit.system.employeeassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Local-only employee assistant orchestration.
 *
 * <p>It never depends on the operating-data assistant. Before an external provider is considered,
 * it validates and desensitizes the question, applies high-risk handoff rules, then checks only
 * tenant-local approved knowledge. External providers receive only the resulting safe question,
 * an opaque UUID and no more than three approved knowledge excerpts.</p>
 */
@Service
public class EmployeeAssistantService {
  private static final int MAX_MESSAGE_LENGTH = 4_000;
  private static final Pattern ATTACHMENT_REFERENCE = Pattern.compile(
      "(?i)(data:[^\\s]+;base64|file://|https?://|/api/storage/attachments/|\\battachment(?:_|-)?ids?\\b)");
  private static final Pattern FINANCIAL_CONTENT = Pattern.compile(
      "(?i)(营业额|营收|利润|毛利|成本|财务|工资|报销|销售额|收入|支出|\\bgmv\\b|[￥¥]\\s*\\d+|\\d+(?:\\.\\d{1,2})?\\s*(?:万元|元))");
  private static final Pattern MOBILE_NUMBER = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
  private static final Pattern ID_CARD_NUMBER = Pattern.compile("(?<![\\dXx])\\d{17}[\\dXx](?![\\dXx])");
  private static final Pattern BANK_CARD_NUMBER = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
  private static final Pattern EMAIL_ADDRESS = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
  private static final Pattern CUSTOMER_NAME_REFERENCE = Pattern.compile(
      "(?:(?:顾客|客户|收货人|联系人)(?:姓名|名字)?[：:]\\s*[\\p{IsHan}]{2,4}|(?:顾客|客户)(?:叫|名为|是|为)\\s*[\\p{IsHan}]{2,4})");
  private static final Pattern ORDER_OR_ADDRESS_REFERENCE = Pattern.compile(
      "(?i)(?:订单(?:号|编号)?|取餐码|小票号)(?:[：:#\\s]*|(?:是|为)\\s*)[A-Z0-9-]{4,}"
          + "|(?:收货地址|配送地址|家庭住址|详细地址)(?:[：:]|(?:是|为)\\s*)");
  private static final Pattern REFUND_QUESTION = Pattern.compile("(?i)(退款|退货|退钱|退费|退款申请)");
  private static final Pattern CACHE_SENSITIVE_TOPIC = Pattern.compile(
      "(?i)(订单|退款|退货|金额|支付|姓名|电话|手机|地址|小票|附件|身份|银行卡|赔偿|赔付)");
  private static final Pattern UNSAFE_OUTPUT_PRIVACY_REQUEST = Pattern.compile(
      "(?s)(?:(?:请|麻烦|需要|提供|发送|告知|告诉|填写|上传|补充|提交|出示|登记|留下|把|将).{0,20}"
          + "(?:顾客|客户)?.{0,8}(?:姓名|电话|联系电话|手机号|订单号|订单编号|金额|小票|支付记录|支付凭证|付款凭证|地址|附件|身份证|身份信息)"
          + "|(?:姓名|电话|联系电话|手机号|订单号|订单编号|金额|小票|支付记录|支付凭证|付款凭证|地址|附件|身份证|身份信息)"
          + ".{0,16}(?:发来|发过来|传来|传过来|告诉|告知|提供|提交|上传|出示|补充|填写|登记|留下))");
  private static final Duration ANSWER_CACHE_TTL = Duration.ofMinutes(5);
  private static final int ANSWER_CACHE_MAX_ENTRIES = 512;
  private static final Logger LOGGER = Logger.getLogger(EmployeeAssistantService.class.getName());
  private static final List<RiskRule> HUMAN_HANDOFF_RULES = List.of(
      new RiskRule("COMPLAINT_ESCALATION", Pattern.compile("(?i)(投诉.*(?:升级|监管|媒体)|12315|市场监管)")),
      new RiskRule("FOOD_SAFETY", Pattern.compile("(?i)(食品安全|食物中毒|异物|变质|过敏)")),
      new RiskRule("PERSONAL_SAFETY", Pattern.compile("(?i)(人身安全|受伤|烫伤|摔倒|报警|暴力)")),
      new RiskRule("LEGAL", Pattern.compile("(?i)(律师函|起诉|法院|法律责任|违法)")),
      new RiskRule("REFUND_DISPUTE", Pattern.compile("(?i)(退款|退费).*(?:争议|纠纷|投诉)|(?:争议|纠纷).*(?:退款|退费)"))
  );

  private final Duration timeout;
  private final ObjectMapper objectMapper;
  private final AuditRepository auditRepository;
  private final HttpClient httpClient;
  private final EmployeeAssistantProvider provider;
  private final boolean localMode;
  private final LocalEmployeeAssistantResponder localResponder;
  private final EmployeeAssistantKnowledgeRepository knowledgeRepository;
  private final boolean runtimeSecured;
  private final ConcurrentMap<AnswerCacheKey, CachedAnswer> answerCache = new ConcurrentHashMap<>();

  /** Compatibility constructor for Spring proxy creation. */
  EmployeeAssistantService() {
    this("", "", "", "", "", "", Duration.ofSeconds(5), Duration.ofSeconds(15),
        new ObjectMapper(), null, HttpClient.newHttpClient(), null, false);
  }

  @Autowired
  public EmployeeAssistantService(
      @Value("${app.employee-assistant.upstream-url:}") String upstreamUrl,
      @Value("${app.employee-assistant.api-token:}") String apiToken,
      @Value("${app.employee-assistant.connect-timeout:3s}") Duration connectTimeout,
      @Value("${app.employee-assistant.timeout:10s}") Duration timeout,
      @Value("${app.employee-assistant.provider:}") String providerName,
      @Value("${app.employee-assistant.model-url:}") String modelUrl,
      @Value("${app.employee-assistant.model-api-key:}") String modelApiKey,
      @Value("${app.employee-assistant.model-name:}") String modelName,
      @Value("${app.employee-assistant.runtime-secured:false}") boolean runtimeSecured,
      ObjectMapper objectMapper,
      AuditRepository auditRepository,
      EmployeeAssistantKnowledgeRepository knowledgeRepository
  ) {
    this(providerName, upstreamUrl, apiToken, modelUrl, modelApiKey, modelName,
        bounded(connectTimeout, Duration.ofSeconds(2), Duration.ofSeconds(3)),
        bounded(timeout, Duration.ofSeconds(8), Duration.ofSeconds(10)),
        objectMapper, auditRepository, HttpClient.newBuilder()
            .connectTimeout(bounded(connectTimeout, Duration.ofSeconds(2), Duration.ofSeconds(3)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), knowledgeRepository, runtimeSecured);
  }

  EmployeeAssistantService(
      String upstreamUrl,
      String apiToken,
      Duration connectTimeout,
      Duration timeout,
      ObjectMapper objectMapper,
      AuditRepository auditRepository,
      HttpClient httpClient
  ) {
    this("REMOTE", upstreamUrl, apiToken, "", "", "", connectTimeout, timeout, objectMapper,
        auditRepository, httpClient, null, false);
  }

  EmployeeAssistantService(
      String providerName,
      String upstreamUrl,
      String apiToken,
      String modelUrl,
      String modelApiKey,
      String modelName,
      Duration connectTimeout,
      Duration timeout,
      ObjectMapper objectMapper,
      AuditRepository auditRepository,
      HttpClient httpClient
  ) {
    this(providerName, upstreamUrl, apiToken, modelUrl, modelApiKey, modelName, connectTimeout, timeout,
        objectMapper, auditRepository, httpClient, null, false);
  }

  EmployeeAssistantService(
      String providerName,
      String upstreamUrl,
      String apiToken,
      String modelUrl,
      String modelApiKey,
      String modelName,
      Duration connectTimeout,
      Duration timeout,
      ObjectMapper objectMapper,
      AuditRepository auditRepository,
      HttpClient httpClient,
      EmployeeAssistantKnowledgeRepository knowledgeRepository,
      boolean runtimeSecured
  ) {
    this.timeout = nonZero(timeout, Duration.ofSeconds(15));
    this.objectMapper = objectMapper;
    this.auditRepository = auditRepository;
    this.httpClient = httpClient;
    this.knowledgeRepository = knowledgeRepository;
    this.runtimeSecured = runtimeSecured;
    this.localMode = isValidLocalMode(
        providerName, upstreamUrl, apiToken, modelUrl, modelApiKey, modelName);
    this.localResponder = new LocalEmployeeAssistantResponder();
    this.provider = localMode
        ? disabledProvider()
        : providerFor(providerName, upstreamUrl, apiToken, modelUrl, modelApiKey, modelName);
  }

  public EmployeeAssistantStatusResponse health(AuthUser user) {
    String requestId = UUID.randomUUID().toString();
    boolean knowledgeAvailable = hasPublishedKnowledge(user);
    EmployeeAssistantStatusResponse status = status(EmployeeAssistantState.UNAVAILABLE,
        "员工服务助手暂时不可用，请稍后重试", knowledgeAvailable);
    try {
      if (localMode) {
        status = status(EmployeeAssistantState.READY, "员工服务助手已就绪（本地安全话术）", knowledgeAvailable);
        return status;
      }
      if (!provider.configured() || !runtimeSecured) {
        String message;
        if (!runtimeSecured && provider.configured()) {
          message = "员工服务助手未通过安全启动器注入，请联系管理员使用统一安全启动器重启服务";
        } else {
          message = knowledgeAvailable
              ? "上游服务未配置，仍可查询已发布的标准话术"
              : "员工服务助手未配置";
        }
        status = status(EmployeeAssistantState.UNCONFIGURED, message, knowledgeAvailable);
        return status;
      }
      HttpResponse<Void> response = httpClient.send(provider.healthRequest(timeout), HttpResponse.BodyHandlers.discarding());
      if (isSuccess(response.statusCode())) {
        status = status(EmployeeAssistantState.READY, "员工服务助手已就绪", knowledgeAvailable);
        return status;
      }
      status = healthFailure(response.statusCode(), knowledgeAvailable);
      return status;
    } catch (HttpTimeoutException ex) {
      status = status(EmployeeAssistantState.UNAVAILABLE, unavailableMessage("员工服务助手响应超时，请稍后重试", knowledgeAvailable), knowledgeAvailable);
      return status;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      status = status(EmployeeAssistantState.UNAVAILABLE, unavailableMessage("员工服务助手检测已取消，请稍后重试", knowledgeAvailable), knowledgeAvailable);
      return status;
    } catch (IOException | IllegalArgumentException ex) {
      status = status(EmployeeAssistantState.UNAVAILABLE, unavailableMessage("员工服务助手暂时不可用，请稍后重试", knowledgeAvailable), knowledgeAvailable);
      return status;
    } finally {
      if (auditRepository != null) writeHealthAudit(user, requestId, status);
    }
  }

  public EmployeeAssistantChatResponse chat(AuthUser user, EmployeeAssistantChatRequest request) {
    String outcome = "REJECTED";
    boolean inputRedacted = false;
    String requestId = UUID.randomUUID().toString();
    ChatTiming timing = new ChatTiming();
    try {
      SanitizedMessage sanitized = sanitize(request == null ? null : request.message());
      inputRedacted = sanitized.redacted();
      String conversationId = normalizeConversationId(request == null ? null : request.sessionId());
      if (isRefundQuestion(sanitized.value())) {
        outcome = "HUMAN_REQUIRED_REFUND";
        return refundResponse(requestId, conversationId);
      }
      String handoffCategory = mandatoryHandoffCategory(sanitized.value());
      if (handoffCategory != null) {
        outcome = "HUMAN_REQUIRED_" + handoffCategory;
        return response("此问题需要人工处理，请点击“转人工处理”提交给督导。", requestId,
            conversationId, true, EmployeeAssistantAnswerSource.HUMAN_REQUIRED, null, null, null, handoffCategory);
      }

      long knowledgeStarted = System.nanoTime();
      KnowledgeLookup knowledgeLookup = knowledgeLookup(user.tenantId(), sanitized.value());
      timing.knowledgeMillis = elapsedMillis(knowledgeStarted);
      List<KnowledgeCandidate> matches = knowledgeLookup.matches();
      KnowledgeCandidate direct = matches.stream().filter(KnowledgeCandidate::highConfidence).findFirst().orElse(null);
      if (direct != null) {
        outcome = "SUCCESS_KNOWLEDGE";
        EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge = direct.knowledge();
        return controlledAnswer(knowledge.standardAnswer(), requestId, conversationId, false,
            EmployeeAssistantAnswerSource.KNOWLEDGE, knowledge.id(), knowledge.currentVersion(), knowledge.title(), null);
      }

      if (localMode) {
        LocalEmployeeAssistantResponder.LocalAnswer localAnswer = localResponder.answer(sanitized.value());
        outcome = localAnswer.needsHuman() ? "HUMAN_REQUIRED_LOCAL_NO_MATCH" : "SUCCESS_LOCAL";
        return response(localAnswer.answer(), requestId, conversationId, localAnswer.needsHuman(),
            localAnswer.needsHuman() ? EmployeeAssistantAnswerSource.HUMAN_REQUIRED : EmployeeAssistantAnswerSource.ASSISTANT,
            null, null, null, localAnswer.handoffCategory());
      }

      if (!provider.configured()) {
        outcome = "NOT_CONFIGURED";
        throw safeFailure("EMPLOYEE_ASSISTANT_NOT_CONFIGURED", "员工服务助手未配置，且未匹配已发布标准话术，请联系管理员",
            HttpStatus.SERVICE_UNAVAILABLE);
      }
      if (!runtimeSecured) {
        outcome = "NOT_SECURED";
        throw safeFailure("EMPLOYEE_ASSISTANT_NOT_CONFIGURED",
            "员工服务助手未通过安全启动器注入，请联系管理员使用统一安全启动器重启服务",
            HttpStatus.SERVICE_UNAVAILABLE);
      }
      AnswerCacheKey cacheKey = cacheKey(user, sanitized.value(), knowledgeLookup.versionKey(), inputRedacted);
      CachedAnswer cachedAnswer = cacheKey == null ? null : answerCache.get(cacheKey);
      if (cachedAnswer != null && !cachedAnswer.isExpired()) {
        outcome = "SUCCESS_CACHE";
        return response(cachedAnswer.answer(), requestId, conversationId, false,
            EmployeeAssistantAnswerSource.ASSISTANT, null, null, null, null);
      }
      if (cachedAnswer != null) answerCache.remove(cacheKey, cachedAnswer);
      List<EmployeeAssistantKnowledgeSnippet> snippets = matches.stream().limit(3)
          .map(candidate -> snippet(candidate.knowledge()))
          .filter(Objects::nonNull)
          .toList();
      HttpResponse<String> providerResponse;
      long modelStarted = System.nanoTime();
      try {
        providerResponse = httpClient.send(
            provider.chatRequest(sanitized.value(), conversationId, snippets, timeout, objectMapper),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } finally {
        timing.modelMillis = elapsedMillis(modelStarted);
      }
      if (!isSuccess(providerResponse.statusCode())) {
        if (providerResponse.statusCode() == 408 || providerResponse.statusCode() == 504) {
          outcome = "HUMAN_REQUIRED_TIMEOUT";
          return timeoutResponse(requestId, conversationId);
        }
        outcome = statusOutcome(providerResponse.statusCode());
        throw upstreamFailure(providerResponse.statusCode());
      }
      EmployeeAssistantChatResponse result = controlledResponse(providerResponse.body(), requestId, conversationId);
      if (cacheKey != null && !result.needsHuman()) cache(cacheKey, result.answer());
      outcome = "SUCCESS_ASSISTANT";
      return result;
    } catch (BusinessException ex) {
      if ("REJECTED".equals(outcome)) outcome = "REJECTED_" + ex.getCode();
      throw ex;
    } catch (HttpTimeoutException ex) {
      outcome = "HUMAN_REQUIRED_TIMEOUT";
      return timeoutResponse(requestId, request == null ? null : request.sessionId());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      outcome = "CANCELLED";
      throw safeFailure("EMPLOYEE_ASSISTANT_CANCELLED", "员工服务助手请求已取消，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
    } catch (IOException | IllegalArgumentException ex) {
      outcome = "UPSTREAM_UNAVAILABLE";
      throw safeFailure("EMPLOYEE_ASSISTANT_UNAVAILABLE", "员工服务助手暂时不可用，请稍后重试或转人工处理", HttpStatus.BAD_GATEWAY);
    } finally {
      timing.totalMillis = elapsedMillis(timing.startedAt);
      writeAudit(user, requestId, outcome, inputRedacted, timing);
      logMetrics(outcome, timing);
    }
  }

  /** Reused by handoff creation so only the sanitized value can reach persistent storage. */
  String sanitizeForHandoff(String rawMessage) {
    return sanitize(rawMessage).value();
  }

  String handoffCategory(String sanitizedQuestion) {
    String mandatory = mandatoryHandoffCategory(sanitizedQuestion);
    return mandatory == null ? "GENERAL" : mandatory;
  }

  private EmployeeAssistantChatResponse controlledResponse(String body, String requestId, String conversationId) {
    try {
      EmployeeAssistantProvider.Answer result = provider.parseChatResponse(body, objectMapper);
      String answer = result.answer() == null ? "" : result.answer().trim();
      if (answer.isBlank()) {
        throw safeFailure("EMPLOYEE_ASSISTANT_RESPONSE_INVALID", "员工服务助手返回内容无效，请稍后重试或转人工处理", HttpStatus.BAD_GATEWAY);
      }
      if (answer.length() > 8_000) answer = answer.substring(0, 8_000);
      return controlledAnswer(answer, requestId, conversationId, result.needsHuman(), EmployeeAssistantAnswerSource.ASSISTANT,
          null, null, null, result.needsHuman() ? "PROVIDER_ESCALATION" : null);
    } catch (BusinessException ex) {
      throw ex;
    } catch (IOException ex) {
      throw safeFailure("EMPLOYEE_ASSISTANT_RESPONSE_INVALID", "员工服务助手返回内容无效，请稍后重试或转人工处理", HttpStatus.BAD_GATEWAY);
    }
  }

  private EmployeeAssistantChatResponse controlledAnswer(
      String answer,
      String requestId,
      String conversationId,
      boolean needsHuman,
      EmployeeAssistantAnswerSource source,
      Long knowledgeId,
      Integer knowledgeVersion,
      String knowledgeTitle,
      String handoffCategory
  ) {
    if (UNSAFE_OUTPUT_PRIVACY_REQUEST.matcher(answer == null ? "" : answer).find()) {
      return response("可以这样说：\n我先帮您确认合适的处理方式，请稍等。\n\n"
              + "员工怎么处理：\n- 不索要或发送顾客姓名、电话、订单号、金额、小票、支付记录、地址、附件或身份信息。\n"
              + "- 仅在现有业务系统内按门店规则核验。\n"
              + "什么时候转人工：\n- 需要查询具体订单、身份或支付信息，或无法确认处理规则时，转值班负责人。",
          requestId, conversationId, true, EmployeeAssistantAnswerSource.HUMAN_REQUIRED,
          null, null, null, "OUTPUT_SAFETY");
    }
    return response(answer, requestId, conversationId, needsHuman, source, knowledgeId, knowledgeVersion,
        knowledgeTitle, handoffCategory);
  }

  private EmployeeAssistantChatResponse refundResponse(String requestId, String conversationId) {
    return response("可以这样说：\n很抱歉给您带来不便，我会马上按门店规则协助核验并跟进。\n\n"
            + "员工怎么处理：\n- 不在聊天中索要或发送顾客姓名、电话、订单号、金额、小票、支付记录、地址、附件或身份信息。\n"
            + "- 仅在现有业务系统内按门店规则核验，不承诺退款金额或到账时间。\n"
            + "- 不自行判断具体订单是否符合退款条件。\n"
            + "什么时候转人工：\n- 无法确认门店规则、顾客对处理有异议，或需要判断具体订单时，立即转值班负责人。",
        requestId, conversationId, true, EmployeeAssistantAnswerSource.HUMAN_REQUIRED,
        null, null, null, "REFUND_REVIEW");
  }

  private EmployeeAssistantChatResponse timeoutResponse(String requestId, String suppliedConversationId) {
    String conversationId;
    try {
      conversationId = normalizeConversationId(suppliedConversationId);
    } catch (BusinessException ex) {
      conversationId = UUID.randomUUID().toString();
    }
    return response("可以这样说：\n我正在确认合适的处理方式，请您稍等。\n\n"
            + "员工怎么处理：\n- 当前未能及时取得标准话术，不在聊天中补充顾客或订单信息。\n"
            + "什么时候转人工：\n- 立即转值班负责人继续处理。",
        requestId, conversationId, true, EmployeeAssistantAnswerSource.HUMAN_REQUIRED,
        null, null, null, "UPSTREAM_TIMEOUT");
  }

  private EmployeeAssistantChatResponse response(
      String answer,
      String requestId,
      String conversationId,
      boolean needsHuman,
      EmployeeAssistantAnswerSource source,
      Long knowledgeId,
      Integer knowledgeVersion,
      String knowledgeTitle,
      String handoffCategory
  ) {
    return new EmployeeAssistantChatResponse(answer, true, requestId, conversationId, needsHuman, source,
        knowledgeId, knowledgeVersion, knowledgeTitle, handoffCategory);
  }

  private SanitizedMessage sanitize(String rawMessage) {
    String message = rawMessage == null ? "" : rawMessage.trim();
    if (message.isBlank()) throw safeFailure("EMPLOYEE_ASSISTANT_BAD_MESSAGE", "请输入要咨询的问题", HttpStatus.BAD_REQUEST);
    if (message.length() > MAX_MESSAGE_LENGTH) throw safeFailure("EMPLOYEE_ASSISTANT_MESSAGE_TOO_LONG", "问题最多 4000 个字符", HttpStatus.BAD_REQUEST);
    if (ATTACHMENT_REFERENCE.matcher(message).find()) {
      throw safeFailure("EMPLOYEE_ASSISTANT_ATTACHMENT_BLOCKED", "员工服务助手不接收附件，请用不含附件的通用问题咨询", HttpStatus.BAD_REQUEST);
    }
    if (FINANCIAL_CONTENT.matcher(message).find()) {
      throw safeFailure("EMPLOYEE_ASSISTANT_FINANCE_BLOCKED", "员工服务助手不接收经营或财务数据，请改用不含数据的通用问题咨询", HttpStatus.BAD_REQUEST);
    }
    if (CUSTOMER_NAME_REFERENCE.matcher(message).find() || ORDER_OR_ADDRESS_REFERENCE.matcher(message).find()) {
      throw safeFailure("EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED", "员工服务助手不接收顾客姓名、订单或地址信息，请删除隐私内容后再咨询", HttpStatus.BAD_REQUEST);
    }
    String redacted = EMAIL_ADDRESS.matcher(message).replaceAll("***");
    redacted = ID_CARD_NUMBER.matcher(redacted).replaceAll("***");
    redacted = BANK_CARD_NUMBER.matcher(redacted).replaceAll("***");
    redacted = MOBILE_NUMBER.matcher(redacted).replaceAll("***");
    return new SanitizedMessage(redacted, !message.equals(redacted));
  }

  private KnowledgeLookup knowledgeLookup(long tenantId, String sanitizedQuestion) {
    if (knowledgeRepository == null) return new KnowledgeLookup(List.of(), "none");
    String normalizedQuestion = normalizedForMatch(sanitizedQuestion);
    if (normalizedQuestion.isBlank()) return new KnowledgeLookup(List.of(), "none");
    try {
      List<EmployeeAssistantKnowledgeRepository.KnowledgeRow> published = knowledgeRepository.publishedKnowledge(tenantId);
      List<EmployeeAssistantKnowledgeRepository.KnowledgeRow> safeKnowledge = published.stream()
          // Published knowledge predates the outbound safety boundary in some deployments.
          // Treat any unsafe legacy row as unavailable instead of returning or forwarding it.
          .filter(knowledge -> isSafeKnowledgeContent(
              knowledge.category(), knowledge.title(), knowledge.keywords(), knowledge.standardAnswer()))
          .toList();
      String versionKey = stableHash(safeKnowledge.stream()
          .map(knowledge -> knowledge.id() + ":" + knowledge.currentVersion())
          .sorted().reduce("", (left, right) -> left + "|" + right));
      List<KnowledgeCandidate> matches = safeKnowledge.stream()
          .map(knowledge -> score(knowledge, normalizedQuestion))
          .filter(candidate -> candidate.score() > 0)
          .sorted(Comparator.comparingInt(KnowledgeCandidate::score).reversed()
              .thenComparing(candidate -> candidate.knowledge().id()))
          .limit(3)
          .toList();
      return new KnowledgeLookup(matches, versionKey);
    } catch (RuntimeException ex) {
      // Provider availability must not turn a transient knowledge lookup error into a data leak.
      return new KnowledgeLookup(List.of(), "lookup-unavailable");
    }
  }

  private KnowledgeCandidate score(EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge, String question) {
    String title = normalizedForMatch(knowledge.title());
    if (!title.isBlank() && question.contains(title)) return new KnowledgeCandidate(knowledge, 100, true);
    List<String> keywords = java.util.Arrays.stream((knowledge.keywords() == null ? "" : knowledge.keywords())
        .split("[,，;；\\s]+"))
        .map(this::normalizedForMatch).filter(value -> value.length() >= 2).distinct().toList();
    int matched = (int) keywords.stream().filter(question::contains).count();
    int threshold = keywords.size() < 2 ? Integer.MAX_VALUE : (int) Math.ceil(keywords.size() * 0.67d);
    return new KnowledgeCandidate(knowledge, matched, matched >= threshold);
  }

  private EmployeeAssistantKnowledgeSnippet snippet(EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge) {
    if (!isSafeKnowledgeContent(
        knowledge.category(), knowledge.title(), knowledge.keywords(), knowledge.standardAnswer())) {
      return null;
    }
    String answer = knowledge.standardAnswer() == null ? "" : knowledge.standardAnswer().trim();
    if (answer.length() > 800) answer = answer.substring(0, 800);
    return new EmployeeAssistantKnowledgeSnippet(knowledge.id(), knowledge.currentVersion(), knowledge.category(),
        knowledge.title(), answer);
  }

  private String mandatoryHandoffCategory(String sanitizedQuestion) {
    if (sanitizedQuestion == null || sanitizedQuestion.isBlank()) return null;
    return HUMAN_HANDOFF_RULES.stream().filter(rule -> rule.pattern().matcher(sanitizedQuestion).find())
        .map(RiskRule::category).findFirst().orElse(null);
  }

  private boolean hasPublishedKnowledge(AuthUser user) {
    if (knowledgeRepository == null || user == null) return false;
    try {
      return knowledgeRepository.publishedKnowledge(user.tenantId()).stream()
          .anyMatch(knowledge -> isSafeKnowledgeContent(
              knowledge.category(), knowledge.title(), knowledge.keywords(), knowledge.standardAnswer()));
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private String normalizeConversationId(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) return UUID.randomUUID().toString();
    String value = ticketId.trim();
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("non-canonical UUID");
      return parsed.toString();
    } catch (IllegalArgumentException ex) {
      throw safeFailure("EMPLOYEE_ASSISTANT_SESSION_INVALID", "会话标识无效，请刷新页面后重试", HttpStatus.BAD_REQUEST);
    }
  }

  private EmployeeAssistantStatusResponse healthFailure(int statusCode, boolean knowledgeAvailable) {
    if (statusCode == 401 || statusCode == 403) {
      return status(EmployeeAssistantState.AUTH_FAILED,
          unavailableMessage("员工服务助手授权异常，请联系管理员检查服务配置", knowledgeAvailable), knowledgeAvailable);
    }
    return status(EmployeeAssistantState.UNAVAILABLE,
        unavailableMessage("员工服务助手暂时不可用，请稍后重试", knowledgeAvailable), knowledgeAvailable);
  }

  private String unavailableMessage(String upstreamMessage, boolean knowledgeAvailable) {
    return knowledgeAvailable ? upstreamMessage + "；仍可查询已发布的标准话术" : upstreamMessage;
  }

  private BusinessException upstreamFailure(int statusCode) {
    if (statusCode == 401 || statusCode == 403) {
      return safeFailure("EMPLOYEE_ASSISTANT_AUTH_FAILED", "员工服务助手授权异常，请联系管理员检查服务配置", HttpStatus.SERVICE_UNAVAILABLE);
    }
    return safeFailure("EMPLOYEE_ASSISTANT_UPSTREAM_UNAVAILABLE", "员工服务助手暂时不可用，请稍后重试或转人工处理", HttpStatus.BAD_GATEWAY);
  }

  private String statusOutcome(int statusCode) {
    return statusCode == 401 || statusCode == 403 ? "UPSTREAM_AUTH_FAILED" : "UPSTREAM_UNAVAILABLE";
  }

  private AnswerCacheKey cacheKey(AuthUser user, String sanitizedQuestion, String knowledgeVersion,
      boolean inputRedacted) {
    if (user == null || inputRedacted || !isCacheableQuestion(sanitizedQuestion)) return null;
    String storeScope = user.storeId() == null || user.storeId().isBlank() ? "ALL" : user.storeId().trim();
    // The question itself is never retained as a cache key; only a hash of a low-risk normalized question is used.
    return new AnswerCacheKey(user.tenantId(), storeScope, knowledgeVersion,
        stableHash(normalizedForMatch(sanitizedQuestion)));
  }

  private boolean isCacheableQuestion(String question) {
    return question != null
        && !question.isBlank()
        && !isRefundQuestion(question)
        && mandatoryHandoffCategory(question) == null
        && !CACHE_SENSITIVE_TOPIC.matcher(question).find()
        && !UNSAFE_OUTPUT_PRIVACY_REQUEST.matcher(question).find();
  }

  private void cache(AnswerCacheKey key, String answer) {
    if (answerCache.size() >= ANSWER_CACHE_MAX_ENTRIES) answerCache.clear();
    answerCache.put(key, new CachedAnswer(answer, System.nanoTime() + ANSWER_CACHE_TTL.toNanos()));
  }

  private boolean isRefundQuestion(String question) {
    return REFUND_QUESTION.matcher(question == null ? "" : question).find();
  }

  private void writeAudit(AuthUser user, String requestId, String outcome, boolean inputRedacted, ChatTiming timing) {
    if (auditRepository == null) return;
    auditRepository.writeLog(user, new AuditLogRequest("employee_assistant.chat", "employee_assistant", requestId,
        null, null, "result=" + outcome + "; input_redacted=" + inputRedacted
            + "; knowledge_ms=" + timing.knowledgeMillis
            + "; model_ms=" + timing.modelMillis
            + "; total_ms=" + timing.totalMillis, null, null));
  }

  private void logMetrics(String outcome, ChatTiming timing) {
    // Safe structured operational metrics: no question text, credentials, upstream address or user data is logged.
    LOGGER.info(() -> "employee_assistant_metric outcome=" + outcome
        + " knowledge_ms=" + timing.knowledgeMillis
        + " model_ms=" + timing.modelMillis
        + " total_ms=" + timing.totalMillis);
  }

  private void writeHealthAudit(AuthUser user, String requestId, EmployeeAssistantStatusResponse status) {
    auditRepository.writeLog(user, new AuditLogRequest("employee_assistant.health", "employee_assistant", requestId,
        null, null, "state=" + status.state(), null, null));
  }

  private EmployeeAssistantStatusResponse status(EmployeeAssistantState state, String message, boolean knowledgeAvailable) {
    return new EmployeeAssistantStatusResponse(state == EmployeeAssistantState.READY,
        state != EmployeeAssistantState.UNCONFIGURED, state, message, knowledgeAvailable,
        state == EmployeeAssistantState.READY || knowledgeAvailable);
  }

  private String normalizedForMatch(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、,.!?:：；;\\-_/()（）]", "");
  }

  private static boolean isSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  private static Duration nonZero(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private static Duration bounded(Duration value, Duration minimum, Duration maximum) {
    Duration normalized = nonZero(value, maximum);
    if (normalized.compareTo(minimum) < 0) return minimum;
    return normalized.compareTo(maximum) > 0 ? maximum : normalized;
  }

  private static long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private static String stableHash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static BusinessException safeFailure(String code, String message, HttpStatus status) {
    return new BusinessException(code, message, status);
  }

  private static EmployeeAssistantProvider providerFor(String providerName, String upstreamUrl, String apiToken,
      String modelUrl, String modelApiKey, String modelName) {
    String normalized = providerName == null ? "" : providerName.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "REMOTE" -> hasAnyValue(modelUrl, modelApiKey, modelName)
          ? disabledProvider()
          : new RemoteEmployeeAssistantProvider(upstreamUrl, apiToken);
      case "MODEL" -> hasAnyValue(upstreamUrl, apiToken)
          ? disabledProvider()
          : new ModelEmployeeAssistantProvider(modelUrl, modelApiKey, modelName);
      default -> new RemoteEmployeeAssistantProvider("", "");
    };
  }

  private static boolean isValidLocalMode(String providerName, String upstreamUrl, String apiToken,
      String modelUrl, String modelApiKey, String modelName) {
    String normalized = providerName == null ? "" : providerName.trim().toUpperCase(Locale.ROOT);
    return "LOCAL".equals(normalized)
        && !hasAnyValue(upstreamUrl, apiToken, modelUrl, modelApiKey, modelName);
  }

  /**
   * A configuration must select exactly one provider family. Returning an unconfigured provider
   * keeps the browser response and audit record free of configuration details.
   */
  private static EmployeeAssistantProvider disabledProvider() {
    return new RemoteEmployeeAssistantProvider("", "");
  }

  private static boolean hasAnyValue(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return true;
    }
    return false;
  }

  /**
   * Knowledge is sent to a third party only as a fallback aid. Reject unsafe new knowledge and
   * skip legacy unsafe rows before matching so it can neither be returned nor leave this process.
   */
  static void requireSafeKnowledgeContent(String... values) {
    String violation = knowledgeSafetyViolation(values);
    if (violation == null) {
      return;
    }
    switch (violation) {
      case "ATTACHMENT" -> throw safeFailure(
          "EMPLOYEE_ASSISTANT_KNOWLEDGE_ATTACHMENT_BLOCKED",
          "知识库不能包含附件或附件链接，请改用不含附件的通用服务话术",
          HttpStatus.BAD_REQUEST);
      case "FINANCE" -> throw safeFailure(
          "EMPLOYEE_ASSISTANT_KNOWLEDGE_FINANCE_BLOCKED",
          "知识库不能包含经营或财务数据，请删除金额和经营数据后再保存",
          HttpStatus.BAD_REQUEST);
      default -> throw safeFailure(
          "EMPLOYEE_ASSISTANT_KNOWLEDGE_PRIVACY_BLOCKED",
          "知识库不能包含联系方式、顾客隐私、订单或地址信息，请删除敏感内容后再保存",
          HttpStatus.BAD_REQUEST);
    }
  }

  static boolean isSafeKnowledgeContent(String... values) {
    return knowledgeSafetyViolation(values) == null;
  }

  private static String knowledgeSafetyViolation(String... values) {
    StringBuilder content = new StringBuilder();
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          if (!content.isEmpty()) {
            content.append('\n');
          }
          content.append(value);
        }
      }
    }
    String value = content.toString();
    if (value.isBlank()) {
      return null;
    }
    if (ATTACHMENT_REFERENCE.matcher(value).find()) {
      return "ATTACHMENT";
    }
    if (FINANCIAL_CONTENT.matcher(value).find()) {
      return "FINANCE";
    }
    if (MOBILE_NUMBER.matcher(value).find()
        || ID_CARD_NUMBER.matcher(value).find()
        || BANK_CARD_NUMBER.matcher(value).find()
        || EMAIL_ADDRESS.matcher(value).find()
        || CUSTOMER_NAME_REFERENCE.matcher(value).find()
        || ORDER_OR_ADDRESS_REFERENCE.matcher(value).find()
        || UNSAFE_OUTPUT_PRIVACY_REQUEST.matcher(value).find()) {
      return "PRIVACY";
    }
    return null;
  }

  private record SanitizedMessage(String value, boolean redacted) {
  }

  private record RiskRule(String category, Pattern pattern) {
  }

  private record KnowledgeCandidate(EmployeeAssistantKnowledgeRepository.KnowledgeRow knowledge, int score,
                                    boolean highConfidence) {
  }

  private record KnowledgeLookup(List<KnowledgeCandidate> matches, String versionKey) {
  }

  private record AnswerCacheKey(long tenantId, String storeScope, String knowledgeVersion, String questionHash) {
  }

  private record CachedAnswer(String answer, long expiresAtNanos) {
    boolean isExpired() {
      return System.nanoTime() >= expiresAtNanos;
    }
  }

  private static final class ChatTiming {
    private final long startedAt = System.nanoTime();
    private long knowledgeMillis;
    private long modelMillis;
    private long totalMillis;
  }
}
