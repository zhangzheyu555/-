package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryGenerationService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final SalaryRepository salaryRepository;
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public SalaryGenerationService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.salaryRepository = salaryRepository;
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
  }

  public SalaryGenerationService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl
  ) {
    this(salaryRepository, employeeRepository, accessControl, null);
  }

  public SalaryGenerateReport previewGeneration(AuthUser user, String storeId, String month) {
    requireEditRole(user);
    storeId = resolveStoreForWrite(user, storeId, "预览生成工资");
    String effectiveMonth = SalaryQueryService.normalizeMonth(month);
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
      Preparation preparation = prepareSalary(user.tenantId(), storeId, effectiveMonth, employee);
      if (!preparation.missingItems().isEmpty()) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(
            employee.id(), employee.name(), "缺少" + String.join("、", preparation.missingItems())));
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
      if (salaryRepository.recordExistsForEmployeeId(user.tenantId(), storeId, effectiveMonth, employee.id())
          || salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, effectiveMonth, employee.name())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      eligible++;
    }
    return new SalaryGenerateReport(eligible, skipped, errors, skipDetails);
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
    String storeId = resolveStoreForWrite(user, request.storeId(), "生成工资");
    String month = SalaryQueryService.normalizeMonth(request.month());
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
      Preparation preparation = prepareSalary(user.tenantId(), storeId, month, employee);
      if (!preparation.missingItems().isEmpty()) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(
            employee.id(), employee.name(), "缺少" + String.join("、", preparation.missingItems())));
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
        }
      }
      if (salaryRepository.recordExistsForEmployeeId(user.tenantId(), storeId, month, employee.id())
          || salaryRepository.recordExistsForEmployee(user.tenantId(), storeId, month, employee.name())) {
        skipped++;
        skipDetails.add(new SalaryGenerateReport.SalarySkipDetail(employee.id(), employee.name(), "工资记录已存在"));
        continue;
      }
      SalaryRecordRequest row = generatedRecord(storeId, month, employee, preparation);
      String salaryId = generatedId(month, employee.id());
      salaryRepository.upsert(user.tenantId(), salaryId, row);
      saveCalculationSnapshot(user.tenantId(), salaryId, employee, preparation, row);
      generated++;
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
    List<SalaryRecordResponse> records = salaryRepository.records(user.tenantId(), month, null, storeId);
    return new GenerateResult(records, new SalaryGenerateReport(generated, skipped, errors, skipDetails));
  }

  private record GenerateResult(List<SalaryRecordResponse> records, SalaryGenerateReport report) {}

  static SalaryRecordRequest generatedRecord(String storeId, String month, EmployeeResponse employee, Preparation preparation) {
    SalaryRepository.SalaryProfileRow profile = preparation.profile();
    SalaryRepository.SalaryPolicyRow policy = preparation.policy();
    SalaryRepository.AttendanceRow attendance = preparation.attendance();
    BigDecimal baseSalary = profile.baseSalary().setScale(2, RoundingMode.HALF_UP);
    BigDecimal overtimeRate = profile.overtimeHourRate() != null
        ? profile.overtimeHourRate() : amountOrZero(policy.overtimeHourRate());
    BigDecimal overtimeAmount = attendance.overtimeHours().multiply(overtimeRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal gross = baseSalary.add(overtimeAmount).setScale(2, RoundingMode.HALF_UP);
    return new SalaryRecordRequest(
        storeId,
        month,
        employee.id(),
        employee.name(),
        employee.position(),
        attendance.attendanceDays().stripTrailingZeros().toPlainString(),
        gross,
        attendance.normalHours(),
        attendance.overtimeHours(),
        attendance.totalHours(),
        attendance.vacationBalance(),
        "考勤来源：" + attendance.source(),
        baseSalary,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        overtimeAmount,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        ZERO
    );
  }

  private Preparation prepareSalary(long tenantId, String storeId, String month, EmployeeResponse employee) {
    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
    if (employee.position() == null || employee.position().isBlank()) missing.add("岗位配置");
    SalaryRepository.SalaryProfileRow profile = salaryRepository.salaryProfile(tenantId, employee.id(), month).orElse(null);
    if (profile == null || profile.baseSalary() == null || profile.baseSalary().compareTo(BigDecimal.ZERO) <= 0) {
      missing.add("员工工资档案");
    }
    SalaryRepository.SalaryPolicyRow policy = profile == null ? null
        : salaryRepository.activePolicy(tenantId, profile.policyId(), month).orElse(null);
    if (policy == null) missing.add("有效工资政策");
    SalaryRepository.AttendanceRow attendance = salaryRepository.attendance(tenantId, storeId, employee.id(), month).orElse(null);
    if (attendance == null) missing.add("当月已确认考勤");
    return new Preparation(profile, policy, attendance, missing);
  }

  private void saveCalculationSnapshot(long tenantId, String salaryId, EmployeeResponse employee,
                                       Preparation preparation, SalaryRecordRequest row) {
    try {
      SalaryRepository.SalaryProfileRow profile = preparation.profile();
      SalaryRepository.SalaryPolicyRow policy = preparation.policy();
      SalaryRepository.AttendanceRow attendance = preparation.attendance();
      String policySnapshot = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
          "policyId", policy.id(), "policyName", policy.name(), "policyVersion", policy.version(),
          "employeeId", employee.id(), "baseSalary", profile.baseSalary(),
          "overtimeHourRate", profile.overtimeHourRate() != null ? profile.overtimeHourRate() : amountOrZero(policy.overtimeHourRate())
      ));
      String calculationSnapshot = OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
          "attendanceDays", attendance.attendanceDays(), "normalHours", attendance.normalHours(),
          "overtimeHours", attendance.overtimeHours(), "totalHours", attendance.totalHours(),
          "base", row.base(), "overtime", row.overtime(), "gross", row.gross(), "netPay", row.gross()
      ));
      salaryRepository.saveCalculationSnapshot(
          tenantId, salaryId, policy, policySnapshot, calculationSnapshot,
          row.base(), row.overtime(), row.gross());
    } catch (JsonProcessingException ex) {
      throw new BusinessException("SALARY_SNAPSHOT_FAILED", "工资计算快照生成失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private static BigDecimal amountOrZero(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  record Preparation(SalaryRepository.SalaryProfileRow profile, SalaryRepository.SalaryPolicyRow policy,
                     SalaryRepository.AttendanceRow attendance, List<String> missingItems) {}

  private static String generatedId(String month, String employeeId) {
    return "SALGEN-" + month.replace("-", "") + "-" + employeeId;
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

  private void requireStoreScope(AuthUser user, String storeId) {
    if (businessScopeResolver != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, null, "处理工资数据");
      return;
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.SALARY, storeId, "处理工资数据");
      return;
    }
    if ("STORE_MANAGER".equals(user.role())) {
      String scoped = user.storeId();
      if (scoped == null || scoped.isBlank()) {
        throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
      }
      if (!scoped.trim().equals(storeId)) {
        throw new BusinessException("FORBIDDEN", "店长只能查看所属门店的工资数据", HttpStatus.FORBIDDEN);
      }
    }
  }

  private String resolveStoreForWrite(AuthUser user, String storeId, String action) {
    if (businessScopeResolver != null) {
      String resolved = businessScopeResolver.resolve(
          user, DataScopeDomains.SALARY, storeId, null, action).storeId();
      return SalaryQueryService.requireText(resolved, "STORE_REQUIRED", "请选择门店");
    }
    String requested = SalaryQueryService.blankToNull(storeId);
    if (requested == null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      requested = SalaryQueryService.blankToNull(user.storeId());
    }
    return SalaryQueryService.requireText(requested, "STORE_REQUIRED", "请选择门店");
  }
}
