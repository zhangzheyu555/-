package com.storeprofit.system.inspection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record InspectionRecordResponse(
    String id,
    String storeId,
    String storeCode,
    String storeName,
    Long brandId,
    String brandName,
    String inspectionDate,
    String inspector,
    String brand,
    @JsonProperty("originalFullScore") BigDecimal fullScore,
    @JsonProperty("originalScore") BigDecimal score,
    @JsonProperty("originalPassed") boolean passed,
    String deductionsJson,
    String redlinesJson,
    String photosJson,
    String note,
    Long standardVersionId,
    String standardVersion,
    @JsonProperty("originalMaterialScore") BigDecimal materialScore,
    @JsonProperty("originalHygieneScore") BigDecimal hygieneScore,
    @JsonProperty("originalServiceScore") BigDecimal serviceScore,
    @JsonProperty("originalResultCode") String resultCode,
    List<InspectionItemResultResponse> itemResults,
    @JsonIgnore InspectionResultPresentation resultPresentation,
    @JsonIgnore InspectionScoreScaleMigrationAudit scoreScaleMigrationAudit
) {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public InspectionRecordResponse {
    itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
    resultPresentation = resultPresentation == null
        ? InspectionResultPolicy.present(
            fullScore, score, materialScore, hygieneScore, serviceScore,
            passed, resultCode, redlinesJson, standardVersion, null)
        : resultPresentation;
  }

  public InspectionRecordResponse(
      String id,
      String storeId,
      String storeCode,
      String storeName,
      Long brandId,
      String brandName,
      String inspectionDate,
      String inspector,
      String brand,
      BigDecimal fullScore,
      BigDecimal score,
      boolean passed,
      String deductionsJson,
      String redlinesJson,
      String photosJson,
      String note
  ) {
    this(id, storeId, storeCode, storeName, brandId, brandName, inspectionDate, inspector,
        brand, fullScore, score, passed, deductionsJson, redlinesJson, photosJson, note,
        null, null, null, null, null, passed ? "PASSED" : "FAILED", List.of(), null, null);
  }

  public InspectionRecordResponse(
      String id,
      String storeId,
      String storeCode,
      String storeName,
      Long brandId,
      String brandName,
      String inspectionDate,
      String inspector,
      String brand,
      BigDecimal fullScore,
      BigDecimal score,
      boolean passed,
      String deductionsJson,
      String redlinesJson,
      String photosJson,
      String note,
      Long standardVersionId,
      String standardVersion,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      String resultCode,
      List<InspectionItemResultResponse> itemResults
  ) {
    this(id, storeId, storeCode, storeName, brandId, brandName, inspectionDate, inspector,
        brand, fullScore, score, passed, deductionsJson, redlinesJson, photosJson, note,
        standardVersionId, standardVersion, materialScore, hygieneScore, serviceScore,
        resultCode, itemResults, null, null);
  }

  public InspectionRecordResponse(
      String id,
      String storeId,
      String storeCode,
      String storeName,
      Long brandId,
      String brandName,
      String inspectionDate,
      String inspector,
      String brand,
      BigDecimal fullScore,
      BigDecimal score,
      boolean passed,
      String deductionsJson,
      String redlinesJson,
      String photosJson,
      String note,
      Long standardVersionId,
      String standardVersion,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      String resultCode,
      List<InspectionItemResultResponse> itemResults,
      InspectionResultPresentation resultPresentation
  ) {
    this(id, storeId, storeCode, storeName, brandId, brandName, inspectionDate, inspector,
        brand, fullScore, score, passed, deductionsJson, redlinesJson, photosJson, note,
        standardVersionId, standardVersion, materialScore, hygieneScore, serviceScore,
        resultCode, itemResults, resultPresentation, null);
  }

  public InspectionRecordResponse withItemResults(List<InspectionItemResultResponse> results) {
    return new InspectionRecordResponse(id, storeId, storeCode, storeName, brandId, brandName,
        inspectionDate, inspector, brand, fullScore, score, passed, deductionsJson, redlinesJson,
        photosJson, note, standardVersionId, standardVersion, materialScore, hygieneScore,
        serviceScore, resultCode, results, resultPresentation, scoreScaleMigrationAudit);
  }

  @JsonProperty
  public BigDecimal originalPassScore() {
    return resultPresentation.originalPassScore();
  }

  @JsonProperty
  public BigDecimal displayFullScore() {
    return resultPresentation.displayFullScore();
  }

  @JsonProperty("maxScore")
  public BigDecimal maxScore() {
    return resultPresentation.displayFullScore();
  }

  @JsonProperty("fullScore")
  public BigDecimal apiFullScore() {
    return resultPresentation.displayFullScore();
  }

  @JsonProperty
  public BigDecimal displayScore() {
    return resultPresentation.displayScore();
  }

  @JsonProperty("score")
  public BigDecimal apiScore() {
    return resultPresentation.displayScore();
  }

  @JsonProperty
  public BigDecimal displayPassScore() {
    return resultPresentation.displayPassScore();
  }

  @JsonProperty("passScore")
  public BigDecimal passScore() {
    return resultPresentation.displayPassScore();
  }

  @JsonProperty
  public BigDecimal displayMaterialScore() {
    return resultPresentation.displayMaterialScore();
  }

  @JsonProperty("materialScore")
  public BigDecimal apiMaterialScore() {
    return resultPresentation.displayMaterialScore();
  }

  @JsonProperty
  public BigDecimal displayHygieneScore() {
    return resultPresentation.displayHygieneScore();
  }

  @JsonProperty("hygieneScore")
  public BigDecimal apiHygieneScore() {
    return resultPresentation.displayHygieneScore();
  }

  @JsonProperty
  public BigDecimal displayServiceScore() {
    return resultPresentation.displayServiceScore();
  }

  @JsonProperty("serviceScore")
  public BigDecimal apiServiceScore() {
    return resultPresentation.displayServiceScore();
  }

  @JsonProperty
  public boolean displayPassed() {
    return resultPresentation.displayPassed();
  }

  @JsonProperty("passed")
  public boolean apiPassed() {
    return resultPresentation.displayPassed();
  }

  @JsonProperty
  public String displayResultCode() {
    return resultPresentation.displayResultCode();
  }

  @JsonProperty("resultCode")
  public String apiResultCode() {
    return resultPresentation.displayResultCode();
  }

  @JsonProperty
  public String repairStatus() {
    if (scoreScaleMigrationAudit != null
        && "NOT_NEEDED".equals(resultPresentation.repairStatus())) {
      return scoreScaleMigrationAudit.status();
    }
    return resultPresentation.repairStatus();
  }

  @JsonProperty
  public boolean repaired() {
    return resultPresentation.repaired();
  }

  @JsonProperty
  public BigDecimal repairedScore() {
    return resultPresentation.repairedScore();
  }

  @JsonProperty
  public BigDecimal repairedFullScore() {
    return resultPresentation.repairedFullScore();
  }

  @JsonProperty
  public BigDecimal repairedPassScore() {
    return resultPresentation.repairedPassScore();
  }

  @JsonProperty
  public BigDecimal repairedMaterialScore() {
    return resultPresentation.repairedMaterialScore();
  }

  @JsonProperty
  public BigDecimal repairedHygieneScore() {
    return resultPresentation.repairedHygieneScore();
  }

  @JsonProperty
  public BigDecimal repairedServiceScore() {
    return resultPresentation.repairedServiceScore();
  }

  @JsonProperty
  public Boolean repairedPassed() {
    return resultPresentation.repairedPassed();
  }

  @JsonProperty
  public String repairedResultCode() {
    return resultPresentation.repairedResultCode();
  }

  @JsonProperty
  public String repairReason() {
    return resultPresentation.repairReason();
  }

  @JsonProperty
  public String originalStandardVersion() {
    return resultPresentation.originalStandardVersion();
  }

  @JsonProperty
  public Long repairAuditId() {
    return resultPresentation.repairAuditId();
  }

  @JsonProperty
  public Long repairedBy() {
    return resultPresentation.repairedBy();
  }

  @JsonProperty
  public LocalDateTime repairedAt() {
    return resultPresentation.repairedAt();
  }

  @JsonProperty
  public BigDecimal referenceScore200() {
    return resultPresentation.referenceScore200();
  }

  @JsonProperty
  public Long scoreScaleMigrationAuditId() {
    return scoreScaleMigrationAudit == null ? null : scoreScaleMigrationAudit.id();
  }

  @JsonProperty
  public String scoreScaleMigrationStatus() {
    return scoreScaleMigrationAudit == null ? null : scoreScaleMigrationAudit.status();
  }

  @JsonProperty
  public String scoreScaleMigrationReason() {
    return scoreScaleMigrationAudit == null ? null : scoreScaleMigrationAudit.reason();
  }

  @JsonProperty
  public LocalDateTime scoreScaleMigratedAt() {
    return scoreScaleMigrationAudit == null ? null : scoreScaleMigrationAudit.migratedAt();
  }

  @JsonProperty
  public BigDecimal migratedScore() {
    return scoreScaleMigrationAudit == null ? null : scoreScaleMigrationAudit.convertedScore();
  }

  @JsonProperty
  public String scoreDetailStatus() {
    if (scoreScaleMigrationAudit == null) {
      return "CURRENT";
    }
    if (!itemResults.isEmpty()) {
      return "CANONICAL";
    }
    return hasJsonEntries(deductionsJson) || hasJsonEntries(redlinesJson)
        ? "PENDING_REVIEW" : "NO_DETAIL";
  }

  @JsonProperty
  public long redLineCount() {
    long itemCount = itemResults.stream()
        .filter(InspectionItemResultResponse::issueFound)
        .filter(item -> item.redLine() || "RED".equalsIgnoreCase(item.riskLevel()))
        .count();
    if (itemCount > 0) {
      return itemCount;
    }
    try {
      JsonNode redlines = OBJECT_MAPPER.readTree(redlinesJson == null ? "[]" : redlinesJson);
      if (redlines != null && redlines.isArray() && !redlines.isEmpty()) {
        return redlines.size();
      }
    } catch (Exception ignored) {
      // Historical free-text JSON must not make the record endpoint fail.
    }
    return "RED_LINE_FAILED".equalsIgnoreCase(displayResultCode()) ? 1 : 0;
  }

  @JsonProperty
  public long yellowLineCount() {
    return itemResults.stream()
        .filter(InspectionItemResultResponse::issueFound)
        .filter(item -> "YELLOW".equalsIgnoreCase(item.riskLevel()))
        .count();
  }

  private boolean hasJsonEntries(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String normalized = value.trim().replaceAll("\\s+", "");
    return !"[]".equals(normalized) && !"null".equalsIgnoreCase(normalized);
  }
}
