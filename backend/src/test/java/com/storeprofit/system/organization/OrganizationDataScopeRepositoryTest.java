package com.storeprofit.system.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyService;
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
          id bigint not null primary key, tenant_id bigint not null, code varchar(64) not null,
          name varchar(160) not null, warehouse_type varchar(32) not null, region_code varchar(32) not null,
          store_supply_allowed tinyint not null default 1, enabled tinyint not null default 1
        )
        """);
    jdbc.execute("""
        create table store_branch (
          id varchar(64) not null primary key, tenant_id bigint not null, brand_id bigint,
          code varchar(80), name varchar(160), area varchar(160), manager varchar(120), manager_phone varchar(40),
          open_date date, status varchar(40), note varchar(255), region_code varchar(40),
          supply_warehouse_id bigint, manager_employee_id varchar(120), cost_account_store_id varchar(64),
          version bigint not null default 0, created_at timestamp, updated_at timestamp,
          unique(tenant_id, code)
        )
        """);
    jdbc.execute("""
        create table employee (
          id varchar(120) not null primary key, tenant_id bigint not null, store_id varchar(64) not null,
          name varchar(120) not null, phone varchar(40), status varchar(40) not null
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
    jdbc.update("""
        insert into employee(id, tenant_id, store_id, name, phone, status)
        values ('e1', 1, 's1', 'Alice', '13800138000', '在职'),
               ('e2', 1, 's2', 'Bob', '0716-1234567', '离职'),
               ('other-e', 2, 'other', 'Mallory', '13900139000', '在职')
        """);
    jdbc.update("""
        insert into warehouse_facility(
          id, tenant_id, code, name, warehouse_type, region_code, store_supply_allowed, enabled
        ) values
          (11, 1, 'JZ', '荆州总仓', 'CENTRAL', 'JINGZHOU', 1, 1),
          (12, 1, 'SD', '山东分仓', 'REGIONAL', 'SHANDONG', 1, 1),
          (13, 2, 'OTHER', '外部仓', 'CENTRAL', 'OTHER', 1, 1)
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
    assertThat(repository.storeHasLinkedData(1L, "link-free")).isFalse();

    jdbc.execute("""
        create table operation_log (
          id bigint not null primary key, tenant_id bigint not null, store_id varchar(64)
        )
        """);
    jdbc.update("insert into operation_log(id, tenant_id, store_id) values (1, 1, 'link-free')");
    assertThat(repository.storeHasLinkedData(1L, "link-free")).isFalse();

    jdbc.execute("""
        create table business_todo (
          id varchar(64) not null primary key, tenant_id bigint not null, store_id varchar(64)
        )
        """);
    jdbc.update("insert into business_todo(id, tenant_id, store_id) values ('todo-1', 1, 'link-free')");
    jdbc.update("insert into business_todo(id, tenant_id, store_id) values ('todo-2', 2, 'tenant-only')");

    assertThat(repository.storeHasLinkedData(1L, "link-free")).isTrue();
    assertThat(repository.storeHasLinkedData(1L, "tenant-only")).isFalse();
  }

  @Test
  void h2StoreUpsertValidatesBrandStatusAndDuplicateCodeBeforeWriting() {
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, null, auditRepository);
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    StoreUpsertRequest valid = new StoreUpsertRequest(
        "new-store", "NEW-001", "新增门店", 1L, "荆州", "李四", "13800138000",
        "2026-07-21", "营业中", "H2 合成数据", "JINGZHOU", null,
        "e1", "s1", null);

    service.upsertStore(boss, valid);

    assertThat(repository.storeCount(1L)).isEqualTo(3);
    assertThat(repository.store(1L, "new-store")).isPresent();
    verify(auditRepository).writeLog(any(), any());

    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "bad-brand", "NEW-002", "错误品牌", 3L, "荆州", "李四", "13800138000",
        "", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BRAND_NOT_FOUND"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "bad-status", "NEW-003", "错误状态", 1L, "荆州", "李四", "13800138000",
        "", "未知状态", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_STATUS_INVALID"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "duplicate-code", "NEW-001", "重复编号", 1L, "荆州", "李四", "13800138000",
        "", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_CODE_DUPLICATE"));
    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "other", "OTHER-001", "跨租户门店", 1L, "荆州", "李四", "13800138000",
        "", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
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

  @Test
  void storeArchiveRejectsDuplicateNameInvalidContactAndMissingRequiredFields() {
    AccessControlService accessControl = mock(AccessControlService.class);
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, null, mock(AuditRepository.class));
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "duplicate-name", "NEW-011", "One", 1L, "荆州", "李四", "13800138000",
        "2026-07-21", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_NAME_DUPLICATE"));

    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "bad-contact", "NEW-012", "联系方式错误门店", 1L, "荆州", "李四", "abc-123",
        "2026-07-21", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_CONTACT_INVALID"));

    assertThatThrownBy(() -> service.upsertStore(boss, new StoreUpsertRequest(
        "missing-code", " ", "缺少编号门店", 1L, "荆州", "李四", "13800138000",
        "2026-07-21", "营业中", "", "JINGZHOU", null, "e1", "s1", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_CODE_REQUIRED"));

    assertThat(repository.storeCount(1L)).isEqualTo(2);
  }

  @Test
  void storeArchiveNeverPhysicallyDeletesEvenWhenTheStoreHasNoBusinessRows() {
    AccessControlService accessControl = mock(AccessControlService.class);
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, null, mock(AuditRepository.class));
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

    assertThatThrownBy(() -> service.deleteStore(boss, "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_DELETE_DISABLED"));

    assertThat(repository.store(1L, "s1")).isPresent();
  }

  @Test
  void createEditAndOptionsUseActiveReferencesAndOptimisticVersion() {
    AccessControlService accessControl = mock(AccessControlService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    WarehouseTopologyService topology = mock(WarehouseTopologyService.class);
    when(topology.normalizeRegion("JINGZHOU")).thenReturn("JINGZHOU");
    when(topology.resolveSupplyWarehouse(1L, "JINGZHOU")).thenReturn(
        new FacilityRow(11L, "JZ", "荆州总仓", "CENTRAL", "JINGZHOU",
            null, null, true, true, true));
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, topology, auditRepository);
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

    StoreArchiveOptionsResponse options = service.storeOptions(boss);
    assertThat(options.regions()).extracting(StoreArchiveOptionsResponse.RegionOption::code)
        .containsExactly("JINGZHOU", "SHANDONG");
    assertThat(options.managers()).extracting(StoreArchiveOptionsResponse.ManagerOption::employeeId)
        .containsExactly("e1");
    assertThat(options.costAccounts()).extracting(StoreArchiveOptionsResponse.CostAccountOption::storeId)
        .containsExactly("s1", "s2");
    assertThat(options.statuses()).extracting(StoreArchiveOptionsResponse.StatusOption::value)
        .containsExactly("营业中", "停用", "停业");

    StoreResponse created = service.createStore(boss, new StoreUpsertRequest(
        null, "NEW-020", "完整档案门店", 1L, null, null, "13800138000",
        "2026-07-24", "营业中", "测试", "JINGZHOU", null,
        "e1", "s1", null));

    assertThat(created.id()).isNotBlank();
    assertThat(created.managerEmployeeId()).isEqualTo("e1");
    assertThat(created.manager()).isEqualTo("Alice");
    assertThat(created.costAccountStoreId()).isEqualTo("s1");
    assertThat(created.costAccountStoreName()).isEqualTo("One");
    assertThat(created.version()).isZero();

    StoreResponse updated = service.updateStore(boss, new StoreUpsertRequest(
        created.id(), "NEW-020", "完整档案门店（已编辑）", 1L, null, null, "0716-1234567",
        "2026-07-24", "营业中", "即时生效", "JINGZHOU", null,
        "e1", created.id(), created.version()));
    assertThat(updated.name()).isEqualTo("完整档案门店（已编辑）");
    assertThat(updated.managerPhone()).isEqualTo("0716-1234567");
    assertThat(updated.costAccountStoreId()).isEqualTo(created.id());
    assertThat(updated.version()).isEqualTo(1L);

    StoreResponse disabled = service.changeStoreStatus(
        boss, created.id(), new StoreStatusChangeRequest("停用", updated.version()));
    assertThat(disabled.status()).isEqualTo("停用");
    assertThat(disabled.version()).isEqualTo(2L);
    StoreResponse reenabled = service.changeStoreStatus(
        boss, created.id(), new StoreStatusChangeRequest("营业中", disabled.version()));
    assertThat(reenabled.status()).isEqualTo("营业中");
    assertThat(reenabled.version()).isEqualTo(3L);

    assertThatThrownBy(() -> service.updateStore(boss, new StoreUpsertRequest(
        created.id(), "NEW-020", "过期编辑", 1L, null, null, "13800138000",
        "2026-07-24", "营业中", "", "JINGZHOU", null,
        "e1", created.id(), 0L)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_VERSION_CONFLICT"));
  }

  @Test
  void legacyStoreCanBeDisabledButMustCompleteItsArchiveBeforeReenable() {
    AccessControlService accessControl = mock(AccessControlService.class);
    OrganizationService service = new OrganizationService(
        repository, null, accessControl, null, null, mock(AuditRepository.class));
    AuthUser boss = new AuthUser(7L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

    StoreResponse disabled = service.changeStoreStatus(
        boss, "s1", new StoreStatusChangeRequest("停用", 0L));
    assertThat(disabled.status()).isEqualTo("停用");

    assertThatThrownBy(() -> service.changeStoreStatus(
        boss, "s1", new StoreStatusChangeRequest("营业中", disabled.version())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_ARCHIVE_INCOMPLETE"));
  }

  public static String dateFormat(java.sql.Date value, String ignoredPattern) {
    return value == null ? null : value.toString();
  }
}
