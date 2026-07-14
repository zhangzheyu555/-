package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InspectionStandardValidator {
  static final BigDecimal REQUIRED_FULL_SCORE = InspectionScoringRules.MAX_SCORE;
  static final BigDecimal REQUIRED_PASS_SCORE = InspectionScoringRules.PASS_SCORE;
  static final int REQUIRED_ITEM_COUNT = 105;
  static final int REQUIRED_RED_LINE_COUNT = 21;
  static final int REQUIRED_YELLOW_LINE_COUNT = 9;

  private static final Map<String, ExpectedCategory> EXPECTED_CATEGORIES = expectedCategories();

  private InspectionStandardValidator() {
  }

  static InspectionStandardValidation validate(
      InspectionStandardRepository.VersionRow version,
      List<InspectionStandardItemResponse> sourceItems
  ) {
    List<InspectionStandardItemResponse> items = sourceItems == null ? List.of() : sourceItems;
    List<InspectionStandardDiagnostic> diagnostics = new ArrayList<>();
    Map<String, MutableCategory> actualCategories = new LinkedHashMap<>();
    EXPECTED_CATEGORIES.keySet().forEach(code -> actualCategories.put(code, new MutableCategory()));
    int redLineCount = 0;
    int yellowLineCount = 0;

    for (InspectionStandardItemResponse item : items) {
      String categoryCode = category(item == null ? null : item.dimension());
      if (categoryCode == null) {
        diagnostics.add(new InspectionStandardDiagnostic(
            null, "未知分类", null, null, null, null,
            "存在无法识别分类的巡检条款：" + safeCode(item)));
      } else {
        MutableCategory actual = actualCategories.get(categoryCode);
        actual.count++;
        actual.score = actual.score.add(amount(item.suggestedScore()));
      }
      if (item != null && item.redLine() && "RED".equalsIgnoreCase(item.riskLevel())) {
        redLineCount++;
      }
      if (item != null && !item.redLine() && "YELLOW".equalsIgnoreCase(item.riskLevel())) {
        yellowLineCount++;
      }
    }

    if (version == null) {
      diagnostics.add(new InspectionStandardDiagnostic(
          null, null, null, null, null, null, "当前没有启用的巡检标准"));
    } else {
      if (amount(version.fullScore()).compareTo(REQUIRED_FULL_SCORE) != 0) {
        diagnostics.add(new InspectionStandardDiagnostic(
            null, null, null, null, REQUIRED_FULL_SCORE, amount(version.fullScore()),
            "标准总分应为200分，当前为" + plain(amount(version.fullScore())) + "分"));
      }
      if (amount(version.passScore()).compareTo(REQUIRED_PASS_SCORE) != 0) {
        diagnostics.add(new InspectionStandardDiagnostic(
            null, null, null, null, REQUIRED_PASS_SCORE, amount(version.passScore()),
            "合格线应为180分，当前为" + plain(amount(version.passScore())) + "分"));
      }
    }
    if (items.size() != REQUIRED_ITEM_COUNT) {
      diagnostics.add(new InspectionStandardDiagnostic(
          null, null, REQUIRED_ITEM_COUNT, items.size(), null, null,
          "标准应为105条，当前为" + items.size() + "条"));
    }

    List<InspectionStandardCategoryStats> categoryStats = new ArrayList<>();
    for (Map.Entry<String, ExpectedCategory> entry : EXPECTED_CATEGORIES.entrySet()) {
      String categoryCode = entry.getKey();
      ExpectedCategory expected = entry.getValue();
      MutableCategory actual = actualCategories.get(categoryCode);
      BigDecimal actualScore = amount(actual.score);
      boolean valid = actual.count == expected.count
          && actualScore.compareTo(expected.score) == 0;
      categoryStats.add(new InspectionStandardCategoryStats(
          categoryCode, expected.name, expected.count, actual.count,
          expected.score, actualScore, valid));
      if (!valid) {
        String message = expected.name + "应为" + expected.count + "条/"
            + plain(expected.score) + "分，当前" + actual.count + "条/"
            + plain(actualScore) + "分";
        diagnostics.add(new InspectionStandardDiagnostic(
            categoryCode, expected.name, expected.count, actual.count,
            expected.score, actualScore, message));
      }
    }

    if (redLineCount != REQUIRED_RED_LINE_COUNT) {
      diagnostics.add(new InspectionStandardDiagnostic(
          null, "红线", REQUIRED_RED_LINE_COUNT, redLineCount, null, null,
          "红线应为21条，当前为" + redLineCount + "条"));
    }
    if (yellowLineCount != REQUIRED_YELLOW_LINE_COUNT) {
      diagnostics.add(new InspectionStandardDiagnostic(
          null, "黄线", REQUIRED_YELLOW_LINE_COUNT, yellowLineCount, null, null,
          "黄线应为9条，当前为" + yellowLineCount + "条"));
    }

    boolean valid = diagnostics.isEmpty();
    String validationError = valid ? null : diagnostics.stream()
        .map(InspectionStandardDiagnostic::message)
        .reduce((left, right) -> left + "；" + right)
        .orElse("巡检标准校验失败");
    return new InspectionStandardValidation(
        valid,
        validationError,
        diagnostics,
        categoryStats,
        new InspectionStandardRiskStats(redLineCount, yellowLineCount)
    );
  }

  static String category(String dimension) {
    String value = dimension == null ? "" : dimension.trim();
    if (value.contains("物料") || value.equalsIgnoreCase("MATERIAL")) {
      return "MATERIAL";
    }
    if (value.contains("卫生") || value.equalsIgnoreCase("HYGIENE")) {
      return "HYGIENE";
    }
    if (value.contains("服务") || value.equalsIgnoreCase("SERVICE")) {
      return "SERVICE";
    }
    return null;
  }

  private static Map<String, ExpectedCategory> expectedCategories() {
    Map<String, ExpectedCategory> result = new LinkedHashMap<>();
    result.put("MATERIAL", new ExpectedCategory("物料", 40, amount("37")));
    result.put("HYGIENE", new ExpectedCategory("卫生", 47, amount("63")));
    result.put("SERVICE", new ExpectedCategory("服务", 18, amount("100")));
    return Collections.unmodifiableMap(result);
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private static String safeCode(InspectionStandardItemResponse item) {
    return item == null || item.code() == null || item.code().isBlank() ? "未编号条款" : item.code();
  }

  private record ExpectedCategory(String name, int count, BigDecimal score) {
  }

  private static final class MutableCategory {
    private int count;
    private BigDecimal score = BigDecimal.ZERO.setScale(2);
  }
}
