package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SalaryQueryService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final SalaryRepository salaryRepository;
  private final AccessControlService accessControl;
  private final DataScopeService dataScopeService;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public SalaryQueryService(
      SalaryRepository salaryRepository,
      AccessControlService accessControl,
      DataScopeService dataScopeService,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.salaryRepository = salaryRepository;
    this.accessControl = accessControl;
    this.dataScopeService = dataScopeService;
    this.businessScopeResolver = businessScopeResolver;
  }

  public SalaryQueryService(
      SalaryRepository salaryRepository,
      AccessControlService accessControl,
      DataScopeService dataScopeService
  ) {
    this(salaryRepository, accessControl, dataScopeService, null);
  }

  public SalaryQueryService(SalaryRepository salaryRepository, AccessControlService accessControl) {
    this(salaryRepository, accessControl, null, null);
  }

  public List<SalaryRecordResponse> records(AuthUser user, String month, Long brandId, String storeId) {
    return records(user, month, brandId, storeId, false);
  }

  public List<SalaryRecordResponse> records(AuthUser user, String month, Long brandId, String storeId, boolean allMonths) {
    requireReadRole(user, null, storeId, allMonths ? null : month);
    String targetMonth = allMonths ? null : normalizeMonth(month);
    DataScope dataScope = salaryScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看工资数据", targetMonth);
    String targetStoreId = resolveStoreScope(user, businessScope.storeId(), dataScope);
    return salaryRecords(
        user.tenantId(), targetMonth, businessScope.brandId(), targetStoreId, dataScope).stream()
        .map(row -> maskForRole(user, row))
        .toList();
  }

  public SalaryPageResponse recordsPaged(AuthUser user, String month, Long brandId, String storeId, int page, int size) {
    requireReadRole(user, null, storeId, month);
    String targetMonth = normalizeMonth(month);
    DataScope dataScope = salaryScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看工资数据", targetMonth);
    String targetStoreId = resolveStoreScope(user, businessScope.storeId(), dataScope);
    SalaryRepository.SalaryPageResult result = salaryPage(
        user.tenantId(), targetMonth, businessScope.brandId(), targetStoreId, page, size, dataScope);
    List<SalaryRecordResponse> masked = result.rows().stream()
        .map(row -> maskForRole(user, row))
        .toList();
    SalarySummaryResponse summary = summaryFromRows(masked, targetMonth);
    return new SalaryPageResponse(masked, result.total(), result.page(), result.size(), result.totalPages(), summary);
  }

  public SalaryEmployeePageResponse employeePage(
      AuthUser user, String month, Long brandId, String storeId, String status, String keyword, int page, int size
  ) {
    requireReadRole(user, null, storeId, month);
    String targetMonth = normalizeMonth(month);
    int safePage = validatePage(page);
    int safeSize = validateSize(size);
    DataScope dataScope = salaryScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看工资数据", targetMonth);
    String targetStoreId = resolveStoreScope(user, businessScope.storeId(), dataScope);

    String statusFilter = blankToNull(status);
    String keywordFilter = blankToNull(keyword);
    List<SalaryRecordResponse> scopedRows = employeeSalaryRows(
        user.tenantId(), targetMonth, businessScope.brandId(), targetStoreId, dataScope).stream()
        .map(row -> maskForRole(user, row))
        .toList();

    Map<String, Integer> statusCounts = new LinkedHashMap<>();
    for (SalaryRecordResponse row : scopedRows) {
      statusCounts.merge(row.status(), 1, Integer::sum);
    }
    SalaryRepository.SalaryEmployeePageResult pageResult = salaryRepository.employeeSalaryPage(
        user.tenantId(), targetMonth, businessScope.brandId(), targetStoreId, statusFilter, keywordFilter,
        safePage, safeSize, dataScope);
    List<SalaryRecordResponse> rows = pageResult.rows().stream()
        .map(row -> maskForRole(user, row))
        .toList();
    return new SalaryEmployeePageResponse(
        rows, pageResult.total(), safePage, safeSize, pageResult.totalPages(), summaryFromRows(scopedRows, targetMonth), statusCounts,
        sum(scopedRows.stream().map(SalaryRecordResponse::workHours).toList()),
        sum(scopedRows.stream().map(SalaryRecordResponse::vacationLeft).toList()));
  }

  private int validatePage(int page) {
    if (page < 1) {
      throw new BusinessException("BAD_PAGE", "页码必须大于等于1", HttpStatus.BAD_REQUEST);
    }
    return page;
  }

  private int validateSize(int size) {
    if (size < 1 || size > 100) {
      throw new BusinessException("BAD_PAGE_SIZE", "每页数量必须在1到100之间", HttpStatus.BAD_REQUEST);
    }
    return size;
  }

  public List<SalaryAvailableMonth> availableMonths(AuthUser user, String storeId) {
    requireReadRole(user, null, storeId, null);
    DataScope dataScope = salaryScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, null, "查看工资月份", null);
    String targetStoreId = resolveStoreScope(user, businessScope.storeId(), dataScope);
    return dataScope == null
        ? salaryRepository.availableMonths(user.tenantId(), targetStoreId)
        : salaryRepository.availableMonths(user.tenantId(), targetStoreId, dataScope);
  }

  public SalarySummaryResponse summary(AuthUser user, String month, Long brandId, String storeId) {
    String targetMonth = normalizeMonth(month);
    List<SalaryRecordResponse> rows = records(user, targetMonth, brandId, storeId);
    return summaryFromRows(rows, targetMonth);
  }

  public SalaryRecordResponse getRecord(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "工资记录编号不能为空");
    SalaryRecordResponse auditRecord = salaryRepository.record(user.tenantId(), targetId).orElse(null);
    requireReadRole(
        user,
        targetId,
        auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month()
    );
    return maskForRole(user, requireRecord(user, id));
  }

  public SalaryRecordResponse requireRecord(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "工资记录编号不能为空");
    SalaryRecordResponse record = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "未找到工资记录", HttpStatus.NOT_FOUND));
    if (businessScopeResolver != null) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, record.storeId(), null, "查看工资数据", record.month());
    } else {
      requireStoreScope(user, record.storeId());
    }
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

  private void requireReadRole(AuthUser user, String salaryId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireSalaryRead(user, salaryId, storeId, month);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "No permission to read salary records", HttpStatus.FORBIDDEN);
    }
  }

  public void requireStoreScope(AuthUser user, String storeId) {
    if (businessScopeResolver != null && isStoreManager(user)) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, null, "处理工资数据");
      return;
    }
    DataScope dataScope = salaryScope(user);
    if (dataScope != null) {
      if (!dataScope.allowsStore(storeId)) {
        throw new BusinessException("FORBIDDEN", "当前账号只能处理授权门店的工资数据", HttpStatus.FORBIDDEN);
      }
      return;
    }
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

  public String resolveStoreForWrite(AuthUser user, String requestedStoreId, String action) {
    BusinessScope scope = resolveBusinessScope(user, requestedStoreId, null, action, null);
    return requireText(scope.storeId(), "STORE_REQUIRED", "请选择门店");
  }

  private String resolveStoreScope(AuthUser user, String requestedStoreId, DataScope dataScope) {
    String targetStoreId = blankToNull(requestedStoreId);
    if (dataScope != null) {
      if (targetStoreId != null && !dataScope.allowsStore(targetStoreId)) {
        throw new BusinessException("FORBIDDEN", "当前账号只能查看授权门店的工资数据", HttpStatus.FORBIDDEN);
      }
      return targetStoreId;
    }
    if (!isStoreManager(user)) {
      return targetStoreId;
    }
    String scopedStoreId = requireManagerStore(user);
    if (targetStoreId != null && !scopedStoreId.equals(targetStoreId)) {
      throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
    }
    return scopedStoreId;
  }

  // === shared utilities ===

  static String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "月份格式必须使用 YYYY-MM", HttpStatus.BAD_REQUEST);
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

  private DataScope salaryScope(AuthUser user) {
    return dataScopeService == null ? null : dataScopeService.scope(user, DataScopeDomains.SALARY);
  }

  private BusinessScope resolveBusinessScope(
      AuthUser user,
      String storeId,
      Long brandId,
      String action,
      String auditMonth
  ) {
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, brandId, action, auditMonth);
    }
    String requestedStoreId = blankToNull(storeId);
    if (requestedStoreId == null && isStoreManager(user)) {
      requestedStoreId = requireManagerStore(user);
    }
    return new BusinessScope(requestedStoreId, null, brandId, null, salaryScope(user));
  }

  private List<SalaryRecordResponse> salaryRecords(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    return dataScope == null
        ? salaryRepository.records(tenantId, month, brandId, storeId)
        : salaryRepository.records(tenantId, month, brandId, storeId, dataScope);
  }

  private SalaryRepository.SalaryPageResult salaryPage(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      int page,
      int size,
      DataScope dataScope
  ) {
    return dataScope == null
        ? salaryRepository.page(tenantId, month, brandId, storeId, page, size)
        : salaryRepository.page(tenantId, month, brandId, storeId, page, size, dataScope);
  }

  private List<SalaryRecordResponse> employeeSalaryRows(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    return dataScope == null
        ? salaryRepository.employeeSalaryRows(tenantId, month, brandId, storeId)
        : salaryRepository.employeeSalaryRows(tenantId, month, brandId, storeId, dataScope);
  }
}
