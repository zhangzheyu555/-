package com.storeprofit.system.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Parses legacy deduction and red-line JSON without changing the original stored payload. */
final class InspectionLegacySnapshotParser {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private InspectionLegacySnapshotParser() {}

  static List<InspectionStandardSnapshot> parse(InspectionRecordRequest request) {
    List<InspectionStandardSnapshot> snapshots = new ArrayList<>();
    append(snapshots, request == null ? null : request.deductionsJson(), false);
    append(snapshots, request == null ? null : request.redlinesJson(), true);
    return snapshots;
  }

  private static void append(
      List<InspectionStandardSnapshot> snapshots,
      String rawJson,
      boolean forceRedLine
  ) {
    if (rawJson == null || rawJson.isBlank()) {
      return;
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(rawJson);
      if (root == null || !root.isArray()) {
        return;
      }
      for (JsonNode node : root) {
        if (node == null || !node.isObject()) {
          continue;
        }
        Long standardId = nodeLong(node, "standard_id", "standardId");
        String title = nodeText(node, "standard_title", "standardTitle", "item", "title", "deduction_content");
        if (standardId == null && (title == null || title.isBlank())) {
          continue;
        }
        boolean redLine = forceRedLine || nodeBoolean(node, "red_line", "redline", "redLine");
        snapshots.add(new InspectionStandardSnapshot(
            standardId,
            nodeText(node, "standard_version", "standardVersion"),
            nodeText(node, "dimension", "dim", "deduction_project", "project"),
            title,
            nodeText(node, "standard_description", "standardDescription", "method"),
            nodeDecimal(node, "suggested_score", "suggestedScore", "score"),
            nodeDecimal(node, "actual_deduction_score", "actualDeductionScore", "deduction_score", "deductionScore", "deduct"),
            redLine,
            nodeText(node, "problem_description", "problemDescription", "issue", "description"),
            snapshots.size() + 1
        ));
      }
    } catch (JsonProcessingException ignored) {
      // Legacy records may contain free-form JSON; preserve it without creating malformed rows.
    }
  }

  private static String nodeText(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && !value.isNull()) {
        String text = value.asText("").trim();
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }

  private static Long nodeLong(JsonNode node, String... names) {
    String value = nodeText(node, names);
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static BigDecimal nodeDecimal(JsonNode node, String... names) {
    String value = nodeText(node, names);
    if (value == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    try {
      return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException ignored) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
  }

  private static boolean nodeBoolean(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isBoolean()) {
        return value.booleanValue();
      }
      String text = value.asText("").trim();
      if ("1".equals(text) || "yes".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)) {
        return true;
      }
    }
    return false;
  }
}
