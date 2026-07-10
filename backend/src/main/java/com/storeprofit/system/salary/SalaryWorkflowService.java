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
    SalaryRecordRequest normalized = normalizeRequest(user, request);
    salaryQueryService.requireStoreScope(user, normalized.storeId());
    if (!salaryRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    salaryRepository.record(user.tenantId(), targetId).ifPresent(existing -> {
      salaryQueryService.requireStoreScope(user, existing.storeId());
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
  public void delete(AuthUser user, String id) {
    requireEditRole(user);
    String targetId = SalaryQueryService.requireText(id, "ID_REQUIRED", "Salary record id is required");
    SalaryRecordResponse existing = salaryRepository.record(user.tenantId(), targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Salary record not found", HttpStatus.NOT_FOUND));
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
    requireEditRole(user);
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

  private SalaryRecordRequest normalizeRequest(AuthUser user, SalaryRecordRequest request) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "工资记录不能为空", HttpStatus.BAD_REQUEST);
    }
    String storeId = SalaryQueryService.requireText(request.storeId(), "STORE_REQUIRED", "请选择门店");
    EmployeeResponse employee = resolveEmployee(user, storeId, request.employeeId(), request.employeeName());
    return new SalaryRecordRequest(
        storeId,
        SalaryQueryService.normalizeMonth(request.month()),
        employee == null ? SalaryQueryService.blankToNull(request.employeeId()) : employee.id(),
        employee == null ? SalaryQueryService.requireText(request.employeeName(), "EMPLOYEE_REQUIRED", "请选择员工") : employee.name(),
        employee == null ? request.position() : SalaryQueryService.blankToNull(request.position()) == null ? employee.position() : request.position(),
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
        request.lateNight(),
        request.subsidy(),
        request.performance(),
        request.deductUniform(),
        request.returnUniform()
    );
  }

  private EmployeeResponse resolveEmployee(AuthUser user, String storeId, String employeeId, String employeeName) {
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
    if ("离职".equals(employee.status())) {
      throw new BusinessException("EMPLOYEE_INACTIVE", "离职员工不能新增工资记录", HttpStatus.BAD_REQUEST);
    }
    return employee;
  }

  private void requireEditableStatus(SalaryRecordResponse record) {
    if (STATUS_APPROVED.equals(record.status()) || STATUS_SUBMITTED.equals(record.status())) {
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
    if (!List.of("ADMIN", "BOSS", "FINANCE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to edit salary records", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireSalaryReview(user);
      return;
    }
    if (!List.of("ADMIN", "BOSS").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "当前账号没有审核工资的权限", HttpStatus.FORBIDDEN);
    }
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
