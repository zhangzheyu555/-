package com.storeprofit.system.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformStatusControllerDataScopeTest {
  private final PlatformAdapterRegistry registry = mock(PlatformAdapterRegistry.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final AuthUser user = new AuthUser(
      7L, 1L, "tenant", "supervisor", "", "督导", "SUPERVISOR", "store-a", true);
  private final PlatformStatusController controller =
      new PlatformStatusController(registry, accessControl);

  @Test
  void nonNoneScopeCanReadNonSecretAdapterMetadata() {
    PlatformAdapterStatus adapterStatus = new PlatformAdapterStatus(
        "ELEME", PlatformSyncState.READY, PlatformSyncState.NOT_CONFIGURED, "已配置");
    when(accessControl.requireUser("Bearer token")).thenReturn(user);
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(
        new DataScope(DataScopeModes.STORE_LIST, List.of("store-a")));
    when(registry.statuses()).thenReturn(List.of(adapterStatus));

    assertThat(controller.status("Bearer token").data()).containsExactly(adapterStatus);
    verify(accessControl).requirePlatformRead(user);
  }

  @Test
  void noneScopeCannotReadAdapterMetadata() {
    when(accessControl.requireUser("Bearer token")).thenReturn(user);
    when(accessControl.dataScope(user, DataScopeDomains.PLATFORM)).thenReturn(DataScope.none());

    assertThatThrownBy(() -> controller.status("Bearer token"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("FORBIDDEN"));
    verifyNoInteractions(registry);
  }
}
