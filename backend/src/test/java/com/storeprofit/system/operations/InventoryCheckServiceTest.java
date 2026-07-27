package com.storeprofit.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryItemResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InventoryCheckServiceTest {
  private final OperationsBusinessRepository repository = mock(OperationsBusinessRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final OperationsBusinessService service = new OperationsBusinessService(repository, accessControl);
  private final AuthUser manager = new AuthUser(
      9L, 1L, "default", "manager", "", "测试店长", "STORE_MANAGER", "S-001", true);
  private final AuthUser boss = new AuthUser(
      10L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
  private final AuthUser finance = new AuthUser(
      11L, 1L, "default", "finance", "", "财务张三", "FINANCE", null, true);

  @Test
  void saveUsesCatalogPriceAndSumsRoundedLineAmounts() {
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));
    when(repository.inventoryItems(1L)).thenReturn(List.of(
        item(1L, "PD-001", "物料一", "0.120000"),
        item(2L, "PD-002", "物料二", "0.120000")
    ));
    when(repository.saveInventoryCheck(
        anyLong(), isNull(), anyString(), anyString(), anyString(), anyString(),
        isNull(), any(BigDecimal.class), anyLong(), any()
    )).thenReturn(88L);
    when(repository.inventoryCheck(1L, 88L)).thenReturn(Optional.of(savedCheck()));

    InventoryCheckLineRequest tamperedOne = requestLine("PD-001", "9999", "0.12");
    InventoryCheckLineRequest tamperedTwo = requestLine("PD-002", "9999", "0.12");
    service.saveInventoryCheck(manager, new InventoryCheckRequest(
        null, "S-001", "2026-07-27", null, List.of(tamperedOne, tamperedTwo)));

    ArgumentCaptor<BigDecimal> totalCaptor = ArgumentCaptor.forClass(BigDecimal.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<InventoryCheckLineRequest>> linesCaptor =
        ArgumentCaptor.forClass((Class) List.class);
    verify(repository).saveInventoryCheck(
        eq(1L), isNull(), anyString(), eq("S-001"), eq("测试门店"), eq("2026-07-27"),
        isNull(), totalCaptor.capture(), eq(9L), linesCaptor.capture());

    assertThat(totalCaptor.getValue()).isEqualByComparingTo("0.02");
    assertThat(linesCaptor.getValue())
        .extracting(InventoryCheckLineRequest::unitPrice)
        .containsExactly(new BigDecimal("0.120000"), new BigDecimal("0.120000"));
    assertThat(linesCaptor.getValue())
        .extracting(InventoryCheckLineRequest::itemName)
        .containsExactly("物料一", "物料二");
  }

  @Test
  void positiveQuantityRequiresCatalogPrice() {
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));
    when(repository.inventoryItems(1L)).thenReturn(List.of(
        new InventoryItemResponse(
            1L, "PD-001", "水果", "待定价水果", null, "斤",
            BigDecimal.ONE, null, null, 1, true, false)
    ));

    assertThatThrownBy(() -> service.saveInventoryCheck(manager, new InventoryCheckRequest(
        null, "S-001", "2026-07-27", null,
        List.of(requestLine("PD-001", "9999", "1")))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚未维护价格");

    verify(repository, never()).saveInventoryCheck(
        anyLong(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void financeReviewStoresAccountableNameRoleAndFirstReviewRemainsResponsible() {
    InventoryCheckResponse submitted = submittedCheck();
    InventoryCheckResponse reviewed = new InventoryCheckResponse(
        88L, "PDC-TEST", "S-001", "测试门店", "2026-07-27",
        "REVIEWED", "已复核", new BigDecimal("0.02"), 9L, 11L,
        "财务张三", "FINANCE", "财务", "2026-07-27 12:00:00",
        null, "2026-07-27 10:00:00", "2026-07-27 12:00:00", List.of());
    when(repository.inventoryCheck(1L, 88L))
        .thenReturn(Optional.of(submitted), Optional.of(reviewed));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));
    when(repository.reviewInventoryCheck(1L, 88L, 11L, "财务张三", "FINANCE"))
        .thenReturn(true);

    InventoryCheckResponse result = service.reviewInventoryCheck(finance, 88L);

    assertThat(result.reviewedByName()).isEqualTo("财务张三");
    assertThat(result.reviewedByRole()).isEqualTo("FINANCE");
    assertThat(result.reviewedByRoleLabel()).isEqualTo("财务");
    assertThat(service.reviewInventoryCheck(finance, 88L)).isSameAs(reviewed);
    verify(repository).reviewInventoryCheck(1L, 88L, 11L, "财务张三", "FINANCE");
  }

  @Test
  void submittedCheckSubmitIsIdempotentAndCancelIsDisabled() {
    InventoryCheckResponse submitted = submittedCheck();
    when(repository.inventoryCheck(1L, 88L)).thenReturn(Optional.of(submitted));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试门店"));

    assertThat(service.submitInventoryCheck(manager, 88L)).isSameAs(submitted);
    assertThatThrownBy(() -> service.cancelInventoryCheck(manager, 88L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不支持作废");
    verify(repository, never()).reviewInventoryCheck(
        anyLong(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void bossExportContainsAuthoritativeNumericAmountsAndWritesAuditLog() throws Exception {
    InventoryCheckLineResponse line = new InventoryCheckLineResponse(
        1L, "=不执行公式", "PD-001", "耗材", "1件10个", "个",
        new BigDecimal("10"), new BigDecimal("0.123000"), new BigDecimal("1.230000"),
        new BigDecimal("10"), new BigDecimal("1.23"), "正常备注");
    InventoryCheckResponse check = new InventoryCheckResponse(
        88L, "PDC-TEST", "S-001", "测试/门店", "2026-07-27",
        "REVIEWED", "已复核", new BigDecimal("1.23"), 9L, 10L,
        "财务张三", "FINANCE", "财务", "2026-07-27 12:00:00",
        null, "2026-07-27 10:00:00",
        "2026-07-27 12:00:00", List.of(line));
    when(repository.inventoryCheck(1L, 88L)).thenReturn(Optional.of(check));
    when(repository.inventoryStoreName(1L, "S-001")).thenReturn(Optional.of("测试/门店"));

    var export = service.exportInventoryCheck(boss, 88L);

    assertThat(export.fileName()).isEqualTo("测试_门店-店铺盘存-2026-07-27-PDC-TEST.xlsx");
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(export.content()))) {
      var sheet = workbook.getSheet("店铺盘存");
      assertThat(sheet).isNotNull();
      assertThat(sheet.getRow(4).getCell(2).getCellType()).isEqualTo(CellType.STRING);
      assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("=不执行公式");
      assertThat(sheet.getRow(4).getCell(9).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(sheet.getRow(4).getCell(9).getNumericCellValue()).isEqualTo(1.23d);
      assertThat(sheet.getRow(5).getCell(9).getNumericCellValue()).isEqualTo(1.23d);
      assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("负责人：财务张三");
      assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("负责人职务：财务");
      assertThat(sheet.getRow(2).getCell(7).getStringCellValue())
          .isEqualTo("复核时间：2026-07-27 12:00:00");
    }
    verify(repository).logAction(
        eq(1L), eq(10L), eq("老板"), eq("导出店铺盘存Excel"),
        eq("store_inventory_check"), eq("88"), eq("S-001"), anyString());
  }

  private InventoryItemResponse item(long id, String code, String name, String unitPrice) {
    return new InventoryItemResponse(
        id, code, "耗材", name, "测试规格", "个",
        BigDecimal.ONE, new BigDecimal(unitPrice), new BigDecimal(unitPrice),
        Math.toIntExact(id), true, true);
  }

  private InventoryCheckLineRequest requestLine(String code, String clientPrice, String quantity) {
    return new InventoryCheckLineRequest(
        "客户端伪造名称", code, "伪造分类", "伪造规格", "件",
        BigDecimal.ONE, new BigDecimal(clientPrice), new BigDecimal(clientPrice),
        new BigDecimal(quantity), null);
  }

  private InventoryCheckResponse savedCheck() {
    return submittedCheck();
  }

  private InventoryCheckResponse submittedCheck() {
    return new InventoryCheckResponse(
        88L, "PDC-TEST", "S-001", "测试门店", "2026-07-27",
        "SUBMITTED", "已提交", new BigDecimal("0.02"), 9L, null,
        null, null, null, null,
        null, "2026-07-27 10:00:00", "2026-07-27 10:00:00", List.of());
  }
}
