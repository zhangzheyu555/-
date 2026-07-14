package com.storeprofit.system.eleme;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElemePropertiesDataScopeTest {

  @Test
  void fullScopeKeepsLegacyEntriesButRestrictedScopeRequiresStoreMapping() {
    ElemeProperties properties = new ElemeProperties();
    properties.setShops(List.of(
        "legacy-shop:旧配置门店",
        "shop-a:甲店:store-a",
        " shop-b : 乙店 : store-b ",
        ":无效门店:store-a"
    ));

    assertThat(properties.resolveShops(null))
        .extracting(ElemeProperties.ShopMapping::shopId)
        .containsExactly("legacy-shop", "shop-a", "shop-b");
    assertThat(properties.resolveShops(List.of("store-a")))
        .containsExactly(new ElemeProperties.ShopMapping("shop-a", "甲店", "store-a"));
    assertThat(properties.resolveShops(List.of("store-missing"))).isEmpty();
    assertThat(properties.resolveShops(List.of())).isEmpty();
  }

  @Test
  void shopCountUsesSameFailClosedFiltering() {
    ElemeProperties properties = new ElemeProperties();
    properties.setShops(List.of(
        "legacy-shop:旧配置门店",
        "shop-a:甲店:store-a",
        "shop-b:乙店:store-b"
    ));

    assertThat(properties.shopCount(null)).isEqualTo(3);
    assertThat(properties.shopCount(List.of("store-a"))).isEqualTo(1);
    assertThat(properties.shopCount(List.of("store-a", "store-b"))).isEqualTo(2);
  }

  @Test
  void restrictedSummaryDoesNotCallPlatformForUnmappedLegacyShop() {
    ElemeProperties properties = new ElemeProperties();
    properties.setAppKey("configured-app");
    properties.setAppSecret("configured-secret");
    properties.setAccessToken("configured-token");
    properties.setShops(List.of("legacy-shop:旧配置门店"));
    ElemeOrderService service = new ElemeOrderService(properties);

    ElemeSummaryResponse recent = service.summary(7, List.of("store-a"));
    ElemeSummaryResponse monthly = service.summaryForMonth(
        YearMonth.now(ZoneId.of("Asia/Shanghai")).toString(), List.of("store-a"));

    assertThat(recent.mode()).isEqualTo("UNCONFIGURED");
    assertThat(recent.shops()).isEmpty();
    assertThat(monthly.mode()).isEqualTo("UNCONFIGURED");
    assertThat(monthly.shops()).isEmpty();
  }
}
