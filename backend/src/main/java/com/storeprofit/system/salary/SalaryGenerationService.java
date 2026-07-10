package com.storeprofit.system.salary;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryGenerationService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final SalaryRepository salaryRepository;
  private final EmployeeRepository employeeRepository;
  private final AccessControlService accessControl;

  public SalaryGenerationService(
      SalaryRepository salaryRepository,
      EmployeeRepository employeeRepository,
      AccessControlService accessControl
  ) {
    this.salaryRepository = salaryRepository;
    this.employeeRepository = employeeRepository;
    this.accessControl = accessControl;
  }

  public SalaryGenerateReport previewGeneration(AuthUser user, String storeId, String month) {
    requireEditRole(user);
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
    String storeId = SalaryQueryService.requireText(request.storeId(), "STORE_REQUIRED", "Store is required");
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
    List<SalaryRecordResponse> records = salaryRepository.records(user.tenantId(), month, null, storeId);
    return new GenerateResult(records, new SalaryGenerateReport(generated, skipped, errors, skipDetails));
  }

  private record GenerateResult(List<SalaryRecordResponse> records, SalaryGenerateReport report) {}

  static SalaryRecordRequest generatedRecord(String storeId, String month, EmployeeResponse employee) {
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
        ZERO
    );
  }

  private static String generatedId(String month, String employeeId) {
    return "SALGEN-" + month.replace("-", "") + "-" + employeeId;
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

  private void requireStoreScope(AuthUser user, String storeId) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, storeId, "处理工资数据");
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
}
