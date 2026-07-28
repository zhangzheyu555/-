package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.mockito.ArgumentCaptor;
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
  private final QmaiSyncService sync = mock(QmaiSyncService.class);
  private final QmaiProperties properties = new QmaiProperties();
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new QmaiController(
      config, orders, console, access, audit, data, recipes, sync, properties))
      .setControllerAdvice(new GlobalExceptionHandler()).build();

  @Test
  void bossSupervisorAndFinanceUseScopedRevenueWhileOtherRolesAreForbidden() throws Exception {
    for (String role : List.of("BOSS", "SUPERVISOR", "FINANCE")) {
      AuthUser user = user(role);
      when(access.requireUser("Bearer " + role)).thenReturn(user);
      String domain = "FINANCE".equals(role)
          ? DataScopeDomains.FINANCE
          : DataScopeDomains.PLATFORM;
      when(access.dataScope(user, domain)).thenReturn(
          new DataScope(DataScopeModes.STORE_LIST, List.of("s1")));
      when(data.month("2026-07")).thenReturn("2026-07");
      when(data.revenue(anyLong(), any(), anyString(), any())).thenReturn(List.of(
          new QmaiOperatingDataRepository.RevenueRow("s1", 1L, new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO)));
      mvc.perform(get("/api/qmai/revenue?month=2026-07").header("Authorization", "Bearer " + role))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].storeId").value("s1"));
      verify(access).requireQmaiRead(user);
    }
    verify(access).dataScope(user("FINANCE"), DataScopeDomains.FINANCE);
    verify(access, never()).dataScope(user("FINANCE"), DataScopeDomains.PLATFORM);
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
  void bossRecipeSnapshotUsesPlatformRatherThanWarehouseScope() throws Exception {
    properties.setRecipeEnabled(true);
    AuthUser boss = user("BOSS");
    when(access.requireUser("Bearer boss-recipe")).thenReturn(boss);
    when(access.dataScope(boss, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.ALL, List.of()));
    when(data.month("2026-07")).thenReturn("2026-07");
    when(recipes.monthly(anyLong(), any(), anyString(), any())).thenReturn(
        new QmaiRecipeSnapshotService.Snapshot("2026-07",
            new QmaiRecipeCalculationService.CalculationSnapshot(
                BigDecimal.ZERO.setScale(3), List.of()), 0));

    mvc.perform(get("/api/qmai/recipe-usage?month=2026-07")
            .header("Authorization", "Bearer boss-recipe"))
        .andExpect(status().isOk());

    verify(access).dataScope(boss, DataScopeDomains.PLATFORM);
    verify(access, never()).dataScope(boss, DataScopeDomains.WAREHOUSE);
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
    verify(access).requireDataExport(boss, null, "2026-07");
  }

  @Test
  void exactBusinessDateUsesSameLocalRangeForQueryAndExport() throws Exception {
    AuthUser boss = user("BOSS");
    when(access.requireUser("Bearer boss-date")).thenReturn(boss);
    when(access.dataScope(boss, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.ALL, List.of()));
    when(data.businessDate("2026-07", "2026-07-26")).thenReturn("2026-07-26");
    when(data.revenueForDate(anyLong(), any(), anyString(), any())).thenReturn(List.of(
        new QmaiOperatingDataRepository.RevenueRow(
            "s1", "茹果门店", 2L, new BigDecimal("20.00"),
            BigDecimal.ZERO, BigDecimal.ZERO)));
    when(data.productsForDate(anyLong(), any(), anyString(), any())).thenReturn(List.of(
        new QmaiOperatingDataRepository.ProductRow(
            "s1", "茹果门店", "芒果饮", "饮品", BigDecimal.ONE,
            BigDecimal.ZERO, new BigDecimal("20.00"), BigDecimal.ZERO)));

    mvc.perform(get("/api/qmai/revenue?month=2026-07&businessDate=2026-07-26")
            .header("Authorization", "Bearer boss-date"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].storeName").value("茹果门店"));
    mvc.perform(get("/api/qmai/revenue.csv?month=2026-07&businessDate=2026-07-26")
            .header("Authorization", "Bearer boss-date"))
        .andExpect(status().isOk())
        .andExpect(result -> assertThat(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
            .contains("茹果门店", "20.00"));
    mvc.perform(get("/api/qmai/products?month=2026-07&businessDate=2026-07-26")
            .header("Authorization", "Bearer boss-date"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].itemName").value("芒果饮"));
    mvc.perform(get("/api/qmai/products.csv?month=2026-07&businessDate=2026-07-26")
            .header("Authorization", "Bearer boss-date"))
        .andExpect(status().isOk())
        .andExpect(result -> assertThat(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
            .contains("茹果门店", "芒果饮"));

    verify(data, times(2)).revenueForDate(anyLong(), any(), anyString(), any());
    verify(data, times(2)).productsForDate(anyLong(), any(), anyString(), any());
    verify(access, times(2)).requireDataExport(boss, null, "2026-07-26");
  }

  @Test
  void supervisorCanReadQmaiButCannotExportCsvOrReachRepositoryQuery() throws Exception {
    AuthUser supervisor = user("SUPERVISOR");
    when(access.requireUser("Bearer supervisor-export")).thenReturn(supervisor);
    when(access.dataScope(supervisor, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.ALL, List.of()));
    when(data.month("2026-07")).thenReturn("2026-07");
    doThrow(new BusinessException("FORBIDDEN", "经营数据导出仅限财务或老板",
        HttpStatus.FORBIDDEN))
        .when(access).requireDataExport(supervisor, null, "2026-07");

    mvc.perform(get("/api/qmai/revenue.csv?month=2026-07")
            .header("Authorization", "Bearer supervisor-export"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(access).requireQmaiRead(supervisor);
    verify(access).requireDataExport(supervisor, null, "2026-07");
    verify(data, never()).revenue(anyLong(), any(), anyString(), any());
  }

  @Test
  void financeCanReadMaskedStatusThroughRealPermissionBoundary() throws Exception {
    AuthService authService = mock(AuthService.class);
    AuthRepository authRepository = mock(AuthRepository.class);
    AuditRepository realBoundaryAudit = mock(AuditRepository.class);
    AccessControlService realAccess =
        new AccessControlService(authService, authRepository, realBoundaryAudit);
    AuthUser finance = user("FINANCE");
    when(authService.requireUser("Bearer finance-real")).thenReturn(finance);
    when(config.maskedView(1L, null)).thenReturn(Map.of(
        "platform", "企迈",
        "configured", false,
        "shops", "285275:不应向财务暴露:s2"));
    MockMvc realMvc = MockMvcBuilders.standaloneSetup(new QmaiController(
        config, orders, console, realAccess, realBoundaryAudit, data, recipes, sync, properties))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    realMvc.perform(get("/api/qmai/status")
            .header("Authorization", "Bearer finance-real"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.platform").value("企迈"))
        .andExpect(jsonPath("$.data.shops").value(""));
    realMvc.perform(get("/api/qmai/config")
            .header("Authorization", "Bearer finance-real"))
        .andExpect(status().isForbidden());
  }

  @Test
  void allowedReadOnlyProbesWriteCredentialFreeAuditRecords() throws Exception {
    AuthUser boss = user("BOSS");
    when(access.requireUser("Bearer boss-probe")).thenReturn(boss);
    when(orders.probeShops(1L, null)).thenReturn(Map.of("ok", true));
    when(orders.probe(anyLong(), any(), anyString(), any(), anyInt()))
        .thenReturn(Map.of("ok", true));
    when(console.probe(anyLong(), any(), anyString(), any()))
        .thenReturn(Map.of("ok", true));

    mvc.perform(get("/api/qmai/probe-shops")
            .header("Authorization", "Bearer boss-probe"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/qmai/probe")
            .header("Authorization", "Bearer boss-probe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"v3/dataone/item/store/turnover",
                 "params":{"openKey":"must-not-be-audited","queryDate":"2026-07-26"}}
                """))
        .andExpect(status().isOk());
    mvc.perform(post("/api/qmai/console-probe")
            .header("Authorization", "Bearer boss-probe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"path":"data-center/trd/pc/list-business-income",
                 "params":{"qm_seller_token":"must-not-be-audited"}}
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditLogRequest> auditRequest =
        ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(audit, times(3)).writeLog(
        org.mockito.ArgumentMatchers.eq(boss), auditRequest.capture());
    assertThat(auditRequest.getAllValues()).allSatisfy(request -> {
      assertThat(request.targetId()).doesNotContain("must-not-be-audited");
      assertThat(request.reason()).doesNotContain("must-not-be-audited");
      assertThat(request.beforeJson()).isNull();
      assertThat(request.afterJson()).isNull();
    });
  }

  @Test
  void storeManagerCannotStartHistoricalBackfill() throws Exception {
    AuthUser manager = user("STORE_MANAGER");
    when(access.requireUser("Bearer manager-sync")).thenReturn(manager);
    doThrow(new BusinessException("FORBIDDEN", "无权", HttpStatus.FORBIDDEN))
        .when(access).requirePlatformManage(manager);

    mvc.perform(post("/api/qmai/sync/backfill?month=2026-07")
            .header("Authorization", "Bearer manager-sync"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verifyNoInteractions(sync);
  }

  @Test
  void bossStartsTenantScopedMonthlyBackfillAndWritesAudit() throws Exception {
    AuthUser boss = user("BOSS");
    when(access.requireUser("Bearer boss-sync")).thenReturn(boss);
    when(access.dataScope(boss, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.ALL, List.of()));
    when(data.month("2026-06")).thenReturn("2026-06");
    QmaiSyncService.BatchView batch = new QmaiSyncService.BatchView(
        9L, "ruguo", "2026-06", "QUEUED", 30, 0, 0, 0, 0,
        null, "历史数据补取任务已排队", "BOSS", null, null, null);
    when(sync.startMonth(1L, null, "2026-06", null, 7L, "BOSS"))
        .thenReturn(batch);

    mvc.perform(post("/api/qmai/sync/backfill?month=2026-06")
            .header("Authorization", "Bearer boss-sync"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(9))
        .andExpect(jsonPath("$.data.totalTasks").value(30));

    verify(access).requirePlatformManage(boss);
    verify(audit).writeLog(any(), any());
  }

  private AuthUser user(String role) {
    return new AuthUser(7L, 1L, "tenant", role.toLowerCase(), "", role, role, "s1", true);
  }
}
