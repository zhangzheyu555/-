package com.storeprofit.system.warehouse;

import static org.mockito.ArgumentMatchers.any;
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

class WarehouseTransferContextControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehousePrintService printService = mock(WarehousePrintService.class);
  private final WarehouseNetworkService networkService = mock(WarehouseNetworkService.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new WarehouseController(authService, warehouseService, printService, networkService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void unauthenticatedContextReturns401BeforeTheWorkbenchService() throws Exception {
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc.perform(get("/api/warehouse/transfers/context").param("warehouseId", "2"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verify(networkService, never()).transferContext(any(), any());
  }

  @Test
  void crossScopeWarehouseReturns403() throws Exception {
    AuthUser user = new AuthUser(
        7L, 1L, "测试企业", "regional-user", "", "山东仓库", "WAREHOUSE", null, true);
    when(authService.requireUser("Bearer regional-token")).thenReturn(user);
    when(networkService.transferContext(user, 1L)).thenThrow(
        new BusinessException("FORBIDDEN", "当前账号不能操作该仓库", HttpStatus.FORBIDDEN));

    mockMvc.perform(get("/api/warehouse/transfers/context")
            .header("Authorization", "Bearer regional-token")
            .param("warehouseId", "1"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(networkService).transferContext(user, 1L);
  }

  @Test
  void authenticatedContextUsesSelectedWarehouseAndReturnsServerContract() throws Exception {
    AuthUser user = new AuthUser(
        7L, 1L, "测试企业", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);
    WarehouseTransferContextResponse context = new WarehouseTransferContextResponse(
        new WarehouseTransferContextResponse.WarehouseRef(2L, "SD-REGIONAL", "山东分仓"),
        "REQUEST_REPLENISHMENT",
        "向上级总仓申请补货",
        List.of(),
        new WarehouseTransferContextResponse.Todos(1, 0, 0, 0, 0));
    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(networkService.transferContext(user, 2L)).thenReturn(context);

    mockMvc.perform(get("/api/warehouse/transfers/context")
            .header("Authorization", "Bearer token")
            .param("warehouseId", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.currentWarehouse.id").value(2))
        .andExpect(jsonPath("$.data.mode").value("REQUEST_REPLENISHMENT"))
        .andExpect(jsonPath("$.data.todos.draft").value(1));

    verify(authService).requireUser("Bearer token");
    verify(networkService).transferContext(user, 2L);
  }
}
