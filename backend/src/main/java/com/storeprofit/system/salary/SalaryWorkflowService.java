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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
    requireEditRole(user);
    SalaryRecordRequest normalized = normalizeRequest(user, request, false);
    salaryQueryService.requireStoreScope(user, normalized.storeId());
    if (!salaryRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    Optional<SalaryRecordResponse> existingRecord = salaryRepository.record(user.tenantId(), targetId);
    existingRecord.ifPresent(existing -> {
      salaryQueryService.requireStoreScope(user, existing.storeId());
      requireEditableStatus(existing);
    });
    salaryRepository.recordForEmployeeMonth(user.tenantId(), normalized.employeeId(), normalized.month())
        .filter(existing -> !targetId.equals(existing.id()))
        .ifPresent(existing -> {
          throw new BusinessException(
              "SALARY_ALREADY_EXISTS",
              "该员工本月已在其他工资名单，不能重复添加",
              HttpStatus.CONFLICT
          );
        });
    SalaryRecordRequest persisted = existingRecord
        .map(existing -> preserveUnallocatedGrossDifference(existing, normalized))
        .orElse(normalized);
    try {
      salaryRepository.upsert(user.tenantId(), targetId, persisted);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(
          "SALARY_ALREADY_EXISTS",
          "该员工本月已有工资记录，请刷新后查看",
          HttpStatus.CONFLICT
      );
    }
    salaryRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "salary_save",
        targetId,
        persisted.storeId(),
        persisted.month(),
        "工资记录已保存"
    );
    reconcileTodos(user, persisted.month());
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
  public SalaryRepository.AttendanceRow saveAttendance(AuthUser user, SalaryAttendanceRequest request) {
    requireEditRole(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "考勤记录不能为空", HttpStatus.BAD_REQUEST);
    }
    String storeId = salaryQueryService.resolveStoreForWrite(user, request.storeId(), "录入工资考勤");
    if (storeId == null || storeId.isBlank()) {
      storeId = SalaryQueryService.requireText(request.storeId(), "STORE_REQUIRED", "请选择门店");
    }
    salaryQueryService.requireStoreScope(user, storeId);
    String employeeId = SalaryQueryService.requireText(request.employeeId(), "EMPLOYEE_REQUIRED", "请选择员工");
    String month = SalaryQueryService.normalizeMonth(request.month());
    boolean assignedToTargetStore = salaryRepository.recordExistsForEmployeeId(
        user.tenantId(), storeId, month, employeeId);
    EmployeeResponse employee = resolveEmployee(
        user, storeId, employeeId, null, false, assignedToTargetStore);
    boolean hourlyEmployee = SalaryGenerationService.isHourlyEmployee(
        employee.employmentType(), employee.position());
    BigDecimal days = request.attendanceDays().setScale(2, RoundingMode.HALF_UP);
    BigDecimal overtimeHours = request.overtimeHours().setScale(2, RoundingMode.HALF_UP);
    if (!hourlyEmployee && days.compareTo(new BigDecimal("31")) > 0) {
      throw new BusinessException("ATTENDANCE_DAYS_INVALID", "出勤天数不能超过31天", HttpStatus.BAD_REQUEST);
    }
    if (overtimeHours.compareTo(new BigDecimal("300")) > 0) {
      throw new BusinessException("OVERTIME_HOURS_INVALID", "加班小时不能超过300小时", HttpStatus.BAD_REQUEST);
    }
    BigDecimal normalHours = hourlyEmployee && request.normalHours() != null
        ? request.normalHours().setScale(2, RoundingMode.HALF_UP)
        : days.multiply(new BigDecimal("8")).setScale(2, RoundingMode.HALF_UP);
    if (normalHours.compareTo(new BigDecimal("744")) > 0) {
      throw new BusinessException("NORMAL_HOURS_INVALID", "正常工时不能超过744小时", HttpStatus.BAD_REQUEST);
    }
    BigDecimal totalHours = normalHours.add(overtimeHours).setScale(2, RoundingMode.HALF_UP);
    salaryRepository.upsertAttendance(
        user.tenantId(), user.id(), storeId, employee.id(), month,
        days, normalHours, overtimeHours, totalHours);
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_attendance_save",
        employee.id() + "-" + month, storeId, month,
        (hourlyEmployee ? "录入计时员工正常工时" : "录入出勤" + days.stripTrailingZeros().toPlainString() + "天，正常工时")
            + normalHours.stripTrailingZeros().toPlainString() + "小时，加班"
            + overtimeHours.stripTrailingZeros().toPlainString() + "小时，总工时"
            + totalHours.stripTrailingZeros().toPlainString() + "小时");
    return salaryRepository.attendance(user.tenantId(), storeId, employee.id(), month)
        .orElseThrow(() -> new BusinessException("SAVE_FAILED", "考勤保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  public List<SalaryAssignmentCandidate> assignmentCandidates(AuthUser user, String requestedStoreId, String requestedMonth) {
    requireEditRole(user);
    String storeId = salaryQueryService.resolveStoreForWrite(user, requestedStoreId, "查看工资添加人员");
    salaryQueryService.requireStoreScope(user, storeId);
    String month = SalaryQueryService.normalizeMonth(requestedMonth);
    DataScope salaryScope = accessControl == null
        ? null
        : accessControl.dataScope(user, DataScopeDomains.SALARY);

    return employeeRepository.records(user.tenantId(), null, null, null).stream()
        .filter(employee -> !storeId.equals(employee.storeId()))
        .filter(employee -> !isInactiveEmployee(employee))
        .filter(employee -> salaryScope == null || salaryScope.allowsStore(employee.storeId()))
        .filter(employee -> salaryRepository.recordForEmployeeMonth(user.tenantId(), employee.id(), month).isEmpty())
        .map(employee -> new SalaryAssignmentCandidate(
            employee.id(),
            employee.name(),
            employee.position(),
            employee.storeId(),
            employee.storeName() == null || employee.storeName().isBlank()
                ? employee.storeId()
                : employee.storeName()
        ))
        .sorted(Comparator
            .comparing(SalaryAssignmentCandidate::sourceStoreName, Comparator.nullsLast(String::compareTo))
            .thenComparing(SalaryAssignmentCandidate::position, Comparator.nullsLast(String::compareTo))
            .thenComparing(SalaryAssignmentCandidate::employeeName))
        .toList();
  }

  @Transactional
  public SalaryRecordResponse assignEmployee(AuthUser user, SalaryAssignmentRequest request) {
    requireEditRole(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "添加人员信息不能为空", HttpStatus.BAD_REQUEST);
    }
    String storeId = salaryQueryService.resolveStoreForWrite(user, request.storeId(), "添加员工到工资名单");
    salaryQueryService.requireStoreScope(user, storeId);
    String month = SalaryQueryService.normalizeMonth(request.month());
    if (!salaryRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }

    String employeeId = SalaryQueryService.requireText(request.employeeId(), "EMPLOYEE_REQUIRED", "请选择员工");
    EmployeeResponse employee = employeeRepository.record(user.tenantId(), employeeId)
        .orElseThrow(() -> new BusinessException(
            "EMPLOYEE_NOT_FOUND", "员工不存在或不属于当前企业", HttpStatus.BAD_REQUEST));
    if (isInactiveEmployee(employee)) {
      throw new BusinessException("EMPLOYEE_INACTIVE", "离职或停用员工不能加入工资名单", HttpStatus.BAD_REQUEST);
    }
    if (storeId.equals(employee.storeId())) {
      throw new BusinessException("EMPLOYEE_ALREADY_IN_STORE", "该员工已在当前门店工资名单中", HttpStatus.CONFLICT);
    }
    salaryQueryService.requireStoreScope(user, employee.storeId());
    salaryRepository.recordForEmployeeMonth(user.tenantId(), employee.id(), month).ifPresent(existing -> {
      throw new BusinessException(
          "SALARY_ALREADY_EXISTS",
          "该员工本月已有工资记录，不能重复添加",
          HttpStatus.CONFLICT
      );
    });

    BigDecimal baseSalary = salaryRepository.salaryProfile(user.tenantId(), employee.id(), month)
        .map(SalaryRepository.SalaryProfileRow::baseSalary)
        .orElse(employee.baseSalary());
    baseSalary = baseSalary == null ? ZERO : baseSalary.setScale(2, RoundingMode.HALF_UP);
    BigDecimal seniority = SalaryGenerationService.eligibleForSalaryBenefits(employee)
        ? SalaryGenerationService.seniorityPay(employee, month)
        : ZERO;
    BigDecimal birthdayBenefit = SalaryGenerationService.birthdayBenefit(employee, month);
    BigDecimal gross = baseSalary.add(seniority).add(birthdayBenefit).setScale(2, RoundingMode.HALF_UP);
    SalaryRecordRequest salary = new SalaryRecordRequest(
        storeId, month, employee.id(), employee.name(), employee.position(), null,
        gross, ZERO, ZERO, ZERO, ZERO, null,
        baseSalary, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, seniority, birthdayBenefit,
        ZERO, ZERO, ZERO, ZERO, ZERO
    );
    String salaryId = "SALADD-" + month.replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 12);
    try {
      salaryRepository.upsert(user.tenantId(), salaryId, salary);
    } catch (DuplicateKeyException ex) {
      throw new BusinessException(
          "SALARY_ALREADY_EXISTS",
          "该员工本月已有工资记录，请刷新后查看",
          HttpStatus.CONFLICT
      );
    }
    salaryRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "salary_employee_assign", salaryId,
        storeId, month,
        "从" + displayStore(employee) + "添加员工" + employee.name()
            + "到当月工资名单，岗位保持为" + displayPosition(employee.position())
    );
    reconcileTodos(user, month);
    return salaryQueryService.requireRecord(user, salaryId);
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    requireEditRole(user);
    String targetId = SalaryQueryService.requireText(id, "ID_REQUIRED", "Salary record id is required");
    SalaryRecordResponse existing = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Salary record not found", HttpStatus.NOT_FOUND));
    salaryQueryService.requireStoreScope(user, existing.storeId());
    requireEditableStatus(existing);
    // salary_record_item 对主表存在外键，必须在同一事务中先删除。
    // 若主记录状态或版本已并发改变，下方异常会使分项删除一并回滚。
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
    requireReviewRole(user);
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
    requireReviewRole(user);
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
    requirePayRole(user);
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
    requireEditRole(user);
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

  /**
   * 保底补足与历史差额只存在 gross 中，没有独立明细列。
   * 编辑工龄、深夜加班等分项时，以原 gross 加分项变化量计算，
   * 避免把已生成的保底补足折损或重复叠加。
   */
  private SalaryRecordRequest preserveUnallocatedGrossDifference(
      SalaryRecordResponse existing,
      SalaryRecordRequest request
  ) {
    BigDecimal componentDelta = componentTotal(request).subtract(componentTotal(existing));
    BigDecimal gross = amount(existing.gross()).add(componentDelta).setScale(2, RoundingMode.HALF_UP);
    return new SalaryRecordRequest(
        request.storeId(),
        request.month(),
        request.employeeId(),
        request.employeeName(),
        request.position(),
        request.attendance(),
        gross,
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

  private BigDecimal componentTotal(SalaryRecordRequest row) {
    return amount(row.base()).add(amount(row.social())).add(amount(row.post())).add(amount(row.meal()))
        .add(amount(row.fullAttendance())).add(amount(row.commission())).add(amount(row.overtime()))
        .add(amount(row.seniority())).add(amount(row.birthdayBenefit())).add(amount(row.lateNight()))
        .add(amount(row.subsidy())).add(amount(row.performance()))
        .subtract(amount(row.deductUniform())).subtract(amount(row.returnUniform()));
  }

  private BigDecimal componentTotal(SalaryRecordResponse row) {
    return amount(row.base()).add(amount(row.social())).add(amount(row.post())).add(amount(row.meal()))
        .add(amount(row.fullAttendance())).add(amount(row.commission())).add(amount(row.overtime()))
        .add(amount(row.seniority())).add(amount(row.birthdayBenefit())).add(amount(row.lateNight()))
        .add(amount(row.subsidy())).add(amount(row.performance()))
        .subtract(amount(row.deductUniform())).subtract(amount(row.returnUniform()));
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

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
    String month = SalaryQueryService.normalizeMonth(request.month());
    boolean assignedToTargetStore = SalaryQueryService.blankToNull(request.employeeId()) != null
        && salaryRepository.recordExistsForEmployeeId(
            user.tenantId(), storeId, month, request.employeeId().trim());
    EmployeeResponse employee = resolveEmployee(
        user, storeId, request.employeeId(), request.employeeName(), allowInactiveEmployee, assignedToTargetStore
    );
    return new SalaryRecordRequest(
        storeId,
        month,
        employee == null ? SalaryQueryService.blankToNull(request.employeeId()) : employee.id(),
        employee == null ? SalaryQueryService.requireText(request.employeeName(), "EMPLOYEE_REQUIRED", "请选择员工") : employee.name(),
        employee == null
            ? request.position()
            : !storeId.equals(employee.storeId())
                ? employee.position()
                : SalaryQueryService.blankToNull(request.position()) == null ? employee.position() : request.position(),
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

  private EmployeeResponse resolveEmployee(
      AuthUser user,
      String storeId,
      String employeeId,
      String employeeName,
      boolean allowInactiveEmployee,
      boolean allowAssignedStore
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
      if (!allowAssignedStore) {
        throw new BusinessException("EMPLOYEE_STORE_MISMATCH", "员工尚未加入当前门店当月工资名单", HttpStatus.BAD_REQUEST);
      }
      salaryQueryService.requireStoreScope(user, employee.storeId());
    }
    if (!allowInactiveEmployee && isInactiveEmployee(employee)) {
      throw new BusinessException("EMPLOYEE_INACTIVE", "离职员工不能新增工资记录", HttpStatus.BAD_REQUEST);
    }
    return employee;
  }

  private boolean isInactiveEmployee(EmployeeResponse employee) {
    String status = employee == null || employee.status() == null
        ? ""
        : employee.status().trim().toUpperCase(Locale.ROOT);
    return List.of("离职", "停用", "删除", "INACTIVE", "DELETED").contains(status);
  }

  private String displayStore(EmployeeResponse employee) {
    return employee.storeName() == null || employee.storeName().isBlank()
        ? employee.storeId()
        : employee.storeName();
  }

  private String displayPosition(String position) {
    return position == null || position.isBlank() ? "未设置岗位" : position.trim();
  }

  private void requireEditableStatus(SalaryRecordResponse record) {
    if (!STATUS_DRAFT.equals(record.status()) && !STATUS_REJECTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_LOCKED", "已提交审核或已完成的工资记录不能直接修改", HttpStatus.CONFLICT);
    }
  }

  private void requirePendingReview(SalaryRecordResponse record) {
    if (!STATUS_SUBMITTED.equals(record.status())) {
      throw new BusinessException("SALARY_STATUS_INVALID", "只有待审核的工资记录可以审核", HttpStatus.CONFLICT);
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

  private void requireBoss(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireBoss(user, "导入历史工资数据");
      return;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "仅老板可以导入历史工资数据", HttpStatus.FORBIDDEN);
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

  private void requirePayRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryPay(user);
      return;
    }
    requireEditRole(user);
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
