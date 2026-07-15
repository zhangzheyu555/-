package com.storeprofit.system.warehouse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.common.RequestIdFilter;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseReturnPdfControllerAuthorizationTest {
  private final AuthService authService = mock(AuthService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehousePrintService printService = mock(WarehousePrintService.class);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
      new WarehouseController(authService, warehouseService, printService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .addFilters(new RequestIdFilter())
      .build();

  @Test
  void unauthenticatedReturnPdfRequestReturns401BeforePrintServiceRuns() throws Exception {
    when(authService.requireUser(null)).thenThrow(new BusinessException(
        "UNAUTHORIZED", "请先登录后再访问配送退货单", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/warehouse/print/returns/{returnId}", "PSTH1"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(printService);
  }

  @Test
  void crossStoreReturnPdfRequestReturns403() throws Exception {
    AuthUser storeManager = new AuthUser(
        3L, 1L, "default", "rg1", "", "店长", "STORE_MANAGER", "rg1", true);
    when(authService.requireUser("Bearer store-token")).thenReturn(storeManager);
    when(printService.returnPdf(storeManager, "PSTH-OTHER")).thenThrow(new BusinessException(
        "FORBIDDEN", "无权下载该配送退货单", HttpStatus.FORBIDDEN));

    mockMvc.perform(get("/api/warehouse/print/returns/{returnId}", "PSTH-OTHER")
            .header("Authorization", "Bearer store-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(printService).returnPdf(storeManager, "PSTH-OTHER");
  }
}
