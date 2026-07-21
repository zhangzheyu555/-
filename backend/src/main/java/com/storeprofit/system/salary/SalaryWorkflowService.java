package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryWorkflowService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final String STATUS_DRAFT = "DRAFT";
  private static final String STATUS_SUBMITTED = "SUBMITTED";
  private static final String STATUS_APPROVED = "APPROVED";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_LOCKED = "LOCKED";
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");
  private static final BigDecimal MAX_HOURS = new BigDecimal("99999999.99");
  private final SalaryRepository salaryRepository;
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessTodoService businessTodoService;
  private final SalaryQueryService salaryQueryService;

  public SalaryWorkflowService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService,
      SalaryQueryService salaryQueryService
  ) {
    this.salaryRepository = salaryRepository;
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessTodoService = businessTodoService;
    this.salaryQueryService = salaryQueryService;
  }

  @Transactional
  public SalaryRecordResponse save(AuthUser user, String id, SalaryRecordRequest request) {
    requireEditRole(
        user,
        id,
        request == null ? null : request.storeId(),
        request == null ? null : request.month()
    );
    SalaryRecordRequest normalized = normalizeRequest(user, request, false);
    salaryQueryService.requireStoreScope(user, normalized.storeId());
    if (!salaryRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    salaryRepository.record(user.tenantId(), targetId).ifPresent(existing -> {
      salaryQueryService.requireStoreScope(user, existing.storeId());
      requireEditableStatus(existing);
    });
    salaryRepository.recordIdForEmployeeId(
        user.tenantId(), normalized.storeId(), normalized.month(), normalized.employeeId()
    ).filter(existingId -> !targetId.equals(existingId)).ifPresent(existingId -> {
      throw duplicateSalary();
    });
    try {
      salaryRepository.upsert(user.tenantId(), targetId, normalized);
    } catch (DuplicateKeyException ex) {
      // The pre-check gives a clear result for sequential retries. The unique key remains the
      // authority for concurrent creates, which must return the same business conflict.
      throw duplicateSalary();
    }
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
  public SalaryRecordResponse importHistorical(AuthUser user, String id, SalaryRecordRequest request) {
    requireBoss(user);
    String targetId = normalizeId(id);
    if (!targetId.startsWith("LEGACY-")) {
      throw new BusinessException(
          "HISTORY_IMPORT_ID_REQUIRED",
          "历史工资导入编号必须以 LEGACY- 开头",
          HttpStatus.BAD_REQUEST
      );
    }
    SalaryRecordRequest normalized = normalizeRequest(user, request, true);
    salaryQueryService.requireStoreScope(user, normalized.storeId());
    if (!salaryRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    salaryRepository.record(user.tenantId(), targetId).ifPresent(existing -> {
      salaryQueryService.requireStoreScope(user, existing.storeId());
      requireEditableStatus(existing);
      if (!normalized.storeId().equals(existing.storeId())
          || !normalized.month().equals(existing.month())
          || !normalized.employeeId().equals(existing.employeeId())) {
        throw new BusinessException(
            "HISTORY_IMPORT_IDENTITY_CONFLICT",
            "历史工资编号与已有员工、门店或月份不一致",
            HttpStatus.CONFLICT
        );
      }
    });
    salaryRepository.recordIdForEmployeeId(
        user.tenantId(), normalized.storeId(), normalized.month(), normalized.employeeId()
    ).filter(existingId -> !targetId.equals(existingId)).ifPresent(existingId -> {
      throw new BusinessException(
          "SALARY_ALREADY_EXISTS",
          "该员工当月已有工资记录，历史导入不会覆盖现有工资",
          HttpStatus.CONFLICT
      );
    });
    salaryRepository.upsert(user.tenantId(), targetId, normalized);
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_history_import", targetId,
        normalized.storeId(), normalized.month(), "已导入已核验的历史工资记录"
    );
    return salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("SAVE_FAILED", "工资记录导入失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    String targetId = SalaryQueryService.requireText(id, "ID_REQUIRED", "Salary record id is required");
    SalaryRecordResponse existing = salaryRepository.record(user.tenantId(), targetId).orElse(null);
    requireEditRole(
        user,
        targetId,
        existing == null ? null : existing.storeId(),
        existing == null ? null : existing.month()
    );
    if (existing == null) {
      throw new BusinessException("NOT_FOUND", "Salary record not found", HttpStatus.NOT_FOUND);
    }
    salaryQueryService.requireStoreScope(user, existing.storeId());
    requireEditableStatus(existing);
    int deleted = salaryRepository.delete(user.tenantId(), targetId);
    if (deleted == 0) {
      throw new BusinessException("NOT_FOUND", "Salary record not found", HttpStatus.NOT_FOUND);
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
    SalaryRecordResponse auditRecord = auditRecord(user, id);
    requireEditRole(user, id, auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month());
    SalaryRecordResponse record = salaryQueryService.requireRecord(user, id);
    if (!List.of(STATUS_DRAFT, STATUS_REJECTED).contains(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有草稿或已驳回的工资记录可以提交审核", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.updateStatus(user.tenantId(), record.id(), STATUS_SUBMITTED, user.id(), null, record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_submit", record.id(), record.storeId(), record.month(),
        "工资记录已提交审核", record.status(), STATUS_SUBMITTED);
    reconcileTodos(user, record.month());
    return salaryQueryService.requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse approve(AuthUser user, String id) {
    SalaryRecordResponse auditRecord = auditRecord(user, id);
    requireReviewRole(user, id, auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month());
    SalaryRecordResponse record = salaryQueryService.requireRecord(user, id);
    requirePendingReview(record);
    int updated = salaryRepository.updateStatus(user.tenantId(), record.id(), STATUS_APPROVED, null, (Long) user.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_approve", record.id(), record.storeId(), record.month(),
        "工资记录已审核完成", record.status(), STATUS_APPROVED);
    reconcileTodos(user, record.month());
    return salaryQueryService.requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse reject(AuthUser user, String id, String note) {
    SalaryRecordResponse auditRecord = auditRecord(user, id);
    requireReviewRole(user, id, auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month());
    SalaryRecordResponse record = salaryQueryService.requireRecord(user, id);
    requirePendingReview(record);
    String reason = note == null || note.isBlank() ? "工资记录需要调整后重新提交" : note.trim();
    int updated = salaryRepository.updateStatusWithNote(user.tenantId(), record.id(), STATUS_REJECTED, user.id(), reason, record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_reject", record.id(), record.storeId(), record.month(),
        reason, record.status(), STATUS_REJECTED);
    reconcileTodos(user, record.month());
    return salaryQueryService.requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse markPaid(AuthUser user, String id) {
    SalaryRecordResponse auditRecord = auditRecord(user, id);
    requirePayRole(user, id, auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month());
    SalaryRecordResponse record = salaryQueryService.requireRecord(user, id);
    if (!STATUS_APPROVED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有已审核的工资记录可以标记发放", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.markPaid(user.tenantId(), record.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_mark_paid", record.id(), record.storeId(), record.month(),
        "工资已发放", record.status(), STATUS_PAID);
    reconcileTodos(user, record.month());
    return salaryQueryService.requireRecord(user, record.id());
  }

  @Transactional
  public SalaryRecordResponse lockRecord(AuthUser user, String id) {
    SalaryRecordResponse auditRecord = auditRecord(user, id);
    requireEditRole(user, id, auditRecord == null ? null : auditRecord.storeId(),
        auditRecord == null ? null : auditRecord.month());
    SalaryRecordResponse record = salaryQueryService.requireRecord(user, id);
    if (!List.of(STATUS_APPROVED, STATUS_PAID).contains(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有已审核或已发放的工资记录可以锁定", HttpStatus.CONFLICT);
    }
    int updated = salaryRepository.lockRecord(user.tenantId(), record.id(), record.version());
    if (updated == 0) {
      throw new BusinessException("VERSION_CONFLICT", "工资记录已被其他用户修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_lock", record.id(), record.storeId(), record.month(),
        "工资已锁定", record.status(), STATUS_LOCKED);
    reconcileTodos(user, record.month());
    return salaryQueryService.requireRecord(user, record.id());
  }

  // === helpers ===

  private SalaryRecordRequest normalizeRequest(AuthUser user, SalaryRecordRequest request, boolean allowInactiveEmployee) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "工资记录不能为空", HttpStatus.BAD_REQUEST);
    }
    String storeId = salaryQueryService.resolveStoreForWrite(user, request.storeId(), "保存工资记录");
    if (storeId == null || storeId.isBlank()) {
      // Compatibility for focused tests that mock the query service. Production resolves manager
      // scope through BusinessScopeResolver before this fallback.
      storeId = SalaryQueryService.requireText(request.storeId(), "STORE_REQUIRED", "请选择门店");
    }
    EmployeeResponse employee = resolveEmployee(
        user, storeId, request.employeeId(), request.employeeName(), allowInactiveEmployee
    );
    BigDecimal base = nonNegativeAmount(request.base(), "基本工资");
    BigDecimal social = nonNegativeAmount(request.social(), "社保补助");
    BigDecimal post = nonNegativeAmount(request.post(), "岗位工资");
    BigDecimal meal = nonNegativeAmount(request.meal(), "餐补");
    BigDecimal fullAttendance = nonNegativeAmount(request.fullAttendance(), "全勤奖");
    BigDecimal commission = nonNegativeAmount(request.commission(), "提成");
    BigDecimal overtime = nonNegativeAmount(request.overtime(), "加班工资");
    BigDecimal seniority = nonNegativeAmount(request.seniority(), "工龄工资");
    BigDecimal lateNight = nonNegativeAmount(request.lateNight(), "深夜班补贴");
    BigDecimal subsidy = nonNegativeAmount(request.subsidy(), "补贴");
    BigDecimal performance = nonNegativeAmount(request.performance(), "绩效工资");
    BigDecimal deductUniform = nonNegativeAmount(request.deductUniform(), "扣工服费");
    BigDecimal returnUniform = nonNegativeAmount(request.returnUniform(), "返工服费");
    BigDecimal gross = nonNegativeAmount(request.gross(), "应发工资");
    BigDecimal normalHours = nonNegativeHours(request.normalHours(), "正常工时");
    BigDecimal otHours = nonNegativeHours(request.otHours(), "加班时长");
    BigDecimal workHours = nonNegativeHours(request.workHours(), "总工时");
    BigDecimal vacationLeft = boundedVacation(request.vacationLeft());

    if (!allowInactiveEmployee) {
      BigDecimal calculatedGross = base.add(social).add(post).add(meal).add(fullAttendance)
          .add(commission).add(overtime).add(seniority).add(lateNight).add(subsidy).add(performance)
          .subtract(deductUniform).subtract(returnUniform).setScale(2, RoundingMode.HALF_UP);
      if (calculatedGross.compareTo(ZERO) < 0) {
        throw new BusinessException("SALARY_DEDUCTION_EXCEEDS_GROSS", "扣款不能超过工资明细合计", HttpStatus.BAD_REQUEST);
      }
      if (gross.compareTo(calculatedGross) != 0) {
        throw new BusinessException("SALARY_GROSS_MISMATCH", "应发工资与工资明细合计不一致", HttpStatus.BAD_REQUEST);
      }
    }
    return new SalaryRecordRequest(
        storeId,
        SalaryQueryService.normalizeMonth(request.month()),
        employee == null ? SalaryQueryService.blankToNull(request.employeeId()) : employee.id(),
        employee == null ? SalaryQueryService.requireText(request.employeeName(), "EMPLOYEE_REQUIRED", "请选择员工") : employee.name(),
        employee == null ? request.position() : SalaryQueryService.blankToNull(request.position()) == null ? employee.position() : request.position(),
        request.attendance(),
        gross,
        normalHours,
        otHours,
        workHours,
        vacationLeft,
        request.vacationNote(),
        base,
        social,
        post,
        meal,
        fullAttendance,
        commission,
        overtime,
        seniority,
        lateNight,
        subsidy,
        performance,
        deductUniform,
        returnUniform
    );
  }

  private BigDecimal nonNegativeAmount(BigDecimal value, String field) {
    BigDecimal normalized = amount(value);
    if (normalized.compareTo(ZERO) < 0) {
      throw new BusinessException("SALARY_AMOUNT_NEGATIVE", field + "不能小于 0", HttpStatus.BAD_REQUEST);
    }
    if (normalized.compareTo(MAX_MONEY) > 0) {
      throw new BusinessException("SALARY_AMOUNT_OUT_OF_RANGE", field + "金额超出范围", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BigDecimal nonNegativeHours(BigDecimal value, String field) {
    BigDecimal normalized = amount(value);
    if (normalized.compareTo(ZERO) < 0) {
      throw new BusinessException("SALARY_HOURS_NEGATIVE", field + "不能小于 0", HttpStatus.BAD_REQUEST);
    }
    if (normalized.compareTo(MAX_HOURS) > 0) {
      throw new BusinessException("SALARY_HOURS_OUT_OF_RANGE", field + "超出范围", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BigDecimal boundedVacation(BigDecimal value) {
    BigDecimal normalized = amount(value);
    if (normalized.abs().compareTo(MAX_HOURS) > 0) {
      throw new BusinessException("SALARY_VACATION_OUT_OF_RANGE", "假期余额超出范围", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BusinessException duplicateSalary() {
    return new BusinessException("SALARY_ALREADY_EXISTS", "该员工本工资周期已有记录，请勿重复提交", HttpStatus.CONFLICT);
  }

  private EmployeeResponse resolveEmployee(
      AuthUser user, String storeId, String employeeId, String employeeName, boolean allowInactiveEmployee
  ) {
    if (employeeRepository == null) {
      return null;
    }
    EmployeeResponse employee;
    String normalizedId = SalaryQueryService.blankToNull(employeeId);
    if (normalizedId != null) {
      employee = employeeRepository.record(user.tenantId(), normalizedId)
          .orElseThrow(() -> new BusinessException("EMPLOYEE_NOT_FOUND", "员工不存在或不属于当前企业", HttpStatus.BAD_REQUEST));
    } else {
      String normalizedName = SalaryQueryService.requireText(employeeName, "EMPLOYEE_REQUIRED", "请选择员工");
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
    if (!allowInactiveEmployee && "离职".equals(employee.status())) {
      throw new BusinessException("EMPLOYEE_INACTIVE", "离职员工不能新增工资记录", HttpStatus.BAD_REQUEST);
    }
    return employee;
  }

  private void requireEditableStatus(SalaryRecordResponse record) {
    if (!STATUS_DRAFT.equals(record.status()) && !STATUS_REJECTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_LOCKED", "已提交审核或已完成的工资记录不能直接修改", HttpStatus.CONFLICT);
    }
  }

  /** Reads only enough tenant-scoped context to make a denied state change auditable. */
  private SalaryRecordResponse auditRecord(AuthUser user, String id) {
    String targetId = SalaryQueryService.requireText(id, "ID_REQUIRED", "工资记录编号不能为空");
    return salaryRepository.record(user.tenantId(), targetId).orElse(null);
  }

  private void requirePendingReview(SalaryRecordResponse record) {
    if (!STATUS_SUBMITTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有待审核的工资记录可以审核", HttpStatus.CONFLICT);
    }
  }

  private void requireEditRole(AuthUser user, String salaryId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireSalaryEdit(user, salaryId, storeId, month);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "No permission to edit salary records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireBoss(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireBoss(user, "导入历史工资数据");
      return;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "仅老板可以导入历史工资数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user, String salaryId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireSalaryReview(user, salaryId, storeId, month);
      return;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "当前账号没有审核工资的权限", HttpStatus.FORBIDDEN);
    }
  }

  private void requirePayRole(AuthUser user, String salaryId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireSalaryPay(user, salaryId, storeId, month);
      return;
    }
    requireEditRole(user, salaryId, storeId, month);
  }

  private void reconcileTodos(AuthUser user, String month) {
    if (businessTodoService != null) {
      businessTodoService.reconcileAfterFinanceMutation(user, month);
    }
  }

  private String normalizeId(String id) {
    if (id != null && !id.isBlank()) {
      return id.trim();
    }
    return "SAL" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
