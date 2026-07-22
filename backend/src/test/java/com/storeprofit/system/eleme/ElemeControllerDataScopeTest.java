package com.storeprofit.system.eleme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.integration.PlatformAdapterStatus;
import com.storeprofit.system.platform.integration.PlatformSyncState;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElemeControllerDataScopeTest {
  private final ElemeProperties properties = new ElemeProperties();
  private final ElemeOrderService orderService = mock(ElemeOrderService.class);
  private final ElemePlatformAdapter platformAdapter = mock(ElemePlatformAdapter.class);
  private final ElemeWebhookService webhookService = mock(ElemeWebhookService.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuthUser user = new AuthUser(
      7L, 1L, "tenant", "supervisor", "", "督导", "SUPERVISOR", "store-a", true);
  private final ElemeSummaryResponse emptySummary = new ElemeSummaryResponse(
      "UNCONFIGURED", "未配置", 7, "2026-07-11 12:00:00",
      BigDecimal.ZERO, BigDecimal.ZERO, 0L, List.of());
  private ElemeController controller;

  @BeforeEach
  void setUp() {
    properties.setShops(List.of(
        "legacy-shop:旧配置门店",
        "shop-a:甲店:store-a",
        "shop-b:乙店:store-b"
    ));
    when(accessControl.requireUser("Bearer token")).thenReturn(user);
    when(platformAdapter.status()).thenReturn(new PlatformAdapterStatus(
        "ELEME", PlatformSyncState.NOT_CONFIGURED,
        PlatformSyncState.NOT_CONFIGURED, "未配置"));
    controller = new ElemeController(
        properties, orderService, platformAdapter, webhookService, accessControl);
  }

  @Test
  void statusCountsAllEntriesForAllScope() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(DataScope.all());

    ApiResponse<java.util.Map<String, Object>> response = controller.status("Bearer token");

    assertThat(response.data().get("shopCount")).isEqualTo(3);
    verify(accessControl).requirePlatformRead(user);
  }

  @Test
  void statusCountsOnlyMappedAllowedStoresForRestrictedScope() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.STORE_LIST, List.of("store-a")));

    ApiResponse<java.util.Map<String, Object>> response = controller.status("Bearer token");

    assertThat(response.data().get("shopCount")).isEqualTo(1);
  }

  @Test
  void summaryPassesOnlyAllowedStoreIdsToOrderService() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.OWN_STORE, List.of("store-a")));
    when(orderService.summary(7, List.of("store-a"))).thenReturn(emptySummary);

    ApiResponse<ElemeSummaryResponse> response =
        controller.summary("Bearer token", null, 7);

    assertThat(response.data()).isSameAs(emptySummary);
    verify(orderService).summary(7, List.of("store-a"));
  }

  @Test
  void allScopeUsesExplicitUnrestrictedSentinel() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(DataScope.all());
    when(orderService.summary(eq(7), isNull())).thenReturn(emptySummary);

    controller.summary("Bearer token", null, 7);

    verify(orderService).summary(eq(7), isNull());
  }

  @Test
  void monthlySummaryUsesTheSameRestrictedStoreIds() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.STORE_LIST, List.of("store-b")));
    when(orderService.summaryForMonth("2026-07", List.of("store-b")))
        .thenReturn(emptySummary);

    controller.summary("Bearer token", "2026-07", 7);

    verify(orderService).summaryForMonth("2026-07", List.of("store-b"));
  }

  @Test
  void noneScopeIsRejectedBeforeOrderLookup() {
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(DataScope.none());

    assertThatThrownBy(() -> controller.summary("Bearer token", null, 7))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("FORBIDDEN"));
    verifyNoInteractions(orderService);
  }
}
