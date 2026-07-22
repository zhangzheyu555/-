package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QmaiControllerAuthorizationTest {
  private final QmaiConfigService config = mock(QmaiConfigService.class);
  private final QmaiOrderService orders = mock(QmaiOrderService.class);
  private final QmaiConsoleService console = mock(QmaiConsoleService.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final AuditRepository audit = mock(AuditRepository.class);
  private final QmaiOperatingDataService data = mock(QmaiOperatingDataService.class);
  private final QmaiRecipeSnapshotService recipes = mock(QmaiRecipeSnapshotService.class);
  private final QmaiProperties properties = new QmaiProperties();
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new QmaiController(
      config, orders, console, access, audit, data, recipes, properties))
      .setControllerAdvice(new GlobalExceptionHandler()).build();

  @Test
  void bossSupervisorAndFinanceUseScopedRevenueWhileOtherRolesAreForbidden() throws Exception {
    for (String role : List.of("BOSS", "SUPERVISOR", "FINANCE")) {
      AuthUser user = user(role);
      when(access.requireUser("Bearer " + role)).thenReturn(user);
      when(access.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(
          new DataScope(DataScopeModes.STORE_LIST, List.of("s1")));
      when(data.month("2026-07")).thenReturn("2026-07");
      when(data.revenue(anyLong(), any(), anyString(), any())).thenReturn(List.of(
          new QmaiOperatingDataRepository.RevenueRow("s1", 1L, new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO)));
      mvc.perform(get("/api/qmai/revenue?month=2026-07").header("Authorization", "Bearer " + role))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].storeId").value("s1"));
      verify(access).requireQmaiRead(user);
    }
    verify(audit, times(3)).writeLog(any(), any());
    AuthUser denied = user("STORE_MANAGER");
    when(access.requireUser("Bearer manager")).thenReturn(denied);
    doThrow(new BusinessException("FORBIDDEN", "无权", HttpStatus.FORBIDDEN)).when(access).requireQmaiRead(denied);
    mvc.perform(get("/api/qmai/revenue").header("Authorization", "Bearer manager"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void warehouseCanReadOnlyItsRecipeSnapshotAndCrossStoreIsForbidden() throws Exception {
    properties.setRecipeEnabled(true);
    AuthUser warehouse = user("WAREHOUSE");
    when(access.requireUser("Bearer warehouse")).thenReturn(warehouse);
    when(access.dataScope(warehouse, DataScopeDomains.WAREHOUSE)).thenReturn(
        new DataScope(DataScopeModes.STORE_LIST, List.of("s1")));
    when(data.month("2026-07")).thenReturn("2026-07");
    when(recipes.monthly(anyLong(), any(), anyString(), any())).thenReturn(
        new QmaiRecipeSnapshotService.Snapshot("2026-07",
            new QmaiRecipeCalculationService.CalculationSnapshot(BigDecimal.ZERO.setScale(3), List.of()), 0));
    mvc.perform(get("/api/qmai/recipe-usage?month=2026-07&storeId=s1").header("Authorization", "Bearer warehouse"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/qmai/recipe-usage?month=2026-07&storeId=other").header("Authorization", "Bearer warehouse"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verify(access, times(2)).requireQmaiRecipeRead(warehouse);
    verify(audit).writePermissionDenied(warehouse, "访问未授权配方门店", "qmai_store", "other", "other",
        "门店不在当前账号的数据范围内");
  }


  @Test
  void disabledRecipeEndpointsRejectAfterAuthorizationWithoutReadingRecipeData() throws Exception {
    AuthUser warehouse = user("WAREHOUSE");
    when(access.requireUser("Bearer warehouse-disabled")).thenReturn(warehouse);

    mvc.perform(get("/api/qmai/recipe-usage?month=2026-07&storeId=s1")
            .header("Authorization", "Bearer warehouse-disabled"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("QMAI_RECIPE_DISABLED"))
        .andExpect(jsonPath("$.message").value("企迈配方用量功能暂未开放"));

    verify(access).requireQmaiRecipeRead(warehouse);
    verifyNoInteractions(recipes);
  }

  @Test
  void anonymousQmaiReadIs401BeforeAnyDataAccess() throws Exception {
    when(access.requireUser(null)).thenThrow(new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    mvc.perform(get("/api/qmai/revenue")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void revenueCsvUsesRealLineBreaks() throws Exception {
    AuthUser boss = user("BOSS");
    when(access.requireUser("Bearer boss")).thenReturn(boss);
    when(access.dataScope(boss, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.ALL, List.of()));
    when(data.month("2026-07")).thenReturn("2026-07");
    when(data.revenue(anyLong(), any(), anyString(), any())).thenReturn(List.of(
        new QmaiOperatingDataRepository.RevenueRow(
            "s1", 1L, new BigDecimal("12.00"), BigDecimal.ZERO, BigDecimal.ZERO)));

    mvc.perform(get("/api/qmai/revenue.csv?month=2026-07")
            .header("Authorization", "Bearer boss"))
        .andExpect(status().isOk())
        .andExpect(result -> assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
            .contains("\r\n")
            .doesNotContain("\\r\\n"));
  }

  private AuthUser user(String role) {
    return new AuthUser(7L, 1L, "tenant", role.toLowerCase(), "", role, role, "s1", true);
  }
}
