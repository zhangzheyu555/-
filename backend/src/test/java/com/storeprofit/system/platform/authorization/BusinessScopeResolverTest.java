package com.storeprofit.system.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeRepository.StoreIdentity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BusinessScopeResolverTest {
  private final AuthRepository authRepository = mock(AuthRepository.class);
  private final DataScopeService dataScopeService = mock(DataScopeService.class);
  private final BusinessScopeRepository repository = mock(BusinessScopeRepository.class);
  private final AuditRepository auditRepository = mock(AuditRepository.class);
  private final BusinessScopeResolver resolver = new BusinessScopeResolver(
      authRepository, dataScopeService, repository, auditRepository);
  private final AuthUser manager = new AuthUser(
      7L, 1L, "测试企业", "rg1", "hash", "荆州之星店店长",
      "STORE_MANAGER", "rg1", true, 2L);

  @BeforeEach
  void configureValidManager() {
    when(authRepository.assignedStoreScope(1L, 7L)).thenReturn(List.of("rg1"));
    when(dataScopeService.scope(manager, DataScopeDomains.STORE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("rg1")));
    when(dataScopeService.scope(manager, DataScopeDomains.FINANCE))
        .thenReturn(new DataScope(DataScopeModes.OWN_STORE, List.of("rg1")));
    when(repository.store(1L, "rg1"))
        .thenReturn(Optional.of(new StoreIdentity("rg1", "荆州之星店", 9L, "茹菓")));
  }

  @Test
  void managerWithoutParametersIsAutomaticallyBoundToOnlyStoreAndBrand() {
    BusinessScope result = resolver.resolve(
        manager, DataScopeDomains.FINANCE, null, null, "查看利润数据");

    assertThat(result.storeId()).isEqualTo("rg1");
    assertThat(result.storeName()).isEqualTo("荆州之星店");
    assertThat(result.brandId()).isEqualTo(9L);
    assertThat(result.brandName()).isEqualTo("茹菓");
    assertThat(result.dataScope().mode()).isEqualTo(DataScopeModes.OWN_STORE);
  }

  @Test
  void managerCannotSubmitAnotherStoreOrBrand() {
    assertThatThrownBy(() -> resolver.resolve(
        manager, DataScopeDomains.FINANCE, "other-store", null, "查看利润数据"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    assertThatThrownBy(() -> resolver.resolve(
        manager, DataScopeDomains.FINANCE, null, 10L, "查看利润数据"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  @Test
  void managerWithZeroMultipleOrInconsistentBindingsGetsConfigurationError() {
    when(authRepository.assignedStoreScope(1L, 7L)).thenReturn(List.of("rg1", "rg2"));

    assertThatThrownBy(() -> resolver.sessionScope(manager))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("STORE_MANAGER_SCOPE_INVALID");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(ex.getMessage()).contains("必须且只能绑定一家门店");
        });
  }

  @Test
  void bossCanKeepRequestedStoreAndBrand() {
    AuthUser boss = new AuthUser(
        1L, 1L, "测试企业", "boss", "hash", "老板", "BOSS", null, true, 3L);
    when(dataScopeService.scope(boss, DataScopeDomains.FINANCE)).thenReturn(DataScope.all());
    when(repository.store(1L, "rg2"))
        .thenReturn(Optional.of(new StoreIdentity("rg2", "万达二店", 10L, "另一品牌")));

    BusinessScope result = resolver.resolve(
        boss, DataScopeDomains.FINANCE, "rg2", 10L, "查看利润数据");

    assertThat(result.storeId()).isEqualTo("rg2");
    assertThat(result.brandId()).isEqualTo(10L);
    assertThat(result.dataScope().allowsAllStores()).isTrue();
  }

  @Test
  void bossCanQueryAllStoresWithoutOptionalStoreOrBrandFilters() {
    AuthUser boss = new AuthUser(
        1L, 1L, "测试企业", "boss", "hash", "老板", "BOSS", null, true, 3L);
    when(dataScopeService.scope(boss, DataScopeDomains.FINANCE)).thenReturn(DataScope.all());

    BusinessScope result = resolver.resolve(
        boss, DataScopeDomains.FINANCE, null, null, "查看利润数据");

    assertThat(result.storeId()).isNull();
    assertThat(result.brandId()).isNull();
    assertThat(result.dataScope().allowsAllStores()).isTrue();
  }

  @Test
  void financeScopeBrandMismatchKeepsTheRequestedMonthInTheDenialAudit() {
    AuthUser boss = new AuthUser(
        1L, 1L, "测试企业", "boss", "hash", "老板", "BOSS", null, true, 3L);
    when(dataScopeService.scope(boss, DataScopeDomains.FINANCE)).thenReturn(DataScope.all());
    when(repository.store(1L, "rg1"))
        .thenReturn(Optional.of(new StoreIdentity("rg1", "荆州之星店", 9L, "茹菓")));

    assertThatThrownBy(() -> resolver.resolve(
        boss, DataScopeDomains.FINANCE, "rg1", 10L, "查看报销数据", "2026-08"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    verify(auditRepository).writePermissionDenied(
        eq(boss), eq("查看报销数据"), eq("BRAND"), eq("10"), eq("rg1"), eq("2026-08"), anyString());
  }
}
