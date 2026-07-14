package com.storeprofit.system.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeprofit.system.eleme.ElemePlatformAdapter;
import com.storeprofit.system.eleme.ElemeProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformAdapterTest {
  @Test
  void meituanNeverReportsReadyWithoutARealIntegration() {
    PlatformAdapterStatus status = new MeituanPlatformAdapter().status();

    assertThat(status.platform()).isEqualTo("MEITUAN");
    assertThat(status.orderSync()).isEqualTo(PlatformSyncState.NOT_CONFIGURED);
    assertThat(status.webhook()).isEqualTo(PlatformSyncState.NOT_CONFIGURED);
  }

  @Test
  void elemeCapabilitiesAreReportedIndependently() {
    ElemeProperties properties = new ElemeProperties();
    properties.setAppKey("test-app");
    properties.setAppSecret("test-app-secret");
    properties.setAccessToken("test-token");

    PlatformAdapterStatus status = new ElemePlatformAdapter(properties).status();

    assertThat(status.orderSync()).isEqualTo(PlatformSyncState.READY);
    assertThat(status.webhook()).isEqualTo(PlatformSyncState.NOT_CONFIGURED);
  }

  @Test
  void registryReturnsStablePlatformOrder() {
    ElemeProperties properties = new ElemeProperties();
    PlatformAdapterRegistry registry = new PlatformAdapterRegistry(List.of(
        new MeituanPlatformAdapter(),
        new ElemePlatformAdapter(properties)
    ));

    assertThat(registry.statuses()).extracting(PlatformAdapterStatus::platform)
        .containsExactly("ELEME", "MEITUAN");
  }
}
