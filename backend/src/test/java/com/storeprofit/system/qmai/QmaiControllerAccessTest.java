package com.storeprofit.system.qmai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class QmaiControllerAccessTest {
  @Test
  void missingLoginStopsBeforeAnyQmaiDataIsRead() {
    AccessControlService accessControl = mock(AccessControlService.class);
    QmaiConfigService configService = mock(QmaiConfigService.class);
    QmaiBusinessService businessService = mock(QmaiBusinessService.class);
    when(accessControl.requireUser(null))
        .thenThrow(new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));
    QmaiController controller = controller(accessControl, configService, businessService);

    BusinessException error = catchThrowableOfType(() -> controller.status(null), BusinessException.class);

    assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(configService, businessService);
  }

  @Test
  void configurationUsesThePlatformManageGate() {
    AccessControlService accessControl = mock(AccessControlService.class);
    QmaiConfigService configService = mock(QmaiConfigService.class);
    QmaiBusinessService businessService = mock(QmaiBusinessService.class);
    AuthUser boss = user("BOSS");
    when(accessControl.requireUser("Bearer boss")).thenReturn(boss);
    QmaiController controller = controller(accessControl, configService, businessService);

    controller.config("Bearer boss");

    verify(accessControl).requirePlatformManage(boss);
    verify(configService).view(boss.tenantId());
    verifyNoInteractions(businessService);
  }

  @Test
  void synchronizationDelegatesToTheFinanceServicePermissionBoundary() {
    AccessControlService accessControl = mock(AccessControlService.class);
    QmaiConfigService configService = mock(QmaiConfigService.class);
    QmaiBusinessService businessService = mock(QmaiBusinessService.class);
    AuthUser finance = user("FINANCE");
    when(accessControl.requireUser("Bearer finance")).thenReturn(finance);
    QmaiController controller = controller(accessControl, configService, businessService);

    controller.sync("Bearer finance", "2026-07");

    verify(businessService).startSync(finance, "2026-07");
    verifyNoInteractions(configService);
  }

  private QmaiController controller(AccessControlService accessControl, QmaiConfigService configService,
      QmaiBusinessService businessService) {
    return new QmaiController(accessControl, configService, businessService, mock(AuditRepository.class));
  }

  private AuthUser user(String role) {
    return new AuthUser(12L, 1L, "测试租户", "test-user", "hash", "测试用户", role, null, true);
  }
}
