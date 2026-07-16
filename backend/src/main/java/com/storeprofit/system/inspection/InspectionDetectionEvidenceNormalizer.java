package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Canonicalizes raw model detections without invoking storage, permissions, or external services. */
final class InspectionDetectionEvidenceNormalizer {
  private static final BigDecimal IOU_THRESHOLD = new BigDecimal("0.90");
  private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private InspectionDetectionEvidenceNormalizer() {}

  static List<Map<String, Object>> deduplicate(Object rawDetections) {
    if (!(rawDetections instanceof Collection<?> collection)) {
      return List.of();
    }
    List<Map<String, Object>> unique = new ArrayList<>();
    for (Object value : collection) {
      if (!(value instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> candidate = new LinkedHashMap<>();
      raw.forEach((key, item) -> candidate.put(String.valueOf(key), item));
      int duplicateIndex = duplicateIndex(unique, candidate);
      if (duplicateIndex < 0) {
        unique.add(candidate);
      } else if (confidence(candidate).compareTo(confidence(unique.get(duplicateIndex))) > 0) {
        unique.set(duplicateIndex, candidate);
      }
    }
    unique.sort(Comparator.comparing(InspectionDetectionEvidenceNormalizer::canonicalValue));
    return List.copyOf(unique);
  }

  static BigDecimal maximumConfidence(List<Map<String, Object>> detections) {
    return detections.stream()
        .map(InspectionDetectionEvidenceNormalizer::confidence)
        .max(BigDecimal::compareTo)
        .orElse(ZERO_AMOUNT)
        .setScale(4, RoundingMode.HALF_UP);
  }

  static String issueCode(List<Map<String, Object>> detections) {
    return detections.stream()
        .map(item -> textValue(item, "class_name", "className", "label"))
        .filter(Objects::nonNull)
        .map(value -> value.toUpperCase(java.util.Locale.ROOT))
        .distinct()
        .sorted()
        .collect(Collectors.joining("+"));
  }

  static String stableKey(Map<String, Object> source, List<Map<String, Object>> detections) {
    String canonical = String.join("|",
        Objects.toString(textValue(source, "image_id", "imageId"), ""),
        Objects.toString(textValue(source, "filename", "fileName"), ""),
        detections.stream().map(InspectionDetectionEvidenceNormalizer::canonicalValue).sorted()
            .collect(Collectors.joining(";"))
    );
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return "det-" + HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static double intersectionOverUnion(double[] first, double[] second) {
    double x1 = Math.max(first[0], second[0]);
    double y1 = Math.max(first[1], second[1]);
    double x2 = Math.min(first[2], second[2]);
    double y2 = Math.min(first[3], second[3]);
    double intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    double firstArea = Math.max(0, first[2] - first[0]) * Math.max(0, first[3] - first[1]);
    double secondArea = Math.max(0, second[2] - second[0]) * Math.max(0, second[3] - second[1]);
    double union = firstArea + secondArea - intersection;
    return union <= 0 ? 0 : intersection / union;
  }

  private static int duplicateIndex(
      List<Map<String, Object>> values,
      Map<String, Object> candidate
  ) {
    for (int index = 0; index < values.size(); index++) {
      Map<String, Object> existing = values.get(index);
      if (!Objects.equals(
          normalizeMatchText(textValue(existing, "class_name", "className", "label")),
          normalizeMatchText(textValue(candidate, "class_name", "className", "label")))) {
        continue;
      }
      double[] first = detectionBox(existing);
      double[] second = detectionBox(candidate);
      if (first != null && second != null
          && intersectionOverUnion(first, second) >= IOU_THRESHOLD.doubleValue()) {
        return index;
      }
      if (first == null && second == null && canonicalValue(existing).equals(canonicalValue(candidate))) {
        return index;
      }
    }
    return -1;
  }

  private static double[] detectionBox(Map<String, Object> detection) {
    Object raw = value(detection, "box_xyxy", "boxXyxy", "bbox", "box");
    if (!(raw instanceof List<?> values) || values.size() < 4) {
      return null;
    }
    double[] box = new double[4];
    for (int index = 0; index < 4; index++) {
      Object coordinate = values.get(index);
      if (!(coordinate instanceof Number number)) {
        try {
          box[index] = Double.parseDouble(String.valueOf(coordinate));
        } catch (NumberFormatException ex) {
          return null;
        }
      } else {
        box[index] = number.doubleValue();
      }
    }
    return box;
  }

  private static BigDecimal confidence(Map<String, Object> detection) {
    BigDecimal value = decimalValue(detection, "confidence", "score");
    return value == null ? ZERO_AMOUNT : value.max(BigDecimal.ZERO);
  }

  private static String canonicalValue(Map<String, Object> detection) {
    double[] box = detectionBox(detection);
    String coordinates = box == null ? "" : java.util.Arrays.stream(box)
        .map(value -> Math.rint(value * 1000d) / 1000d)
        .mapToObj(value -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
        .collect(Collectors.joining(","));
    return String.join(":",
        normalizeMatchText(textValue(detection, "class_name", "className", "label")),
        coordinates,
        Objects.toString(textValue(detection, "source"), ""),
        Objects.toString(booleanValue(detection, "on_floor", "onFloor"), "")
    );
  }

  private static String normalizeMatchText(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^\\p{IsHan}a-z0-9]+", "")
        .replace("检查标准", "")
        .replace("标准", "");
  }

  private static Object value(Map<String, Object> source, String... keys) {
    if (source == null) {
      return null;
    }
    for (String key : keys) {
      if (source.containsKey(key)) {
        return source.get(key);
      }
    }
    return null;
  }

  private static String textValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).trim();
    return text.isBlank() ? null : text;
  }

  private static Boolean booleanValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw instanceof Boolean bool) {
      return bool;
    }
    if (raw == null) {
      return null;
    }
    String text = String.valueOf(raw).trim();
    return text.isBlank() ? null : Boolean.parseBoolean(text);
  }

  private static BigDecimal decimalValue(Map<String, Object> source, String... keys) {
    Object raw = value(source, keys);
    if (raw == null) {
      return null;
    }
    try {
      String text = String.valueOf(raw).trim();
      return text.isBlank() ? null : new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
