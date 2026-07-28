package com.storeprofit.system.warehouse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseReturnScopeControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehousePrintService printService = mock(WarehousePrintService.class);
  private final WarehouseNetworkService networkService = mock(WarehouseNetworkService.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new WarehouseController(
          authService, warehouseService, printService, networkService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void selectedWarehouseIsPassedToReturnScopeBoundary() throws Exception {
    AuthUser warehouse = new AuthUser(
        8L, 1L, "测试企业", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);
    when(authService.requireUser("Bearer warehouse-token")).thenReturn(warehouse);
    when(warehouseService.returns(warehouse, 2L)).thenReturn(List.of());

    mockMvc.perform(get("/api/warehouse/returns")
            .header("Authorization", "Bearer warehouse-token")
            .param("warehouseId", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());

    verify(warehouseService).returns(warehouse, 2L);
  }

  @Test
  void omittedWarehouseKeepsTheCompatibleVisibleFacilityQuery() throws Exception {
    AuthUser warehouse = new AuthUser(
        8L, 1L, "测试企业", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);
    when(authService.requireUser("Bearer warehouse-token")).thenReturn(warehouse);
    when(warehouseService.returns(warehouse, (Long) null)).thenReturn(List.of());

    mockMvc.perform(get("/api/warehouse/returns")
            .header("Authorization", "Bearer warehouse-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());

    verify(warehouseService).returns(warehouse, (Long) null);
  }
}
