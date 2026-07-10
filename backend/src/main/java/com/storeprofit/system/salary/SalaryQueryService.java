package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SalaryQueryService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final SalaryRepository salaryRepository;
  private final AccessControlService accessControl;

  public SalaryQueryService(SalaryRepository salaryRepository, AccessControlService accessControl) {
    this.salaryRepository = salaryRepository;
    this.accessControl = accessControl;
  }

  public List<SalaryRecordResponse> records(AuthUser user, String month, Long brandId, String storeId) {
    return records(user, month, brandId, storeId, false);
  }

  public List<SalaryRecordResponse> records(AuthUser user, String month, Long brandId, String storeId, boolean allMonths) {
    requireReadRole(user);
    String targetMonth = allMonths ? null : normalizeMonth(month);
    if (accessControl != null) {
      String targetStoreId = blankToNull(storeId);
      if (targetStoreId != null) {
        accessControl.requireStoreAccess(user, targetStoreId, "查看工资数据");
      }
      return salaryRepository.records(user.tenantId(), targetMonth, brandId, targetStoreId).stream()
          .filter(row -> accessControl.canAccessStore(user, row.storeId()))
          .map(row -> maskForRole(user, row))
          .toList();
    }
    if (isStoreManager(user)) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
      return salaryRepository.records(user.tenantId(), targetMonth, brandId, scopedStoreId).stream()
          .map(row -> maskForRole(user, row))
          .toList();
    }
    return salaryRepository.records(user.tenantId(), targetMonth, brandId, blankToNull(storeId)).stream()
        .map(row -> maskForRole(user, row))
        .toList();
  }

  public SalaryPageResponse recordsPaged(AuthUser user, String month, Long brandId, String storeId, int page, int size) {
    requireReadRole(user);
    String targetMonth = normalizeMonth(month);
    if (accessControl != null) {
      String targetStoreId = blankToNull(storeId);
      if (targetStoreId != null) {
        accessControl.requireStoreAccess(user, targetStoreId, "查看工资数据");
      }
      SalaryRepository.SalaryPageResult result = salaryRepository.page(user.tenantId(), targetMonth, brandId, targetStoreId, page, size);
      List<SalaryRecordResponse> filtered = result.rows().stream()
          .filter(row -> accessControl.canAccessStore(user, row.storeId()))
          .map(row -> maskForRole(user, row))
          .toList();
      SalarySummaryResponse summary = summaryFromRows(filtered, targetMonth);
      return new SalaryPageResponse(filtered, result.total(), result.page(), result.size(), result.totalPages(), summary);
    }
    if (isStoreManager(user)) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
      SalaryRepository.SalaryPageResult result = salaryRepository.page(user.tenantId(), targetMonth, brandId, scopedStoreId, page, size);
      List<SalaryRecordResponse> masked = result.rows().stream()
          .map(row -> maskForRole(user, row))
          .toList();
      SalarySummaryResponse summary = summaryFromRows(masked, targetMonth);
      return new SalaryPageResponse(masked, result.total(), result.page(), result.size(), result.totalPages(), summary);
    }
    SalaryRepository.SalaryPageResult result = salaryRepository.page(user.tenantId(), targetMonth, brandId, blankToNull(storeId), page, size);
    List<SalaryRecordResponse> masked = result.rows().stream()
        .map(row -> maskForRole(user, row))
        .toList();
    SalarySummaryResponse summary = summaryFromRows(masked, targetMonth);
    return new SalaryPageResponse(masked, result.total(), result.page(), result.size(), result.totalPages(), summary);
  }

  public SalarySummaryResponse summary(AuthUser user, String month, Long brandId, String storeId) {
    String targetMonth = normalizeMonth(month);
    List<SalaryRecordResponse> rows = records(user, targetMonth, brandId, storeId);
    return summaryFromRows(rows, targetMonth);
  }

  public SalaryRecordResponse getRecord(AuthUser user, String id) {
    requireReadRole(user);
    return maskForRole(user, requireRecord(user, id));
  }

  public SalaryRecordResponse requireRecord(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "工资记录编号不能为空");
    SalaryRecordResponse record = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "未找到工资记录", HttpStatus.NOT_FOUND));
    requireStoreScope(user, record.storeId());
    return record;
  }

  private SalarySummaryResponse summaryFromRows(List<SalaryRecordResponse> rows, String month) {
    return new SalarySummaryResponse(
        month,
        (int) rows.stream().map(SalaryRecordResponse::storeId).distinct().count(),
        rows.size(),
        sum(rows.stream().map(SalaryRecordResponse::gross).toList()),
        sum(rows.stream().map(SalaryRecordResponse::base).toList()),
        sum(rows.stream().map(SalaryRecordResponse::commission).toList()),
        sum(rows.stream().map(SalaryRecordResponse::overtime).toList())
    );
  }

  // === role / auth helpers ===

  /**
   * Mask monetary fields for roles that should only see employee identity info.
   * STORE_MANAGER can see who works at their store and their status,
   * but not individual salary amounts.
   */
  private SalaryRecordResponse maskForRole(AuthUser user, SalaryRecordResponse record) {
    if (user == null || record == null) return record;
    if ("STORE_MANAGER".equals(user.role())) {
      return record.masked();
    }
    return record;
  }

  private void requireReadRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryRead(user);
      return;
    }
    if (!List.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to read salary records", HttpStatus.FORBIDDEN);
    }
  }

  public void requireStoreScope(AuthUser user, String storeId) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, storeId, "处理工资数据");
      return;
    }
    if (isStoreManager(user) && !requireManagerStore(user).equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return "STORE_MANAGER".equals(user.role());
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  // === shared utilities ===

  static String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "Month must use YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  static BigDecimal sum(List<BigDecimal> values) {
    return values.stream()
        .map(value -> value == null ? BigDecimal.ZERO : value)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
