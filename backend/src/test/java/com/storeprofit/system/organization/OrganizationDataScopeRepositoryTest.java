package com.storeprofit.system.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class OrganizationDataScopeRepositoryTest {
  private JdbcTemplate jdbc;
  private OrganizationRepository repository;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create alias if not exists date_format for '"
        + OrganizationDataScopeRepositoryTest.class.getName() + ".dateFormat'");
    jdbc.execute("""
        create table brand (
          id bigint not null primary key, tenant_id bigint not null, code varchar(40),
          name varchar(120), color varchar(40), sort_order int not null default 0
        )
        """);
    jdbc.execute("""
        create table warehouse_facility (
          id bigint not null primary key, tenant_id bigint not null, name varchar(160) not null
        )
        """);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) not null primary key, tenant_id bigint not null, brand_id bigint,
          code varchar(80), name varchar(160), area varchar(160), manager varchar(120), manager_phone varchar(40),
          open_date date, status varchar(40), note varchar(255), region_code varchar(40),
          supply_warehouse_id bigint, created_at timestamp, updated_at timestamp,
          unique(tenant_id, code)
        )
        """);
    jdbc.update("""
        insert into brand(id, tenant_id, code, name, color, sort_order)
        values (1, 1, 'A', 'Alpha', '#111', 1), (2, 1, 'B', 'Beta', '#222', 2),
               (3, 2, 'X', 'Other', '#333', 1)
        """);
    jdbc.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name, area, manager, open_date, status, note)
        values ('s1', 1, 1, '001', 'One', 'A', 'Alice', '2025-01-01', '营业中', null),
               ('s2', 1, 2, '002', 'Two', 'B', 'Bob', '2025-02-01', '营业中', null),
               ('other', 2, 3, '099', 'Other', 'C', 'Mallory', '2025-03-01', '营业中', null)
        """);
    repository = new OrganizationRepository(jdbc);
  }

  @Test
  void storeAndBrandQueriesHonorConfiguredStoreListAndNone() {
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("s2"));

    assertThat(repository.stores(1L, scope)).extracting(StoreResponse::id).containsExactly("s2");
    assertThat(repository.brands(1L, scope)).extracting(BrandResponse::id).containsExactly(2L);
    assertThat(repository.stores(1L, DataScope.none())).isEmpty();
    assertThat(repository.brands(1L, DataScope.none())).isEmpty();
  }

  @Test
  void storeDeleteLinkCheckFindsBusinessRowsButIgnoresAuditRows() {
    assertThat(repository.storeHasLinkedData(1L, "s1")).isFalse();

    jdbc.execute("""
        create table operation_log (
          id bigint not null primary key, tenant_id bigint not null, store_id varchar(64)
        )
        """);
    jdbc.update("insert into operation_log(id, tenant_id, store_id) values (1, 1, 's1')");
    assertThat(repository.storeHasLinkedData(1L, "s1")).isFalse();

    jdbc.execute("""
        create table business_todo (
          id varchar(64) not null primary key, tenant_id bigint not null, store_id varchar(64)
        )
        """);
    jdbc.update("insert into business_todo(id, tenant_id, store_id) values ('todo-1', 1, 's1')");
    jdbc.update("insert into business_todo(id, tenant_id, store_id) values ('todo-2', 2, 's2')");

    assertThat(repository.storeHasLinkedData(1L, "s1")).isTrue();
    assertThat(repository.storeHasLinkedData(1L, "s2")).isFalse();
  }

  @Test
  void h2StoreUpsertValidatesBrandStatusAndDuplicateCodeBeforeWriting() {
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, null, auditRepository);
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    StoreUpsertRequest valid = new StoreUpsertRequest(
        "new-store", "NEW-001", "新增门店", 1L, "荆州", "李四", "2026-07-21", "营业中", "H2 合成数据");

    service.upsertStore(boss, valid);

    assertThat(repository.storeCount(1L)).isEqualTo(3);
    assertThat(repository.store(1L, "new-store")).isPresent();
    verify(auditRepository).writeLog(any(), any());

    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "bad-brand", "NEW-002", "错误品牌", 3L, "荆州", "", "", "营业中", "")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BRAND_NOT_FOUND"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "bad-status", "NEW-003", "错误状态", 1L, "荆州", "", "", "未知状态", "")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_STATUS_INVALID"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "duplicate-code", "NEW-001", "重复编号", 1L, "荆州", "", "", "营业中", "")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_CODE_DUPLICATE"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "other", "OTHER-001", "跨租户门店", 1L, "荆州", "", "", "营业中", "")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> {
          BusinessException forbidden = (BusinessException) error;
          assertThat(forbidden.getCode()).isEqualTo("FORBIDDEN");
          assertThat(forbidden.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });

    assertThat(repository.storeCount(1L)).isEqualTo(3);
    verify(auditRepository).writePermissionDenied(
        eq(boss), eq("维护门店档案"), eq("API"), eq("other"), eq("other"), contains("不属于当前企业"));
  }

  public static String dateFormat(java.sql.Date value, String ignoredPattern) {
    return value == null ? null : value.toString();
  }
}
