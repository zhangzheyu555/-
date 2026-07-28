package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarehouseReturnCreateControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehouseController controller = new WarehouseController(
      authService, warehouseService, mock(WarehousePrintService.class));
  private final AuthUser storeManager = new AuthUser(
      7L, 1L, "default", "manager-s1", "s1", "店长", "STORE_MANAGER", "s1", true);

  @Test
  void warehouseReturnCreateDelegatesToAuthenticatedService() {
    when(authService.requireUser("Bearer scoped-token")).thenReturn(storeManager);
    WarehouseReturnRequest request = new WarehouseReturnRequest(
        "s1", "REQ-1", null, "测试退货", null, "2026-07-28", List.of(), List.of());
    WarehouseReturnResponse saved = mock(WarehouseReturnResponse.class);
    when(warehouseService.createReturn(storeManager, request)).thenReturn(saved);

    var response = controller.createReturn("Bearer scoped-token", request);

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isSameAs(saved);
    verify(authService).requireUser("Bearer scoped-token");
    verify(warehouseService).createReturn(storeManager, request);
  }
}
