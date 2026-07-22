package com.storeprofit.system.employee;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EmployeeControllerAuthorizationTest {
  private final AuthService authService = mock(AuthService.class);
  private final EmployeeService employeeService = mock(EmployeeService.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new EmployeeController(authService, employeeService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @Test
  void anonymousEmployeeCreationIsRejectedWith401BeforeAnyWrite() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(post("/api/employees")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"storeId\":\"EMP_A\",\"name\":\"匿名员工\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(authService).requireUser(null);
  }
}
