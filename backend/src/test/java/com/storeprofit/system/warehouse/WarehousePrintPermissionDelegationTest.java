package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class WarehousePrintPermissionDelegationTest {
  private final WarehouseRepository repository = mock(WarehouseRepository.class);
  private final WarehousePdfRenderer renderer = mock(WarehousePdfRenderer.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final WarehousePrintService service = new WarehousePrintService(repository, renderer, accessControl);

  @Test
  void delegatedStoreReaderCanDownloadOnlyScopedDeliveryWithCostsRedacted() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    WarehouseDeliveryPrintHeader header = header("REQ-1", "rg1");
    WarehouseDeliveryPrintLine line = new WarehouseDeliveryPrintLine(
        1L,
        "鲜奶",
        "12盒/件",
        "件",
        BigDecimal.ONE,
        new BigDecimal("88.00"),
        new BigDecimal("88.00"),
        "B001",
        ""
    );
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("rg1")));
    when(repository.deliveryPrintHeader(1L, "REQ-1")).thenReturn(Optional.of(header));
    when(repository.deliveryPrintLines(1L, "REQ-1")).thenReturn(List.of(line));
    when(renderer.delivery(eq(header), org.mockito.ArgumentMatchers.anyList())).thenReturn(new byte[] {1});

    service.deliveryPdf(delegated, "REQ-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<WarehouseDeliveryPrintLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
    verify(renderer).delivery(eq(header), linesCaptor.capture());
    assertThat(linesCaptor.getValue()).singleElement().satisfies(safeLine -> {
      assertThat(safeLine.unitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(safeLine.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    });
    verify(accessControl).requireWarehouseStoreRead(delegated);
    verify(accessControl).requireStoreAccess(
        delegated,
        DataScopeDomains.WAREHOUSE,
        "rg1",
        "下载该出库单"
    );
  }

  @Test
  void explicitCrossStoreDeliveryIdReturnsForbiddenAndIsNotRendered() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    BusinessException denied = forbidden();
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.STORE_LIST, List.of("rg1")));
    when(repository.deliveryPrintHeader(1L, "REQ-OTHER"))
        .thenReturn(Optional.of(header("REQ-OTHER", "rg2")));
    doThrow(denied).when(accessControl).requireStoreAccess(
        delegated,
        DataScopeDomains.WAREHOUSE,
        "rg2",
        "下载该出库单"
    );

    assertThatThrownBy(() -> service.deliveryPdf(delegated, "REQ-OTHER")).isSameAs(denied);

    verify(renderer, never()).delivery(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyList()
    );
  }

  @Test
  void personalStoreReadDenyPreventsPdfRendering() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    BusinessException denied = forbidden();
    when(accessControl.hasPermission(delegated, PermissionCodes.WAREHOUSE_CENTRAL_READ)).thenReturn(false);
    when(accessControl.dataScope(delegated, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("rg1")));
    when(repository.deliveryPrintHeader(1L, "REQ-1")).thenReturn(Optional.of(header("REQ-1", "rg1")));
    doThrow(denied).when(accessControl).requireWarehouseStoreRead(delegated);

    assertThatThrownBy(() -> service.deliveryPdf(delegated, "REQ-1")).isSameAs(denied);

    verify(renderer, never()).delivery(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyList()
    );
  }

  @Test
  void receiptPdfRequiresCentralReadBeforeLoadingTheBatch() {
    AuthUser delegated = user("OPERATIONS", "rg1");
    BusinessException denied = forbidden();
    doThrow(denied).when(accessControl).requireWarehouseCentralRead(delegated);

    assertThatThrownBy(() -> service.receiptPdf(delegated, 7L)).isSameAs(denied);

    verify(repository, never()).receiptPrintRow(1L, 7L);
  }

  private WarehouseDeliveryPrintHeader header(String requisitionId, String storeId) {
    return new WarehouseDeliveryPrintHeader(
        requisitionId,
        storeId,
        storeId,
        "SHIPPED",
        "收货人",
        "",
        "",
        "DEL-1",
        "2026-07-11 10:00:00",
        "",
        "仓库管理员"
    );
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(7L, 1L, "default", "tester", "", "测试账号", role, storeId, true);
  }

  private BusinessException forbidden() {
    return new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
  }
}
