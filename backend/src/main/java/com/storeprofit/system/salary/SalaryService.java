package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final String STATUS_DRAFT = "DRAFT";
  private static final String STATUS_SUBMITTED = "SUBMITTED";
  private static final String STATUS_APPROVED = "APPROVED";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_LOCKED = "LOCKED";
  private final SalaryRepository salaryRepository;
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessTodoService businessTodoService;

  @Autowired
  public SalaryService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService
  ) {
    this.salaryRepository = salaryRepository;
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessTodoService = businessTodoService;
  }

  public SalaryService(SalaryRepository salaryRepository, EmployeeRepository employeeRepository) {
    this(salaryRepository, employeeRepository, null, null);
  }

  public SalaryService(SalaryRepository salaryRepository) {
    this(salaryRepository, null, null, null);
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
        accessControl.requireStoreAccess(user, DataScopeDomains.SALARY, targetStoreId, "查看工资数据");
      }
      DataScope dataScope = accessControl.dataScope(user, DataScopeDomains.SALARY);
      return salaryRepository.records(user.tenantId(), targetMonth, brandId, targetStoreId, dataScope);
    }
    if (isStoreManager(user)) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
      return salaryRepository.records(user.tenantId(), targetMonth, brandId, scopedStoreId);
    }
    return salaryRepository.records(user.tenantId(), targetMonth, brandId, blankToNull(storeId));
  }

  public SalarySummaryResponse summary(AuthUser user, String month, Long brandId, String storeId) {
    String targetMonth = normalizeMonth(month);
    List<SalaryRecordResponse> rows = records(user, targetMonth, brandId, storeId);
    return new SalarySummaryResponse(
        targetMonth,
        (int) rows.stream().map(SalaryRecordResponse::storeId).distinct().count(),
        rows.size(),
        sum(rows.stream().map(SalaryRecordResponse::gross).toList()),
        sum(rows.stream().map(SalaryRecordResponse::base).toList()),
        sum(rows.stream().map(SalaryRecordResponse::commission).toList()),
        sum(rows.stream().map(SalaryRecordResponse::overtime).toList())
    );
  }

  @Transactional
  public SalaryRecordResponse save(AuthUser user, String id, SalaryRecordRequest request) {
    requireEditRole(user);
    SalaryRecordRequest normalized = normalizeRequest(user, request);
    requireStoreScope(user, normalized.storeId());
    if (!salaryRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    salaryRepository.record(user.tenantId(), targetId).ifPresent(existing -> {
      requireStoreScope(user, existing.storeId());
      requireEditableStatus(existing);
    });
    salaryRepository.upsert(user.tenantId(), targetId, normalized);
    salaryRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "salary_save",
        targetId,
        normalized.storeId(),
        normalized.month(),
        "工资记录已保存"
    );
    reconcileTodos(user, normalized.month());
    return salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("SAVE_FAILED", "工资记录保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public List<SalaryRecordResponse> generate(AuthUser user, SalaryGenerateRequest request) {
    return generateInternal(user, request).records;
  }

  @Transactional
  public SalaryGenerateReport generateWithReport(AuthUser user, SalaryGenerateRequest request) {
    return generateInternal(user, request).report();
  }

  private GenerateResult generateInternal(AuthUser user, SalaryGenerateRequest request) {
    requireEditRole(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Salary generation payload is required", HttpStatus.BAD_REQUEST);
    }
    String storeId = requireText(request.storeId(), "STORE_REQUIRED", "Store is required");
    String month = normalizeMonth(request.month());
    requireStoreScope(user, storeId);
    if (!salaryRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    if (employeeRepository == null) {
      throw new BusinessException("EMPLOYEE_REPOSITORY_UNAVAILABLE", "Employee repository is not available", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    List<EmployeeResponse> employees = employeeRepository.records(user.tenantId(), null, storeId, null);
    int generated = 0;
    int skipped = 0;
    int errors = 0;
    List<SalaryGenerateReport.SalarySkipDetail> skipDetails = new java.util.ArrayList<>();
    for (EmployeeResponse employee : employees) {
      if ("离职".equals(employee.status())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "员工已离职"));
        continue;
      }
      if (employee.hireDate() != null && !employee.hireDate().isBlank()) {
        try {
          java.time.LocalDate hireDate = java.time.LocalDate.parse(employee.hireDate());
          YearMonth targetMonth = YearMonth.parse(month);
          if (hireDate.isAfter(targetMonth.atEndOfMonth())) {
            skipped++;
            skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "入职日期晚于" + month));
            continue;
          }
        } catch (Exception ignored) {
          // skip date parsing issues
        }
      }
      if (salaryRepository.recordForEmployeeMonth(user.tenantId(), employee.id(), month).isPresent()
          || salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, month, employee.name())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      try {
        SalaryRecordRequest row = generatedRecord(storeId, month, employee);
        salaryRepository.upsert(user.tenantId(), generatedId(month, employee.id()), row);
        generated++;
      } catch (Exception ex) {
        errors++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "生成失败：" + ex.getMessage()));
      }
    }
    salaryRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "salary_generate",
        storeId + "-" + month,
        storeId,
        month,
        "已生成 " + generated + " 条，跳过 " + skipped + " 条，异常 " + errors + " 条"
    );
    reconcileTodos(user, month);
    List<SalaryRecordResponse> records = salaryRepository.records(user.tenantId(), month, null, storeId);
    return new GenerateResult(records, new SalaryGenerateReport(generated, skipped, errors, skipDetails));
  }

  private record GenerateResult(List<SalaryRecordResponse> records, SalaryGenerateReport report) {}

  public SalaryGenerateReport previewGeneration(AuthUser user, String storeId, String month) {
    requireEditRole(user);
    String effectiveMonth = normalizeMonth(month);
    requireStoreScope(user, storeId);
    if (employeeRepository == null) {
      throw new BusinessException("EMPLOYEE_REPOSITORY_UNAVAILABLE", "Employee repository is not available", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    List<EmployeeResponse> employees = employeeRepository.records(user.tenantId(), null, storeId, null);
    int eligible = 0;
    int skipped = 0;
    int errors = 0;
    List<SalaryGenerateReport.SalarySkipDetail> skipDetails = new java.util.ArrayList<>();
    for (EmployeeResponse employee : employees) {
      if ("离职".equals(employee.status())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "员工已离职"));
        continue;
      }
      if (employee.hireDate() != null && !employee.hireDate().isBlank()) {
        try {
          java.time.LocalDate hireDate = java.time.LocalDate.parse(employee.hireDate());
          YearMonth targetMonth = YearMonth.parse(effectiveMonth);
          if (hireDate.isAfter(targetMonth.atEndOfMonth())) {
            skipped++;
            skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "入职日期晚于" + effectiveMonth));
            continue;
          }
        } catch (Exception ignored) {}
      }
      if (salaryRepository.recordForEmployeeMonth(user.tenantId(), employee.id(), effectiveMonth).isPresent()
          || salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, effectiveMonth, employee.name())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      eligible++;
    }
    return new SalaryGenerateReport(0, skipped, errors, skipDetails);
  }

  public SalaryPageResponse recordsPaged(AuthUser user, String month, Long brandId, String storeId, int page, int size) {
    requireReadRole(user);
    String targetMonth = normalizeMonth(month);
    if (accessControl != null) {
      String targetStoreId = blankToNull(storeId);
      if (targetStoreId != null) {
        accessControl.requireStoreAccess(user, DataScopeDomains.SALARY, targetStoreId, "查看工资数据");
      }
      DataScope dataScope = accessControl.dataScope(user, DataScopeDomains.SALARY);
      SalaryRepository.SalaryPageResult result = salaryRepository.page(
          user.tenantId(), targetMonth, brandId, targetStoreId, page, size, dataScope);
      SalarySummaryResponse summary = summaryFromRows(result.rows(), targetMonth);
      return new SalaryPageResponse(
          result.rows(), result.total(), result.page(), result.size(), result.totalPages(), summary);
    }
    if (isStoreManager(user)) {
      String scopedStoreId = requireManagerStore(user);
      if (storeId != null && !storeId.isBlank() && !scopedStoreId.equals(storeId.trim())) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
      SalaryRepository.SalaryPageResult result = salaryRepository.page(user.tenantId(), targetMonth, brandId, scopedStoreId, page, size);
      SalarySummaryResponse summary = summaryFromRows(result.rows(), targetMonth);
      return new SalaryPageResponse(result.rows(), result.total(), result.page(), result.size(), result.totalPages(), summary);
    }
    SalaryRepository.SalaryPageResult result = salaryRepository.page(user.tenantId(), targetMonth, brandId, blankToNull(storeId), page, size);
    SalarySummaryResponse summary = summaryFromRows(result.rows(), targetMonth);
    return new SalaryPageResponse(result.rows(), result.total(), result.page(), result.size(), result.totalPages(), summary);
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

  @Transactional
  public SalaryRecordResponse markPaid(AuthUser user, String id) {
    requirePayRole(user);
    SalaryRecordResponse record = requireRecord(user, id);
    if (!STATUS_APPROVED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有已审核的工资记录可以标记发放", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.markPaid(user.tenantId(), record.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_mark_paid", record.id(), record.storeId(), record.month(), "工资已发放");
    reconcileTodos(user, record.month());
    return requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse lockRecord(AuthUser user, String id) {
    requireEditRole(user);
    SalaryRecordResponse record = requireRecord(user, id);
    if (!List.of(STATUS_APPROVED, STATUS_PAID).contains(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有已审核或已发放的工资记录可以锁定", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.lockRecord(user.tenantId(), record.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_lock", record.id(), record.storeId(), record.month(), "工资已锁定");
    reconcileTodos(user, record.month());
    return requireRecord(user, record.id());
  }

  public String exportCsv(AuthUser user, String month, Long brandId, String storeId) {
    requireReadRole(user);
    List<SalaryRecordResponse> rows = records(user, month, brandId, storeId);
    StringBuilder csv = new StringBuilder();
    csv.append("工号,姓名,门店,品牌,岗位,月份,基本工资,社保补助,岗位工资,餐补,全勤,提成,加班工资,工龄工资,员工福利（生日）,深夜加班（元）,补贴,绩效,扣工服费,返工服费,应发工资,状态\n");
    for (SalaryRecordResponse row : rows) {
      csv.append(escapeCsv(row.employeeId())).append(",");
      csv.append(escapeCsv(row.employeeName())).append(",");
      csv.append(escapeCsv(row.storeName())).append(",");
      csv.append(escapeCsv(row.brandName())).append(",");
      csv.append(escapeCsv(row.position())).append(",");
      csv.append(row.month()).append(",");
      csv.append(row.base()).append(",");
      csv.append(row.social()).append(",");
      csv.append(row.post()).append(",");
      csv.append(row.meal()).append(",");
      csv.append(row.fullAttendance()).append(",");
      csv.append(row.commission()).append(",");
      csv.append(row.overtime()).append(",");
      csv.append(row.seniority()).append(",");
      csv.append(row.birthdayBenefit()).append(",");
      csv.append(row.lateNight()).append(",");
      csv.append(row.subsidy()).append(",");
      csv.append(row.performance()).append(",");
      csv.append(row.deductUniform()).append(",");
      csv.append(row.returnUniform()).append(",");
      csv.append(row.gross()).append(",");
      csv.append(statusLabel(row.status())).append("\n");
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_export", "csv-" + month, storeId == null ? "" : storeId, month, "导出工资CSV " + rows.size() + " 条");
    return csv.toString();
  }

  private String escapeCsv(String value) {
    if (value == null || value.isBlank()) return "";
    String escaped = value.replace("\"", "\"\"");
    return escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") ? "\"" + escaped + "\"" : escaped;
  }

  private String statusLabel(String status) {
    return switch (status == null ? "DRAFT" : status) {
      case "DRAFT" -> "草稿";
      case "SUBMITTED" -> "待审核";
      case "APPROVED" -> "已审核";
      case "REJECTED" -> "已驳回";
      case "PAID" -> "已发放";
      case "LOCKED" -> "已锁定";
      default -> status;
    };
  }

  public SalaryRecordResponse getRecord(AuthUser user, String id) {
    requireReadRole(user);
    return requireRecord(user, id);
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    requireEditRole(user);
    String targetId = requireText(id, "ID_REQUIRED", "Salary record id is required");
    SalaryRecordResponse existing = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Salary record not found", HttpStatus.NOT_FOUND));
    requireStoreScope(user, existing.storeId());
    requireEditableStatus(existing);
    salaryRepository.deleteItems(user.tenantId(), targetId);
    int deleted = salaryRepository.deleteEditable(user.tenantId(), targetId, existing.version());
    if (deleted == 0) {
      throw new BusinessException(
          "VERSION_CONFLICT",
          "工资记录状态或版本已变化，请刷新后重试",
          HttpStatus.CONFLICT
      );
    }
    salaryRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "salary_delete",
        targetId,
        existing.storeId(),
        existing.month(),
        "工资记录已删除"
    );
    reconcileTodos(user, existing.month());
  }

  @Transactional
  public SalaryRecordResponse submit(AuthUser user, String id) {
    requireEditRole(user);
    SalaryRecordResponse record = requireRecord(user, id);
    if (!List.of(STATUS_DRAFT, STATUS_REJECTED).contains(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有草稿或已驳回的工资记录可以提交审核", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.updateStatus(user.tenantId(), record.id(), STATUS_SUBMITTED, user.id(), null, record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_submit", record.id(), record.storeId(), record.month(), "工资记录已提交审核");
    reconcileTodos(user, record.month());
    return requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse approve(AuthUser user, String id) {
    requireReviewRole(user);
    SalaryRecordResponse record = requireRecord(user, id);
    requirePendingReview(record);
    int updated = salaryRepository.updateStatus(user.tenantId(), record.id(), STATUS_APPROVED, null, (Long) user.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_approve", record.id(), record.storeId(), record.month(), "工资记录已审核完成");
    reconcileTodos(user, record.month());
    return requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse reject(AuthUser user, String id, String note) {
    requireReviewRole(user);
    SalaryRecordResponse record = requireRecord(user, id);
    requirePendingReview(record);
    String reason = note == null || note.isBlank() ? "工资记录需要调整后重新提交" : note.trim();
    int updated = salaryRepository.updateStatusWithNote(user.tenantId(), record.id(), STATUS_REJECTED, user.id(), reason, record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_reject", record.id(), record.storeId(), record.month(), reason);
    reconcileTodos(user, record.month());
    return requireRecord(user, record.id());
  }

  private SalaryRecordRequest normalizeRequest(AuthUser user, SalaryRecordRequest request) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "工资记录不能为空", HttpStatus.BAD_REQUEST);
    }
    String storeId = requireText(request.storeId(), "STORE_REQUIRED", "请选择门店");
    EmployeeResponse employee = resolveEmployee(user, storeId, request.employeeId(), request.employeeName());
    return new SalaryRecordRequest(
        storeId,
        normalizeMonth(request.month()),
        employee == null ? blankToNull(request.employeeId()) : employee.id(),
        employee == null ? requireText(request.employeeName(), "EMPLOYEE_REQUIRED", "请选择员工") : employee.name(),
        employee == null ? request.position() : blankToNull(request.position()) == null ? employee.position() : request.position(),
        request.attendance(),
        request.gross(),
        request.normalHours(),
        request.otHours(),
        request.workHours(),
        request.vacationLeft(),
        request.vacationNote(),
        request.base(),
        request.social(),
        request.post(),
        request.meal(),
        request.fullAttendance(),
        request.commission(),
        request.overtime(),
        request.seniority(),
        request.birthdayBenefit(),
        request.lateNight(),
        request.subsidy(),
        request.performance(),
        request.deductUniform(),
        request.returnUniform()
    );
  }

  private SalaryRecordRequest generatedRecord(String storeId, String month, EmployeeResponse employee) {
    BigDecimal baseSalary = employee.baseSalary() == null ? ZERO : employee.baseSalary().setScale(2, RoundingMode.HALF_UP);
    return new SalaryRecordRequest(
        storeId,
        month,
        employee.id(),
        employee.name(),
        employee.position(),
        null,
        baseSalary,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        null,
        baseSalary,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO
    );
  }

  private String generatedId(String month, String employeeId) {
    return "SALGEN-" + month.replace("-", "") + "-" + employeeId;
  }

  private void requireReadRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryRead(user);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "No permission to read salary records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireEditRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryEdit(user);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "No permission to edit salary records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryReview(user);
      return;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "当前账号没有审核工资的权限", HttpStatus.FORBIDDEN);
    }
  }

  private SalaryRecordResponse requireRecord(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "工资记录编号不能为空");
    SalaryRecordResponse record = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "未找到工资记录", HttpStatus.NOT_FOUND));
    requireStoreScope(user, record.storeId());
    return record;
  }

  private void requireEditableStatus(SalaryRecordResponse record) {
    if (!STATUS_DRAFT.equals(record.status()) && !STATUS_REJECTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_LOCKED", "已提交审核或已完成的工资记录不能直接修改", HttpStatus.CONFLICT);
    }
  }

  private void requirePayRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryPay(user);
      return;
    }
    requireEditRole(user);
  }

  private void requirePendingReview(SalaryRecordResponse record) {
    if (!STATUS_SUBMITTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有待审核的工资记录可以审核", HttpStatus.CONFLICT);
    }
  }

  private EmployeeResponse resolveEmployee(AuthUser user, String storeId, String employeeId, String employeeName) {
    if (employeeRepository == null) {
      return null;
    }
    EmployeeResponse employee;
    String normalizedId = blankToNull(employeeId);
    if (normalizedId != null) {
      employee = employeeRepository.record(user.tenantId(), normalizedId)
          .orElseThrow(() -> new BusinessException("EMPLOYEE_NOT_FOUND", "员工不存在或不属于当前企业", HttpStatus.BAD_REQUEST));
    } else {
      String normalizedName = requireText(employeeName, "EMPLOYEE_REQUIRED", "请选择员工");
      List<EmployeeResponse> matches = employeeRepository.records(user.tenantId(), null, storeId, null).stream()
          .filter(row -> normalizedName.equals(row.name()))
          .toList();
      if (matches.size() != 1) {
        throw new BusinessException("EMPLOYEE_ID_REQUIRED", "请选择员工档案后再保存工资记录", HttpStatus.BAD_REQUEST);
      }
      employee = matches.getFirst();
    }
    if (!storeId.equals(employee.storeId())) {
      throw new BusinessException("EMPLOYEE_STORE_MISMATCH", "员工不属于当前门店", HttpStatus.BAD_REQUEST);
    }
    if ("离职".equals(employee.status())) {
      throw new BusinessException("EMPLOYEE_INACTIVE", "离职员工不能新增工资记录", HttpStatus.BAD_REQUEST);
    }
    return employee;
  }

  private void requireStoreScope(AuthUser user, String storeId) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.SALARY, storeId, "处理工资数据");
      return;
    }
    if (isStoreManager(user) && !requireManagerStore(user).equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
    }
  }

  private void reconcileTodos(AuthUser user, String month) {
    if (businessTodoService != null) {
      businessTodoService.reconcileAfterFinanceMutation(user, month);
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

  private String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "月份格式必须使用 YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeId(String id) {
    if (id != null && !id.isBlank()) {
      return id.trim();
    }
    return "SAL" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private BigDecimal sum(List<BigDecimal> values) {
    return values.stream()
        .map(value -> value == null ? BigDecimal.ZERO : value)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
