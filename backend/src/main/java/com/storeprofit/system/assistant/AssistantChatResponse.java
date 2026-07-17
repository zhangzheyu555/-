package com.storeprofit.system.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Trust-oriented assistant protocol. Local database facts and model analysis are deliberately
 * separated so a local fallback can never be rendered as a successful AI response.
 */
public record AssistantChatResponse(
    String question,
    String selectedMode,
    String selectionReason,
    LocalData localData,
    AiAnalysis aiAnalysis,
    boolean fallbackUsed,
    AssistantError error
) {
  public record LocalData(
      String summary,
      List<Metric> metrics,
      String dataPeriod,
      String dataScope,
      String source,
      String dataVersion,
      String calculationVersion,
      Instant updatedAt,
      String snapshotId,
      OperatingSnapshot operatingSnapshot,
      InsufficientData insufficientData,
      String aiInvocation
  ) {
    public LocalData {
      summary = safe(summary);
      metrics = metrics == null ? List.of() : List.copyOf(metrics);
      dataPeriod = safe(dataPeriod);
      dataScope = safe(dataScope);
      source = safe(source);
      dataVersion = safe(dataVersion);
      calculationVersion = safe(calculationVersion);
      updatedAt = updatedAt == null ? Instant.now() : updatedAt;
      snapshotId = safe(snapshotId);
      aiInvocation = safe(aiInvocation).isBlank() ? "NOT_REQUESTED" : safe(aiInvocation).toUpperCase();
    }

    public LocalData(
        String summary,
        List<Metric> metrics,
        String dataPeriod,
        String dataScope,
        String source,
        String dataVersion,
        String calculationVersion,
        Instant updatedAt
    ) {
      this(summary, metrics, dataPeriod, dataScope, source, dataVersion, calculationVersion,
          updatedAt, "", null, null, "NOT_REQUESTED");
    }

    public LocalData(
        String summary,
        List<Metric> metrics,
        String dataPeriod,
        String dataScope,
        String source
    ) {
      this(summary, metrics, dataPeriod, dataScope, source, "", "", null);
    }

    public LocalData withSnapshot(
        OperatingSnapshot snapshot,
        InsufficientData insufficient,
        String invocation
    ) {
      String id = snapshot == null ? snapshotId : snapshot.snapshotId();
      return new LocalData(
          summary, metrics, dataPeriod, dataScope, source, dataVersion, calculationVersion, updatedAt,
          id, snapshot, insufficient, invocation
      );
    }
  }

  /**
   * Deterministic data-insufficiency result. It is returned without invoking a model so callers
   * never have to infer model usage from a routing selection or fallback flag.
   */
  public record InsufficientData(
      String kind,
      List<String> verifiedFacts,
      List<String> cannotDetermine,
      List<String> missingItems,
      List<String> nextSteps,
      boolean modelInvoked
  ) {
    public InsufficientData {
      kind = safe(kind).isBlank() ? "INSUFFICIENT_DATA" : safe(kind).toUpperCase();
      verifiedFacts = immutableStrings(verifiedFacts);
      cannotDetermine = immutableStrings(cannotDetermine);
      missingItems = immutableStrings(missingItems);
      nextSteps = immutableStrings(nextSteps);
    }
  }

  public record Metric(
      String key,
      String label,
      BigDecimal value,
      String unit,
      String displayValue,
      BigDecimal changeRate,
      String comparison
  ) {
    public Metric {
      key = safe(key);
      label = safe(label);
      value = value == null ? BigDecimal.ZERO : value;
      unit = safe(unit);
      displayValue = safe(displayValue);
      comparison = safe(comparison);
    }
  }

  public record AiAnalysis(
      boolean available,
      String provider,
      String model,
      String requestId,
      long latencyMs,
      String analysisType,
      String summary,
      List<String> findings,
      List<Risk> risks,
      List<PossibleCause> possibleCauses,
      List<Action> actions,
      String confidence,
      List<String> limitations
  ) {
    public AiAnalysis {
      provider = available ? safe(provider) : "";
      model = available ? safe(model) : "";
      requestId = available ? safe(requestId) : "";
      latencyMs = available ? Math.max(0, latencyMs) : 0;
      analysisType = available ? normalizeAnalysisType(analysisType) : "";
      summary = available ? safe(summary) : "";
      findings = immutableStrings(findings);
      risks = risks == null ? List.of() : List.copyOf(risks);
      possibleCauses = possibleCauses == null ? List.of() : List.copyOf(possibleCauses);
      actions = actions == null ? List.of() : List.copyOf(actions);
      confidence = available ? normalizeConfidence(confidence) : "";
      limitations = immutableStrings(limitations);
    }

    public static AiAnalysis unavailable() {
      return new AiAnalysis(false, "", "", "", 0, "", "", List.of(), List.of(),
          List.of(), List.of(), "", List.of());
    }

    /**
     * Source-compatible constructor for trusted Java callers. Provider output is never accepted
     * through this overload; AssistantService always supplies the explicitly validated type.
     */
    public AiAnalysis(
        boolean available,
        String provider,
        String model,
        String requestId,
        long latencyMs,
        String summary,
        List<String> findings,
        List<Risk> risks,
        List<PossibleCause> possibleCauses,
        List<Action> actions,
        String confidence,
        List<String> limitations
    ) {
      this(
          available,
          provider,
          model,
          requestId,
          latencyMs,
          available ? "FULL" : "",
          summary,
          findings,
          risks,
          possibleCauses,
          actions,
          confidence,
          limitations
      );
    }
  }

  public record Risk(String title, String evidence, String severity) {
    public Risk {
      title = safe(title);
      evidence = safe(evidence);
      severity = normalizeConfidence(severity);
    }
  }

  public record PossibleCause(String cause, String confidence, String basis) {
    public PossibleCause {
      cause = safe(cause);
      confidence = normalizeConfidence(confidence);
      basis = safe(basis);
    }
  }

  public record Action(
      String action,
      String ownerRole,
      String deadline,
      String expectedImpact,
      String verificationMetric
  ) {
    public Action {
      action = safe(action);
      ownerRole = safe(ownerRole).toUpperCase();
      deadline = safe(deadline);
      expectedImpact = safe(expectedImpact);
      verificationMetric = safe(verificationMetric);
    }
  }

  public record AssistantError(String code, String message) {
    public AssistantError {
      code = safe(code);
      message = safe(message);
    }
  }

  public AssistantChatResponse {
    question = safe(question);
    selectedMode = normalizeMode(selectedMode);
    selectionReason = safe(selectionReason);
    localData = localData == null ? emptyLocalData() : localData;
    aiAnalysis = aiAnalysis == null ? AiAnalysis.unavailable() : aiAnalysis;
  }

  public static AssistantChatResponse localOnly(
      String question,
      LocalData localData,
      String selectionReason
  ) {
    return new AssistantChatResponse(
        question, "LOCAL", selectionReason, localData, AiAnalysis.unavailable(), false, null
    );
  }

  public static AssistantChatResponse localOnly(String question, LocalData localData) {
    return localOnly(question, localData, "用户选择仅查询数据库事实");
  }

  public static AssistantChatResponse aiUnavailable(
      String question,
      LocalData localData,
      String selectionReason,
      String code,
      String message
  ) {
    return new AssistantChatResponse(
        question,
        "AI",
        selectionReason,
        localData,
        AiAnalysis.unavailable(),
        true,
        new AssistantError(code, message)
    );
  }

  public static AssistantChatResponse aiUnavailable(
      String question,
      LocalData localData,
      String code,
      String message
  ) {
    return aiUnavailable(question, localData, "用户选择AI经营分析", code, message);
  }

  private static LocalData emptyLocalData() {
    return new LocalData("", List.of(), "", "", "", "", "", null);
  }

  private static List<String> immutableStrings(List<String> values) {
    return values == null
        ? List.of()
        : values.stream().map(AssistantChatResponse::safe).filter(value -> !value.isBlank()).toList();
  }

  private static String normalizeMode(String value) {
    String normalized = safe(value).toUpperCase();
    return List.of("LOCAL", "AI").contains(normalized) ? normalized : "LOCAL";
  }

  private static String normalizeConfidence(String value) {
    String normalized = safe(value).toUpperCase();
    return List.of("HIGH", "MEDIUM", "LOW").contains(normalized) ? normalized : "MEDIUM";
  }

  private static String normalizeAnalysisType(String value) {
    String normalized = safe(value).toUpperCase();
    return List.of("FULL", "DATA_LIMITED").contains(normalized) ? normalized : "";
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim();
  }

  /*
   * Source-compatibility accessors for existing Java callers while the JSON contract remains the
   * five record components above. They can be removed after downstream Java tests are migrated.
   */
  @Deprecated public String answer() {
    return aiAnalysis.available() ? aiAnalysis.summary() : localData.summary();
  }
  @Deprecated public String localAnswer() { return localData.summary(); }
  @Deprecated public String deepSeekAnswer() { return aiAnalysis.available() ? aiAnalysis.summary() : null; }
  @Deprecated public boolean deepSeekAvailable() { return aiAnalysis.available(); }
  @Deprecated public String deepSeekError() { return error == null ? null : error.message(); }
  @Deprecated public String resolvedStoreId() { return ""; }
  @Deprecated public String resolvedStoreName() { return localData.dataScope(); }
  @Deprecated public String resolvedMonth() { return localData.dataPeriod(); }
  @Deprecated public String intent() { return ""; }
  @Deprecated public boolean aiUsed() { return aiAnalysis.available(); }
  @Deprecated public boolean blocked() {
    return error != null && (error.code().contains("BLOCKED") || error.code().contains("FORBIDDEN")
        || error.code().contains("OUT_OF_SCOPE"));
  }
  @Deprecated public String source() { return localData.source(); }
  @Deprecated public String dataSource() { return localData.source(); }
  @Deprecated public String month() { return localData.dataPeriod(); }
  @Deprecated public List<String> storeScope() { return List.of(); }
  @Deprecated public List<String> warnings() {
    return error == null ? List.of() : List.of(error.message());
  }
  @Deprecated public String model() { return aiAnalysis.model(); }
  @Deprecated public boolean fallback() { return fallbackUsed; }
  @Deprecated public String fallbackReason() { return error == null ? null : error.code(); }
  @Deprecated public String requestId() { return aiAnalysis.requestId(); }
  @Deprecated public Instant generatedAt() { return Instant.now(); }

  /** Compatibility constructor retained for older isolated tests. */
  @Deprecated
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
        "",
        aiUsed ? "AI" : "LOCAL",
        "兼容响应",
        new LocalData(aiUsed ? "" : answer, List.of(), month, String.join(",", storeScope), dataSource),
        aiUsed
            ? new AiAnalysis(true, "DeepSeek", "", "", 0, "FULL", answer, List.of("兼容响应"),
                List.of(), List.of(), List.of(new Action("兼容响应", "", "", "", "")),
                "MEDIUM", List.of())
            : AiAnalysis.unavailable(),
        !aiUsed,
        blocked ? new AssistantError("BLOCKED", warnings == null ? "" : String.join("；", warnings)) : null
    );
  }

  /** Compatibility constructor retained while the service is migrated atomically. */
  @Deprecated
  public AssistantChatResponse(
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
    this(
        "",
        aiUsed ? "AI" : "LOCAL",
        "兼容响应",
        new LocalData(localAnswer, List.of(), resolvedMonth == null ? month : resolvedMonth,
            resolvedStoreName, dataSource),
        deepSeekAvailable && aiUsed && deepSeekAnswer != null
            ? new AiAnalysis(true, "DeepSeek", model, requestId, 0, "FULL", deepSeekAnswer,
                List.of("兼容响应"), List.of(), List.of(),
                List.of(new Action("兼容响应", "", "", "", "")), "MEDIUM", List.of())
            : AiAnalysis.unavailable(),
        fallback,
        deepSeekError == null && !blocked ? null
            : new AssistantError(blocked ? "BLOCKED" : safe(fallbackReason), safe(deepSeekError))
    );
  }
}
