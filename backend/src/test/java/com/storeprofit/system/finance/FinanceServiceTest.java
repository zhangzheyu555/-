package com.storeprofit.system.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceServiceTest {
  private final FinanceRepository financeRepository = mock(FinanceRepository.class);
  private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final FinanceService service = new FinanceService(financeRepository, organizationRepository, accessControl);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
  private final AuthUser finance = new AuthUser(2L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);
  private final AuthUser storeManager = new AuthUser(3L, 1L, "default", "rg1", "", "店长·荆州之星店", "STORE_MANAGER", "rg1", true);

  @Test
  void bossCanDeleteExistingProfitEntryAndWriteOperationLog() {
    when(financeRepository.storeExists(1L, "rg1")).thenReturn(true);
    when(financeRepository.entryExists(1L, "rg1", "2026-12")).thenReturn(true);

    service.delete(boss, "rg1", "2026-12");

    verify(financeRepository).deleteEntry(1L, "rg1", "2026-12");
    verify(financeRepository).logDelete(1L, 1L, "Boss", "rg1", "2026-12");
  }

  @Test
  void financeRoleCannotDeleteProfitEntry() {
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", org.springframework.http.HttpStatus.FORBIDDEN))
        .when(accessControl)
        .requireFinanceDelete(finance);

    assertThatThrownBy(() -> service.delete(finance, "rg1", "2026-12"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(financeRepository, never()).deleteEntry(1L, "rg1", "2026-12");
  }

  @Test
  void storeManagerCanStillSaveManualEntryForOwnStore() {
    ProfitEntryRequest request = new ProfitEntryRequest(
        "rg1", "2026-07", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, "本店手工录入"
    );
    when(financeRepository.storeExists(1L, "rg1")).thenReturn(true);

    service.save(storeManager, request);

    verify(accessControl).requireFinanceWrite(storeManager);
    verify(accessControl, never()).requireFinanceImport(storeManager);
    verify(financeRepository).upsert(eq(1L), any(ProfitEntryRequest.class), eq(3L));
    verify(financeRepository).logSave(1L, 3L, "店长·荆州之星店", "rg1", "2026-07");
  }

  @Test
  void storeManagerEntriesDefaultToBoundStore() {
    when(financeRepository.entries(1L, "2026-07", null, "rg1")).thenReturn(List.of());

    assertThat(service.entries(storeManager, "2026-07", null, null)).isEmpty();

    verify(financeRepository).entries(1L, "2026-07", null, "rg1");
  }

  @Test
  void storeManagerCannotQueryOtherStoreProfit() {
    assertThatThrownBy(() -> service.entries(storeManager, "2026-07", null, "bw1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(financeRepository, never()).entries(1L, "2026-07", null, "bw1");
  }

  @Test
  void configuredFinanceStoreListRejectsForgedStoreBeforeRepositoryQuery() {
    DataScopeService dataScopeService = mock(DataScopeService.class);
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("rg1"));
    when(dataScopeService.scope(finance, DataScopeDomains.FINANCE)).thenReturn(scope);
    FinanceService scopedService = new FinanceService(
        financeRepository, organizationRepository, accessControl, null, dataScopeService);

    assertThatThrownBy(() -> scopedService.entries(finance, "2026-07", null, "bw1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    verify(financeRepository, never()).entries(1L, "2026-07", null, "bw1", scope);
  }
}
