package com.storeprofit.system.storemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.expense.ExpenseService;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.inspection.InspectionRecordResponse;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.RoleTodoService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import com.storeprofit.system.warehouse.WarehouseService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoreManagerWorkbenchServiceTest {
  private final RoleTodoService roleTodoService = mock(RoleTodoService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
  private final FinanceService financeService = mock(FinanceService.class);
  private final InspectionService inspectionService = mock(InspectionService.class);
  private final ExpenseService expenseService = mock(ExpenseService.class);
  private final StoreManagerWorkbenchService service = new StoreManagerWorkbenchService(
      roleTodoService,
      warehouseService,
      warehouseRepository,
      financeService,
      inspectionService,
      expenseService
  );
  private final AuthUser storeManager = new AuthUser(
      5L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "s1", true);

  @Test
  void inspectionSummaryUsesCanonicalScoresAndCountsOnlyActualRedLines() {
    InspectionRecordResponse ordinaryFailure = mock(InspectionRecordResponse.class);
    when(ordinaryFailure.inspectionDate()).thenReturn("2026-07-08");
    when(ordinaryFailure.displayScore()).thenReturn(new BigDecimal("164.00"));
    when(ordinaryFailure.displayResultCode()).thenReturn("FAILED");
    when(ordinaryFailure.redLineCount()).thenReturn(0L);

    InspectionRecordResponse redLineFailure = mock(InspectionRecordResponse.class);
    when(redLineFailure.inspectionDate()).thenReturn("2026-07-09");
    when(redLineFailure.displayScore()).thenReturn(new BigDecimal("196.00"));
    when(redLineFailure.displayResultCode()).thenReturn("RED_LINE_FAILED");
    when(redLineFailure.redLineCount()).thenReturn(1L);
    when(warehouseRepository.storeName(1L, "s1")).thenReturn(Optional.of("一店"));
    when(inspectionService.records(storeManager, null, null, null, "s1", null))
        .thenReturn(List.of(ordinaryFailure, redLineFailure));

    StoreManagerInspectionPageResponse response = service.inspections(storeManager);

    assertThat(response.summary().averageScore()).isEqualByComparingTo("180.00");
    assertThat(response.summary().redlineCount()).isEqualTo(1);
  }
}
