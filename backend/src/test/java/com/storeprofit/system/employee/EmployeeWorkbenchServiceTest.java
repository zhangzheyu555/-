package com.storeprofit.system.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.ExamCenterModels.ExamAssignmentResponse;
import com.storeprofit.system.operations.ExamCenterRepository;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.salary.SalaryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EmployeeWorkbenchServiceTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final ExamCenterRepository examCenterRepository = mock(ExamCenterRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final SalaryRepository salaryRepository = mock(SalaryRepository.class);
  private final EmployeeWorkbenchService service = new EmployeeWorkbenchService(
      accessControl, organizationRepository, examCenterRepository, employeeRepository, salaryRepository);

  @Test
  void employeeWorkbenchUsesCurrentEmployeeAndBoundStoreOnly() {
    AuthUser employee = user("EMPLOYEE", "rg1");
    when(organizationRepository.store(1L, "rg1")).thenReturn(Optional.of(store("rg1")));
    when(examCenterRepository.assignments(1L, "rg1", 8L)).thenReturn(List.of(
        assignment(1L, "rg1", "ASSIGNED", "待参加"),
        assignment(2L, "rg1", "COMPLETED", "已完成")
    ));
    when(accessControl.hasPermission(employee, PermissionCodes.EMPLOYEE_ASSISTANT_USE)).thenReturn(true);

    EmployeeWorkbenchResponse response = service.workbench(employee);

    assertThat(response.profile().userId()).isEqualTo(8L);
    assertThat(response.store().storeId()).isEqualTo("rg1");
    assertThat(response.store().storeName()).isEqualTo("荆州之星店");
    assertThat(response.workSummary().pending()).isEqualTo(1);
    assertThat(response.workSummary().completed()).isEqualTo(1);
    assertThat(response.workItems()).extracting(EmployeeWorkbenchResponse.WorkItem::type)
        .contains("EXAM", "ASSISTANT");
    verify(accessControl).requireEmployeeWorkbench(employee);
    verify(examCenterRepository).assignments(1L, "rg1", 8L);
  }

  @Test
  void employeeWithoutStoreGetsBusinessMessage() {
    AuthUser employee = user("EMPLOYEE", null);

    BusinessException error = catchThrowableOfType(
        () -> service.workbench(employee), BusinessException.class);

    assertThat(error.getCode()).isEqualTo("EMPLOYEE_STORE_REQUIRED");
    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.getMessage()).contains("绑定门店");
  }

  @Test
  void missingBoundStoreRecordGetsBusinessMessage() {
    AuthUser employee = user("EMPLOYEE", "rg-missing");
    when(organizationRepository.store(1L, "rg-missing")).thenReturn(Optional.empty());

    BusinessException error = catchThrowableOfType(
        () -> service.workbench(employee), BusinessException.class);

    assertThat(error.getCode()).isEqualTo("EMPLOYEE_STORE_NOT_FOUND");
    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void employeeProfileShowsArchiveAndSalaryMissingChecklist() {
    AuthUser employee = user("EMPLOYEE", "rg1");
    when(organizationRepository.store(1L, "rg1")).thenReturn(Optional.of(store("rg1")));
    when(employeeRepository.records(1L, null, "rg1", null)).thenReturn(List.of(
        new EmployeeResponse("rg1_emp_01", "rg1", "RG1", "荆州之星店", 1L, "如果",
            "RG1员工一", null, "店员", "店员", "全职", BigDecimal.valueOf(3500),
            "在职", "2026-07-01", null,
            null, null, null, null, null, null, null, null, null,
            8L, "rg1_emp_01", true, null)
    ));
    when(salaryRepository.latestEmployeeRecord(
        1L, "rg1", List.of("rg1_emp_01", "8"), "RG1员工一")).thenReturn(Optional.empty());
    when(examCenterRepository.assignments(1L, "rg1", 8L)).thenReturn(List.of());

    EmployeeProfileResponse response = service.profile(employee);

    assertThat(response.archive().linked()).isTrue();
    assertThat(response.archive().baseSalary()).isEqualByComparingTo("3500.00");
    assertThat(response.salary().available()).isFalse();
    assertThat(response.salary().message()).contains("还没有生成工资记录");
    assertThat(response.checklist()).extracting(EmployeeProfileResponse.ChecklistItem::key)
        .contains("salary", "exam");
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(8L, 1L, "测试租户", "rg1_emp_01", "hash", "RG1员工一",
        role, storeId, true, 1L);
  }

  private StoreResponse store(String storeId) {
    return new StoreResponse(storeId, "RG1", "荆州之星店", 1L, "如果", "荆州", "店长",
        "2026-01-01", "营业中", "");
  }

  private ExamAssignmentResponse assignment(long id, String storeId, String status, String label) {
    return new ExamAssignmentResponse(
        id,
        10L,
        20L,
        "门店服务规范考试",
        "服务规范试卷",
        8L,
        "RG1员工一",
        "EMPLOYEE",
        storeId,
        "荆州之星店",
        status,
        label,
        "2026-07-17 09:00:00",
        "2026-07-18 18:00:00",
        "COMPLETED".equals(status) ? "2026-07-17 10:00:00" : null,
        "COMPLETED".equals(status) ? 100L : null,
        "COMPLETED".equals(status) ? BigDecimal.valueOf(95) : null,
        "COMPLETED".equals(status) ? Boolean.TRUE : null,
        null
    );
  }
}
