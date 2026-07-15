package com.storeprofit.system.operations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrainingVideoControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final TrainingVideoService service = mock(TrainingVideoService.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new TrainingVideoController(authService, service))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void unauthenticatedListReturns401BeforeService() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/exam-center/videos"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(service, never()).videos(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authenticatedListReturnsOnlyServiceVisibleRows() throws Exception {
    AuthUser user = new AuthUser(
        7L, 1L, "测试企业", "employee", "", "员工甲", "EMPLOYEE", "STORE_A", true);
    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(service.videos(user)).thenReturn(List.of());

    mockMvc.perform(get("/api/exam-center/videos").header("Authorization", "Bearer token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    verify(service).videos(user);
  }
}
