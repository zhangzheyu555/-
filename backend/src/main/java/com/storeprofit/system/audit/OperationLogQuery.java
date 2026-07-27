package com.storeprofit.system.audit;

import com.storeprofit.system.common.BusinessException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;

public record OperationLogQuery(
    String keyword,
    String operatorName,
    String action,
    String storeScope,
    String storeId,
    LocalDate startDate,
    LocalDate endDate,
    int page,
    int pageSize
) {
  public static final String STORE_SCOPE_ALL = "ALL";
  public static final String STORE_SCOPE_STORE = "STORE";
  public static final String STORE_SCOPE_GLOBAL = "GLOBAL";
  private static final Set<String> STORE_SCOPES = Set.of(
      STORE_SCOPE_ALL, STORE_SCOPE_STORE, STORE_SCOPE_GLOBAL);

  public OperationLogQuery {
    keyword = normalizedText(keyword, 100, "关键词");
    operatorName = normalizedText(operatorName, 120, "操作人");
    action = normalizedText(action, 80, "日志动作");
    storeScope = normalizedScope(storeScope);
    storeId = normalizedText(storeId, 64, "门店");
    page = Math.max(1, page);
    pageSize = Math.max(1, Math.min(pageSize, 100));

    if (STORE_SCOPE_STORE.equals(storeScope) && storeId == null) {
      throw invalid("选择“指定门店”时必须选择一个门店");
    }
    if (!STORE_SCOPE_STORE.equals(storeScope)) {
      storeId = null;
    }
    if ((startDate == null) != (endDate == null)) {
      throw invalid("开始日期和结束日期必须同时填写");
    }
    if (startDate != null && startDate.isAfter(endDate)) {
      throw invalid("开始日期不能晚于结束日期");
    }
    if (startDate != null && ChronoUnit.DAYS.between(startDate, endDate) > 365) {
      throw invalid("单次最多查询 366 天的操作日志");
    }
  }

  private static String normalizedScope(String value) {
    String normalized = value == null || value.isBlank()
        ? STORE_SCOPE_ALL
        : value.trim().toUpperCase(Locale.ROOT);
    if (!STORE_SCOPES.contains(normalized)) {
      throw invalid("门店范围无效");
    }
    return normalized;
  }

  private static String normalizedText(String value, int maxLength, String fieldName) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw invalid(fieldName + "不能超过 " + maxLength + " 个字符");
    }
    return normalized;
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("AUDIT_QUERY_INVALID", message, HttpStatus.BAD_REQUEST);
  }
}
