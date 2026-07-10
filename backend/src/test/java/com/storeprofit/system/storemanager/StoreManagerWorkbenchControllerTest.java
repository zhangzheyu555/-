package com.storeprofit.system.storemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storemanager.StoreManagerInspectionPageResponse.StoreManagerInspectionSummary;
import com.storeprofit.system.storemanager.StoreManagerWorkbenchResponse.StoreScope;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreManagerWorkbenchControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final StoreManagerWorkbenchService workbenchService = mock(StoreManagerWorkbenchService.class);
  private final StoreManagerWorkbenchController controller = new StoreManagerWorkbenchController(authService, workbenchService);
  private final AuthUser storeManager = new AuthUser(1L, 1L, "default", "rg1", "", "荆州之星店店长", "STORE_MANAGER", "rg1", true);

  @Test
  void inspectionsUsesAuthenticatedStoreManagerAndWrapsResponse() {
    StoreManagerInspectionPageResponse response = new StoreManagerInspectionPageResponse(
        "店长",
        "MySQL inspection_record / 当前店长绑定门店",
        "2026-07-08T12:00:00+08:00",
        new StoreScope("rg1", "荆州之星店"),
        new StoreManagerInspectionSummary(1, 1, new BigDecimal("82.00"), 0),
        List.of()
    );
    when(authService.requireUser("Bearer token")).thenReturn(storeManager);
    when(workbenchService.inspections(storeManager)).thenReturn(response);

    ApiResponse<StoreManagerInspectionPageResponse> result = controller.inspections("Bearer token");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(workbenchService).inspections(storeManager);
  }
}
