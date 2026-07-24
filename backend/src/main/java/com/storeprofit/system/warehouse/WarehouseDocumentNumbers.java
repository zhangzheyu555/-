package com.storeprofit.system.warehouse;

import java.util.Locale;

final class WarehouseDocumentNumbers {
  private static final long SEQUENCE_MODULUS = 1_000_000_000L;

  private WarehouseDocumentNumbers() {
  }

  static String receipt(String date, long sourceId) {
    return documentNumber("RKD", date, String.valueOf(sourceId));
  }

  static String delivery(String date, String deliveryId, String requisitionId) {
    return documentNumber("PSD", date, firstPresent(deliveryId, requisitionId));
  }

  static String returnOrder(String date, String returnNo, String returnId) {
    return documentNumber("PSTH", date, firstPresent(returnNo, returnId));
  }

  private static String documentNumber(String prefix, String date, String source) {
    String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    if (normalized.matches(prefix + "\\d{15}")) {
      return normalized;
    }
    return prefix + shortDate(date) + sequence(normalized);
  }

  private static String shortDate(String value) {
    if (value == null || value.length() < 10) {
      return "000000";
    }
    String compact = value.substring(0, 10).replace("-", "");
    return compact.length() == 8 ? compact.substring(2) : "000000";
  }

  private static String sequence(String source) {
    String digits = source.replaceAll("\\D", "");
    if (!digits.isEmpty()) {
      String suffix = digits.substring(Math.max(0, digits.length() - 9));
      return String.format(Locale.ROOT, "%09d", Long.parseLong(suffix));
    }
    long stableHash = Integer.toUnsignedLong(source.hashCode()) % SEQUENCE_MODULUS;
    return String.format(Locale.ROOT, "%09d", stableHash);
  }

  private static String firstPresent(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }
}
