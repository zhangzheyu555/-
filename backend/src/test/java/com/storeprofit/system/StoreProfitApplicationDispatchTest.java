package com.storeprofit.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.StoreProfitApplication.StartupMode;
import com.storeprofit.system.platform.bootstrap.AdminBootstrapResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StoreProfitApplicationDispatchTest {
  @Test
  void normalArgumentsAlwaysSelectTheOriginalWebPath() {
    assertThat(StoreProfitApplication.startupMode(new String[0], Map.of()))
        .isEqualTo(StartupMode.WEB);
    assertThat(StoreProfitApplication.startupMode(
        new String[0], Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "true")))
        .isEqualTo(StartupMode.WEB);
    assertThat(StoreProfitApplication.startupMode(
        new String[] {"--admin-bootstrap=true"},
        Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "true")))
        .isEqualTo(StartupMode.WEB);
    assertThat(StoreProfitApplication.startupMode(
        new String[] {"--ADMIN-BOOTSTRAP"},
        Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "true")))
        .isEqualTo(StartupMode.WEB);
    assertThat(StoreProfitApplication.startupMode(
        new String[] {" --admin-bootstrap "},
        Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "true")))
        .isEqualTo(StartupMode.WEB);
  }

  @Test
  void exactCommandWithoutExactEnablementSelectsNonWebDisabledMode() {
    assertThat(StoreProfitApplication.startupMode(
        new String[] {"--admin-bootstrap"}, Map.of()))
        .isEqualTo(StartupMode.DISABLED);
    assertThat(StoreProfitApplication.startupMode(
        new String[] {"--admin-bootstrap"},
        Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "TRUE")))
        .isEqualTo(StartupMode.DISABLED);
  }

  @Test
  void exactCommandAndEnablementSelectBootstrapBeforeSpring() {
    assertThat(StoreProfitApplication.startupMode(
        new String[] {"--admin-bootstrap"},
        Map.of("APP_BOOTSTRAP_ADMIN_ENABLED", "true")))
        .isEqualTo(StartupMode.BOOTSTRAP);
  }

  @Test
  void disabledModeDoesNotConstructCommandAndUnexpectedFailureIsStable() {
    AtomicBoolean invoked = new AtomicBoolean();
    assertThat(StoreProfitApplication.bootstrapResult(StartupMode.DISABLED, () -> {
      invoked.set(true);
      return AdminBootstrapResult.created();
    })).isEqualTo(AdminBootstrapResult.safetyRejected());
    assertThat(invoked).isFalse();

    assertThat(StoreProfitApplication.bootstrapResult(StartupMode.BOOTSTRAP, () -> {
      throw new IllegalStateException("sensitive internal detail");
    })).isEqualTo(AdminBootstrapResult.unexpectedFailure());
  }
}
