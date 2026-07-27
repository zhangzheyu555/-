package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.StoreBusinessGuard;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InspectionInactiveStoreTest {

  @Test
  void inactiveStoreCannotCreateANewInspectionRecord() {
    InspectionRecordRepository records = mock(InspectionRecordRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    StoreBusinessGuard guard = mock(StoreBusinessGuard.class);
    AuthUser supervisor = new AuthUser(
        7L, 1L, "测试租户", "supervisor", "", "督导", "SUPERVISOR", null, true);
    doThrow(new BusinessException(
        "STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN",
        "门店已停用，不能创建新的巡检单",
        HttpStatus.CONFLICT
    )).when(guard).requireActive(supervisor, "s1", "巡检单");
    InspectionService service = new InspectionService(
        records,
        access,
        mock(InspectionStandardRepository.class),
        mock(StorageService.class),
        "http://127.0.0.1:1",
        "http://127.0.0.1:1",
        Duration.ofMillis(100),
        "TEST",
        "MOCK",
        mock(AuditRepository.class),
        guard
    );
    InspectionRecordRequest request = new InspectionRecordRequest(
        "s1", "2026-07-24", "督导", "测试品牌",
        new BigDecimal("100"), new BigDecimal("100"), true,
        "[]", "[]", "[]", "测试"
    );

    assertThatThrownBy(() -> service.save(supervisor, null, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN"));
    verifyNoInteractions(records);
  }
}
