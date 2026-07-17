package com.storeprofit.system.employee;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.AssistantEntry;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.Profile;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.Store;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.WorkItem;
import com.storeprofit.system.employee.EmployeeWorkbenchResponse.WorkSummary;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmployeeWorkbenchControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final EmployeeWorkbenchService service = mock(EmployeeWorkbenchService.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new EmployeeWorkbenchController(authService, service))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @Test
  void unauthenticatedEmployeeWorkbenchReturns401() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/employee/workbench"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(authService).requireUser(null);
  }

  @Test
  void employeeCanReadOwnWorkbench() throws Exception {
    AuthUser employee = new AuthUser(8L, 1L, "测试租户", "rg1_emp_01", "hash",
        "RG1员工一", "EMPLOYEE", "rg1", true, 1L);
    when(authService.requireUser("Bearer employee-token")).thenReturn(employee);
    when(service.workbench(employee)).thenReturn(new EmployeeWorkbenchResponse(
        new Profile(8L, "RG1员工一", "EMPLOYEE"),
        new Store("rg1", "荆州之星店", "如果"),
        List.of(new WorkItem("exam-1", "EXAM", "门店服务规范考试", "待参加，截止 2026-07-18",
            "ASSIGNED", "NORMAL", "去考试", "/employee/exams?assignmentId=1")),
        new WorkSummary(1, 1, 0, 0, 0),
        new AssistantEntry(true, "READY", "员工服务助手可用", "/employee-assistant")
    ));

    mockMvc.perform(get("/api/employee/workbench")
            .header("Authorization", "Bearer employee-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.profile.userId").value(8))
        .andExpect(jsonPath("$.data.store.storeId").value("rg1"))
        .andExpect(jsonPath("$.data.workItems[0].type").value("EXAM"));

    verify(service).workbench(employee);
  }

  @Test
  void employeeCanReadOwnProfile() throws Exception {
    AuthUser employee = new AuthUser(8L, 1L, "测试租户", "rg1_emp_01", "hash",
        "RG1员工一", "EMPLOYEE", "rg1", true, 1L);
    when(authService.requireUser("Bearer employee-token")).thenReturn(employee);
    when(service.profile(employee)).thenReturn(new EmployeeProfileResponse(
        new EmployeeProfileResponse.Profile(8L, "rg1_emp_01", "RG1员工一", "EMPLOYEE"),
        new EmployeeProfileResponse.Store("rg1", "荆州之星店", "如果"),
        new EmployeeProfileResponse.Archive(true, "rg1_emp_01", "RG1员工一", "店员",
            "全职", "在职", "2026-07-01", BigDecimal.valueOf(3500), "已关联员工档案"),
        new EmployeeProfileResponse.Salary(false, null, null, "MISSING", "未生成",
            "rg1_emp_01", "RG1员工一", "店员", null, BigDecimal.valueOf(3500), null,
            null, null, null, null, null, null, null, null, null, null, "还没有生成工资记录"),
        List.of(new EmployeeProfileResponse.ChecklistItem(
            "salary", "工资记录未生成", "请财务生成本月工资或录入历史工资。", "待处理", "HIGH"))
    ));

    mockMvc.perform(get("/api/employee/profile")
            .header("Authorization", "Bearer employee-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.profile.username").value("rg1_emp_01"))
        .andExpect(jsonPath("$.data.archive.linked").value(true))
        .andExpect(jsonPath("$.data.salary.available").value(false))
        .andExpect(jsonPath("$.data.checklist[0].key").value("salary"));

    verify(service).profile(employee);
  }
}
