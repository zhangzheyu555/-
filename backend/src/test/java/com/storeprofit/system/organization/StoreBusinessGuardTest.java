package com.storeprofit.system.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class StoreBusinessGuardTest {

  @Test
  void inactiveStoreCannotCreateNewBusinessAndReenableRestoresPermission() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) primary key,
          tenant_id bigint not null,
          name varchar(160) not null,
          status varchar(40) not null
        )
        """);
    jdbc.update("""
        insert into store_branch(id, tenant_id, name, status)
        values ('active', 1, '营业门店', '营业中'), ('inactive', 1, '停用门店', '停用')
        """);
    AuditRepository audit = mock(AuditRepository.class);
    StoreBusinessGuard guard = new StoreBusinessGuard(jdbc, audit);
    AuthUser manager = new AuthUser(
        7L, 1L, "测试租户", "manager", "", "测试店长", "STORE_MANAGER", "inactive", true);

    assertThatCode(() -> guard.requireActive(manager, "active", "叫货单"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.requireActive(manager, "inactive", "叫货单"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException inactive = (BusinessException) error;
          assertThat(inactive.getCode()).isEqualTo("STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN");
          assertThat(inactive.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(inactive.getMessage()).contains("停用门店", "不能创建新的叫货单", "重新启用");
        });
    verify(audit).writePermissionDenied(
        eq(manager),
        eq("创建叫货单"),
        eq("STORE"),
        eq("inactive"),
        eq("inactive"),
        contains("门店已停用")
    );

    jdbc.update("update store_branch set status = '营业中' where id = 'inactive'");
    assertThatCode(() -> guard.requireActive(manager, "inactive", "叫货单"))
        .doesNotThrowAnyException();
  }
}
