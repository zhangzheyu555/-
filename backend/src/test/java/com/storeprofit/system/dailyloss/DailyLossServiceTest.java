package com.storeprofit.system.dailyloss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DailyLossServiceTest {
  @Test
  void approvalWritesExactlyOneLossOutMovementAndDoesNotTouchProfitLedger() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    WarehouseRepository warehouse = mock(WarehouseRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuditRepository audit = mock(AuditRepository.class);
    AuthUser reviewer = user("WAREHOUSE", null);
    DailyLossRepository.LockedLossRow locked = new DailyLossRepository.LockedLossRow(
        "loss-1", "s1", 11L, new BigDecimal("2.50"), "临期变质", "SUBMITTED");
    DailyLossRepository.DailyLossRow approved = row("APPROVED");
    when(access.dataScope(reviewer, DataScopeDomains.WAREHOUSE)).thenReturn(DataScope.all());
    when(repository.findForUpdate(1L, "loss-1")).thenReturn(Optional.of(locked));
    when(repository.insertInventoryApplication(1L, locked, reviewer.id())).thenReturn(true);
    when(warehouse.subtractStoreInventoryIfEnough(
        eq(1L), eq("s1"), eq(11L), eq(new BigDecimal("2.50")), eq("LOSS_OUT"),
        eq("DAILY_LOSS"), eq("loss-1"), eq("临期变质"), eq(reviewer.id())
    )).thenReturn(true);
    when(repository.find(1L, "loss-1")).thenReturn(Optional.of(approved));
    when(repository.inventoryApplicationExists(1L, "loss-1")).thenReturn(true);
    when(repository.attachments(1L, "loss-1")).thenReturn(List.of());

    DailyLossResponse response = service(repository, warehouse, access, audit).approve(
        reviewer, "loss-1", new DailyLossReviewRequest("库存已核对"));

    assertThat(response.status()).isEqualTo("APPROVED");
    assertThat(response.inventoryDeducted()).isTrue();
    verify(repository).markApproved(1L, "loss-1", reviewer.id(), "库存已核对");
    verify(warehouse).subtractStoreInventoryIfEnough(
        1L, "s1", 11L, new BigDecimal("2.50"), "LOSS_OUT", "DAILY_LOSS", "loss-1", "临期变质", reviewer.id());
  }

  @Test
  void repeatedApprovalReturnsExistingResultWithoutAnotherInventoryDeduction() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    WarehouseRepository warehouse = mock(WarehouseRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser reviewer = user("FINANCE", null);
    DailyLossRepository.LockedLossRow locked = new DailyLossRepository.LockedLossRow(
        "loss-1", "s1", 11L, new BigDecimal("2.50"), "临期变质", "APPROVED");
    when(access.dataScope(reviewer, DataScopeDomains.WAREHOUSE)).thenReturn(DataScope.all());
    when(repository.findForUpdate(1L, "loss-1")).thenReturn(Optional.of(locked));
    when(repository.inventoryApplicationExists(1L, "loss-1")).thenReturn(true);
    when(repository.find(1L, "loss-1")).thenReturn(Optional.of(row("APPROVED")));
    when(repository.attachments(1L, "loss-1")).thenReturn(List.of());

    DailyLossResponse response = service(repository, warehouse, access, mock(AuditRepository.class)).approve(
        reviewer, "loss-1", new DailyLossReviewRequest(null));

    assertThat(response.inventoryDeducted()).isTrue();
    verify(warehouse, never()).subtractStoreInventoryIfEnough(
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void forgedCrossStoreReadIsDeniedBeforeAttachmentsAreLoaded() {
    DailyLossRepository repository = mock(DailyLossRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    AuthUser manager = user("STORE_MANAGER", "s1");
    when(access.dataScope(manager, DataScopeDomains.WAREHOUSE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("s1")));
    when(repository.find(1L, "loss-2")).thenReturn(Optional.of(rowForStore("s2", "SUBMITTED")));
    doThrow(new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN))
        .when(access).requireStoreAccess(manager, DataScopeDomains.WAREHOUSE, "s2", "查看每日报损");

    BusinessException error = catchThrowableOfType(
        () -> service(repository, mock(WarehouseRepository.class), access, mock(AuditRepository.class)).get(manager, "loss-2"),
        BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    verify(repository, never()).attachments(1L, "loss-2");
  }

  private DailyLossService service(
      DailyLossRepository repository,
      WarehouseRepository warehouse,
      AccessControlService access,
      AuditRepository audit
  ) {
    return new DailyLossService(repository, warehouse, mock(StorageService.class), access, audit);
  }

  private DailyLossRepository.DailyLossRow row(String status) {
    return rowForStore("s1", status);
  }

  private DailyLossRepository.DailyLossRow rowForStore(String storeId, String status) {
    return new DailyLossRepository.DailyLossRow(
        storeId.equals("s1") ? "loss-1" : "loss-2", storeId, "001", "测试门店", LocalDate.of(2026, 7, 14),
        11L, "ITEM-11", "牛奶", "盒", new BigDecimal("2.50"), new BigDecimal("3.2000"),
        new BigDecimal("8.00"), "临期变质", status, 6L, "店长", LocalDateTime.of(2026, 7, 14, 9, 0),
        "APPROVED".equals(status) ? 7L : null, "APPROVED".equals(status) ? "财务" : null,
        "APPROVED".equals(status) ? LocalDateTime.of(2026, 7, 14, 10, 0) : null, null);
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(7L, 1L, "测试租户", "tester", "hash", "测试人员", role, storeId, true, 1L);
  }
}
