package com.storeprofit.system.salary;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.employee.EmployeeRepository;
import com.storeprofit.system.employee.EmployeeResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SalaryWorkflowServiceTest {
  private final SalaryRepository salaryRepository = mock(SalaryRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final BusinessTodoService businessTodoService = mock(BusinessTodoService.class);
  private final SalaryQueryService salaryQueryService = mock(SalaryQueryService.class);
  private final SalaryWorkflowService service = new SalaryWorkflowService(
      salaryRepository, employeeRepository, accessControl, businessTodoService, salaryQueryService);
  private final AuthUser boss = new AuthUser(1L, 1L, "默认企业", "boss", "", "老板", "BOSS", null, true);
  private final SalaryRecordRequest request = new SalaryRecordRequest(
      "store-1", "2026-05", "employee-1", "历史员工", "店员", "正常",
      new BigDecimal("3000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
      BigDecimal.ZERO, BigDecimal.ZERO
  );

  @BeforeEach
  void setUp() {
    EmployeeResponse inactive = new EmployeeResponse(
        "employee-1", "store-1", "S001", "测试门店", 1L, "测试品牌", "历史员工", "",
        "EMPLOYEE", "店员", "FULL_TIME", BigDecimal.ZERO, "离职", "2025-01-01", "");
    when(employeeRepository.record(1L, "employee-1")).thenReturn(Optional.of(inactive));
    when(salaryRepository.storeExists(1L, "store-1")).thenReturn(true);
    when(salaryRepository.record(1L, "LEGACY-1")).thenReturn(Optional.empty());
    when(salaryRepository.recordIdForEmployeeId(1L, "store-1", "2026-05", "employee-1"))
        .thenReturn(Optional.empty());
  }

  @Test
  void historicalImportAllowsInactiveEmployeeForLegacyIdOnly() {
    SalaryRecordResponse saved = mock(SalaryRecordResponse.class);
    when(salaryRepository.record(1L, "LEGACY-1"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(saved));

    service.importHistorical(boss, "LEGACY-1", request);

    verify(salaryRepository).upsert(eq(1L), eq("LEGACY-1"), any(SalaryRecordRequest.class));
  }

  @Test
  void historicalImportRejectsNonLegacyId() {
    assertThatThrownBy(() -> service.importHistorical(boss, "salary-1", request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("LEGACY-");

    verify(salaryRepository, never()).upsert(1L, "salary-1", request);
  }

  @Test
  void historicalImportDoesNotOverwriteExistingNonLegacySalary() {
    when(salaryRepository.recordIdForEmployeeId(1L, "store-1", "2026-05", "employee-1"))
        .thenReturn(Optional.of("salary-existing"));

    assertThatThrownBy(() -> service.importHistorical(boss, "LEGACY-1", request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不会覆盖");

    verify(salaryRepository, never()).upsert(1L, "LEGACY-1", request);
  }
}
