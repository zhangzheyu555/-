package com.storeprofit.system.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.session.SessionUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthControllerContractTest {
  @Test
  void authMeReturnsSameExpandedSessionContract() {
    AuthService authService = mock(AuthService.class);
    AuthController controller = new AuthController(authService);
    AuthUser user = new AuthUser(
        1L, 1L, "测试租户", "boss", "hash", "老板", "BOSS", null, true, 4L);
    DataScope ownStore = new DataScope(DataScopeModes.OWN_STORE, List.of("rg1"));
    SessionUser session = new SessionUser(
        1L, 1L, "测试租户", "老板", "BOSS", "老板（系统管理员）",
        List.of("rg1"), List.of("system.user.manage"),
        Map.of(DataScopeDomains.STORE, ownStore), "/boss", 4L,
        "rg1", "荆州之星店", 9L, "茹菓", ownStore);
    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(authService.toSessionUser(user)).thenReturn(session);

    var response = controller.me("Bearer token");

    assertThat(response.data()).isSameAs(session);
    assertThat(response.data().boundStoreId()).isEqualTo("rg1");
    assertThat(response.data().boundStoreName()).isEqualTo("荆州之星店");
    assertThat(response.data().brandId()).isEqualTo(9L);
    assertThat(response.data().brandName()).isEqualTo("茹菓");
    assertThat(response.data().dataScope()).isEqualTo(ownStore);
    verify(authService).requireUser("Bearer token");
    verify(authService).toSessionUser(user);
  }
}
