package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.ProfitEntryRequest;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreUpsertRequest;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationStatusServiceTest {
  private JdbcTemplate jdbcTemplate;
  private StorageService storageService;
  private OrganizationRepository organizationRepository;
  private FinanceRepository financeRepository;
  private MigrationStatusService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("""
        create table kv_storage (
          storage_key varchar(120) not null primary key,
          storage_value longtext not null,
          updated_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          operator_id bigint null,
          operator_name varchar(120) null,
          action varchar(80) not null,
          target_type varchar(80) not null,
          target_id varchar(120) null,
          store_id varchar(64) null,
          month char(7) null,
          before_json longtext null,
          after_json longtext null,
          reason varchar(255) null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table salary_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) not null,
          employee_name varchar(120) not null,
          position varchar(80) null,
          attendance varchar(80) null,
          gross decimal(14,2) not null default 0,
          normal_hours decimal(10,2) not null default 0,
          ot_hours decimal(10,2) not null default 0,
          work_hours decimal(10,2) not null default 0,
          vacation_left decimal(10,2) not null default 0,
          vacation_note varchar(255) null,
          base decimal(14,2) not null default 0,
          social decimal(14,2) not null default 0,
          post decimal(14,2) not null default 0,
          meal decimal(14,2) not null default 0,
          full_attendance decimal(14,2) not null default 0,
          commission decimal(14,2) not null default 0,
          overtime decimal(14,2) not null default 0,
          seniority decimal(14,2) not null default 0,
          late_night decimal(14,2) not null default 0,
          subsidy decimal(14,2) not null default 0,
          performance decimal(14,2) not null default 0,
          deduct_uniform decimal(14,2) not null default 0,
          return_uniform decimal(14,2) not null default 0,
          updated_at timestamp null default null
        )
        """);
    jdbcTemplate.execute("""
        create table expense_claim (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) null,
          amount decimal(14,2) not null default 0,
          category varchar(80) null,
          reason text null,
          status varchar(40) not null default '待审核',
          image_url varchar(500) null,
          submitted_by bigint null,
          reviewed_by bigint null,
          reviewed_at timestamp null,
          updated_at timestamp null default null
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          inspection_date date not null,
          inspector varchar(120) null,
          brand varchar(120) null,
          full_score decimal(8,2) not null default 100,
          score decimal(8,2) not null default 100,
          passed tinyint(1) not null default 1,
          deductions_json longtext null,
          redlines_json longtext null,
          photos_json longtext null,
          note text null,
          updated_at timestamp null default null
        )
        """);
    storageService = mock(StorageService.class);
    organizationRepository = mock(OrganizationRepository.class);
    financeRepository = mock(FinanceRepository.class);
    service = new MigrationStatusService(jdbcTemplate, storageService, organizationRepository, financeRepository, new ObjectMapper());
  }

  @Test
  void reportsLegacyBusinessKeysThatNeedStructuredMigration() {
    jdbcTemplate.update("insert into kv_storage(storage_key, storage_value) values (?, ?)", "stores", "[{\"id\":\"s1\"}]");
    jdbcTemplate.update("insert into kv_storage(storage_key, storage_value) values (?, ?)", "expenses", "[{\"id\":\"e1\"}]");
    jdbcTemplate.update("insert into kv_storage(storage_key, storage_value) values (?, ?)", "schema_v", "4");

    MigrationStatusResponse response = service.status(user("BOSS"));

    assertThat(response.migrationRequired()).isTrue();
    assertThat(response.businessKeyCount()).isEqualTo(6);
    assertThat(response.presentBusinessKeyCount()).isEqualTo(2);
    assertThat(response.legacyBusinessKeys())
        .extracting(LegacyKvKeyStatusResponse::key)
        .containsExactly("stores", "entries", "salary", "expenses", "inspections", "logs");

    LegacyKvKeyStatusResponse stores = statusFor(response, "stores");
    assertThat(stores.targetTable()).isEqualTo("store_branch");
    assertThat(stores.present()).isTrue();
    assertThat(stores.valueBytes()).isGreaterThan(0);
    assertThat(stores.migrationState()).isEqualTo("NEEDS_STRUCTURED_MIGRATION");

    LegacyKvKeyStatusResponse entries = statusFor(response, "entries");
    assertThat(entries.targetTable()).isEqualTo("profit_entry");
    assertThat(entries.present()).isFalse();
    assertThat(entries.valueBytes()).isZero();
    assertThat(entries.migrationState()).isEqualTo("NOT_PRESENT");
  }

  @Test
  void rejectsNonOwnerRoles() {
    assertThatThrownBy(() -> service.status(user("FINANCE")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("迁移状态");
  }

  @Test
  void previewsLegacyKvMappingWithoutRunningMigration() {
    jdbcTemplate.update("insert into kv_storage(storage_key, storage_value) values (?, ?)", "salary", "[{\"id\":\"pay1\"}]");

    LegacyKvMigrationPreviewResponse preview = service.legacyKvPreview(user("BOSS"));

    assertThat(preview.automaticRunAvailable()).isFalse();
    assertThat(preview.businessKeyCount()).isEqualTo(6);
    assertThat(preview.actionableKeyCount()).isEqualTo(1);
    assertThat(preview.totalValueBytes()).isGreaterThan(0);
    assertThat(preview.items())
        .extracting(LegacyKvMigrationPreviewItemResponse::key)
        .containsExactly("stores", "entries", "salary", "expenses", "inspections", "logs");

    LegacyKvMigrationPreviewItemResponse salary = previewItemFor(preview, "salary");
    assertThat(salary.targetTable()).isEqualTo("salary_record");
    assertThat(salary.present()).isTrue();
    assertThat(salary.automaticMigrationReady()).isFalse();
    assertThat(salary.plannedAction()).isEqualTo("MAP_TO_STRUCTURED_TABLE");

    LegacyKvMigrationPreviewItemResponse logs = previewItemFor(preview, "logs");
    assertThat(logs.present()).isFalse();
    assertThat(logs.plannedAction()).isEqualTo("NOOP");
  }

  @Test
  void previewsBrowserStorageSnapshotWithoutWritingToDatabase() {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("stores", "[{\"id\":\"s1\"}]");
    entries.put("accounts", "[{\"username\":\"old\"}]");
    entries.put("schema_v", "4");
    entries.put("theme", "dark");

    BrowserStoragePreviewResponse preview = service.browserStoragePreview(
        user("BOSS"),
        new BrowserStoragePreviewRequest(entries)
    );

    assertThat(preview.migrationRequired()).isTrue();
    assertThat(preview.submittedKeyCount()).isEqualTo(4);
    assertThat(preview.businessKeyCount()).isEqualTo(1);
    assertThat(preview.blockedKeyCount()).isEqualTo(1);
    assertThat(preview.ignoredKeyCount()).isEqualTo(2);
    assertThat(preview.totalBusinessValueBytes()).isGreaterThan(0);

    BrowserStoragePreviewItemResponse stores = browserItemFor(preview, "stores");
    assertThat(stores.category()).isEqualTo("BUSINESS_DATA");
    assertThat(stores.targetTable()).isEqualTo("store_branch");
    assertThat(stores.plannedAction()).isEqualTo("UPLOAD_TO_MYSQL");
    assertThat(stores.accepted()).isTrue();

    BrowserStoragePreviewItemResponse accounts = browserItemFor(preview, "accounts");
    assertThat(accounts.category()).isEqualTo("SENSITIVE_AUTH");
    assertThat(accounts.targetTable()).isNull();
    assertThat(accounts.plannedAction()).isEqualTo("BLOCKED");
    assertThat(accounts.accepted()).isFalse();

    BrowserStoragePreviewItemResponse schema = browserItemFor(preview, "schema_v");
    assertThat(schema.category()).isEqualTo("COMPATIBILITY_METADATA");
    assertThat(schema.plannedAction()).isEqualTo("IGNORE");

    BrowserStoragePreviewItemResponse theme = browserItemFor(preview, "theme");
    assertThat(theme.category()).isEqualTo("UNKNOWN");
    assertThat(theme.plannedAction()).isEqualTo("IGNORE");
  }

  @Test
  void browserStoragePreviewRejectsNonOwnerRoles() {
    assertThatThrownBy(() -> service.browserStoragePreview(user("FINANCE"), new BrowserStoragePreviewRequest(Map.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void runsBrowserStorageMigrationByWritingOnlyBusinessKeys() {
    AuthUser boss = user("BOSS");
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("stores", "[{\"id\":\"s1\"}]");
    entries.put("accounts", "[{\"username\":\"old\"}]");
    entries.put("theme", "dark");

    BrowserStorageMigrationRunResponse response = service.browserStorageRun(
        boss,
        new BrowserStoragePreviewRequest(entries)
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.submittedKeyCount()).isEqualTo(3);
    assertThat(response.writtenKeyCount()).isEqualTo(1);
    assertThat(response.blockedKeyCount()).isEqualTo(1);
    assertThat(response.ignoredKeyCount()).isEqualTo(1);

    BrowserStorageMigrationRunItemResponse stores = runItemFor(response, "stores");
    assertThat(stores.result()).isEqualTo("WRITTEN_TO_MYSQL");
    assertThat(stores.accepted()).isTrue();

    BrowserStorageMigrationRunItemResponse accounts = runItemFor(response, "accounts");
    assertThat(accounts.result()).isEqualTo("BLOCKED");
    assertThat(accounts.accepted()).isFalse();

    verify(storageService).set(boss, "stores", "[{\"id\":\"s1\"}]");
    verifyNoMoreInteractions(storageService);
  }

  @Test
  void browserStorageRunRejectsNonOwnerRolesBeforeWriting() {
    assertThatThrownBy(() -> service.browserStorageRun(
        user("FINANCE"),
        new BrowserStoragePreviewRequest(Map.of("stores", "[]"))
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    verifyNoInteractions(storageService);
  }

  @Test
  void runsLegacyKvStoresMigrationIntoStructuredStoreTableAndAuditLog() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "stores",
        """
        [
          {"id":"s1","brand":"霸王茶姬","name":"保利店","code":"4401040005","area":"广州","manager":"张三","note":"真实门店"},
          {"id":"s2","brand":"霸王茶姬","name":"万泰汇店"}
        ]
        """
    );
    org.mockito.Mockito.when(organizationRepository.ensureBrand(
            org.mockito.Mockito.eq(boss.tenantId()),
            org.mockito.Mockito.anyString(),
            org.mockito.Mockito.eq("霸王茶姬"),
            org.mockito.Mockito.eq("#64748b"),
            org.mockito.Mockito.eq(900)
        ))
        .thenReturn(99L);

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("stores"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.requestedKeyCount()).isEqualTo(1);
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    assertThat(response.failedKeyCount()).isZero();
    assertThat(response.items()).hasSize(1);
    LegacyKvMigrationRunItemResponse stores = response.items().getFirst();
    assertThat(stores.key()).isEqualTo("stores");
    assertThat(stores.targetTable()).isEqualTo("store_branch");
    assertThat(stores.result()).isEqualTo("MIGRATED");
    assertThat(stores.migratedRecordCount()).isEqualTo(2);

    ArgumentCaptor<StoreUpsertRequest> captor = ArgumentCaptor.forClass(StoreUpsertRequest.class);
    verify(organizationRepository, org.mockito.Mockito.times(2))
        .upsertStore(org.mockito.Mockito.eq(boss.tenantId()), captor.capture());
    List<StoreUpsertRequest> requests = captor.getAllValues();
    assertThat(requests).extracting(StoreUpsertRequest::id).containsExactly("s1", "s2");
    assertThat(requests.getFirst().brandId()).isEqualTo(99L);
    assertThat(requests.getFirst().code()).isEqualTo("4401040005");
    assertThat(requests.getFirst().name()).isEqualTo("保利店");
    assertThat(requests.getFirst().area()).isEqualTo("广州");
    assertThat(requests.getFirst().manager()).isEqualTo("张三");

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'legacy_kv_structured_migration' and target_type = 'store_branch'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void legacyKvRunMarksUnknownKeysUnsupported() {
    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        user("BOSS"),
        new LegacyKvMigrationRunRequest(List.of("unknown"))
    );

    assertThat(response.executed()).isFalse();
    assertThat(response.migratedKeyCount()).isZero();
    assertThat(response.skippedKeyCount()).isEqualTo(1);
    assertThat(response.items().getFirst().result()).isEqualTo("UNSUPPORTED");
    verifyNoInteractions(organizationRepository);
  }

  @Test
  void runsLegacyKvEntriesMigrationIntoStructuredProfitTable() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "entries",
        """
        {
          "s1|2026-05": {"rev":123.45,"material":10,"labor":20,"rent":30,"utility":4,"commission":5,"other":6,"note":"legacy"},
          "missing|2026-05": {"rev":999}
        }
        """
    );
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "s1")).thenReturn(true);
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "missing")).thenReturn(false);

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("entries"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    LegacyKvMigrationRunItemResponse entries = response.items().getFirst();
    assertThat(entries.key()).isEqualTo("entries");
    assertThat(entries.targetTable()).isEqualTo("profit_entry");
    assertThat(entries.result()).isEqualTo("MIGRATED");
    assertThat(entries.migratedRecordCount()).isEqualTo(1);
    assertThat(entries.message()).contains("skipped=1");

    ArgumentCaptor<ProfitEntryRequest> captor = ArgumentCaptor.forClass(ProfitEntryRequest.class);
    verify(financeRepository).upsert(org.mockito.Mockito.eq(boss.tenantId()), captor.capture(), org.mockito.Mockito.eq(boss.id()));
    ProfitEntryRequest request = captor.getValue();
    assertThat(request.storeId()).isEqualTo("s1");
    assertThat(request.month()).isEqualTo("2026-05");
    assertThat(request.sales()).isEqualByComparingTo(new BigDecimal("123.45"));
    assertThat(request.material()).isEqualByComparingTo(new BigDecimal("10"));
    assertThat(request.labor()).isEqualByComparingTo(new BigDecimal("20"));
    assertThat(request.rent()).isEqualByComparingTo(new BigDecimal("30"));
    assertThat(request.utility()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(request.commission()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(request.expOther()).isEqualByComparingTo(new BigDecimal("6"));
    assertThat(request.note()).isEqualTo("legacy");

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'legacy_kv_structured_migration' and target_type = 'profit_entry'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void runsLegacyKvSalaryMigrationIntoStructuredSalaryTable() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "salary",
        """
        [
          {
            "id":"rg1-e1",
            "sid":"rg1",
            "month":"2026-05",
            "name":"李瑜",
            "position":"店长",
            "attendance":"27",
            "gross":5755,
            "normalHours":216,
            "otHours":2.75,
            "workHours":218.75,
            "vacationLeft":1,
            "vacationNote":"余假1天",
            "base":1900,
            "social":800,
            "post":1300,
            "meal":300,
            "full":200,
            "commission":758,
            "overtime":55,
            "seniority":400,
            "latenight":30,
            "subsidy":0,
            "performance":100,
            "deductUniform":20,
            "returnUniform":10
          },
          {"id":"missing-e1","sid":"missing","month":"2026-05","name":"跳过门店","gross":999}
        ]
        """
    );
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "rg1")).thenReturn(true);
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "missing")).thenReturn(false);

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("salary"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    LegacyKvMigrationRunItemResponse salary = response.items().getFirst();
    assertThat(salary.key()).isEqualTo("salary");
    assertThat(salary.targetTable()).isEqualTo("salary_record");
    assertThat(salary.result()).isEqualTo("MIGRATED");
    assertThat(salary.migratedRecordCount()).isEqualTo(1);
    assertThat(salary.message()).contains("skipped=1");

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select * from salary_record where id = ?",
        "rg1-e1"
    );
    assertThat(row.get("TENANT_ID")).isEqualTo(1L);
    assertThat(row.get("STORE_ID")).isEqualTo("rg1");
    assertThat(row.get("MONTH")).isEqualTo("2026-05");
    assertThat(row.get("EMPLOYEE_NAME")).isEqualTo("李瑜");
    assertThat(row.get("POSITION")).isEqualTo("店长");
    assertThat(row.get("ATTENDANCE")).isEqualTo("27");
    assertThat(row.get("GROSS")).isEqualTo(new BigDecimal("5755.00"));
    assertThat(row.get("NORMAL_HOURS")).isEqualTo(new BigDecimal("216.00"));
    assertThat(row.get("OT_HOURS")).isEqualTo(new BigDecimal("2.75"));
    assertThat(row.get("WORK_HOURS")).isEqualTo(new BigDecimal("218.75"));
    assertThat(row.get("VACATION_LEFT")).isEqualTo(new BigDecimal("1.00"));
    assertThat(row.get("VACATION_NOTE")).isEqualTo("余假1天");
    assertThat(row.get("FULL_ATTENDANCE")).isEqualTo(new BigDecimal("200.00"));
    assertThat(row.get("LATE_NIGHT")).isEqualTo(new BigDecimal("30.00"));
    assertThat(row.get("DEDUCT_UNIFORM")).isEqualTo(new BigDecimal("20.00"));
    assertThat(row.get("RETURN_UNIFORM")).isEqualTo(new BigDecimal("10.00"));

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'legacy_kv_structured_migration' and target_type = 'salary_record'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void runsLegacyKvExpensesMigrationIntoStructuredExpenseTable() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "expenses",
        """
        [
          {
            "id":"exp1",
            "sid":"rg1",
            "month":"2026-05",
            "amount":128.5,
            "category":"物料采购",
            "note":"牛奶采购",
            "img":"data:image/jpeg;base64,abc",
            "status":"pending"
          },
          {"id":"exp2","sid":"missing","month":"2026-05","amount":999,"category":"其他","status":"done"}
        ]
        """
    );
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "rg1")).thenReturn(true);
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "missing")).thenReturn(false);

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("expenses"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    LegacyKvMigrationRunItemResponse expenses = response.items().getFirst();
    assertThat(expenses.key()).isEqualTo("expenses");
    assertThat(expenses.targetTable()).isEqualTo("expense_claim");
    assertThat(expenses.result()).isEqualTo("MIGRATED");
    assertThat(expenses.migratedRecordCount()).isEqualTo(1);
    assertThat(expenses.message()).contains("skipped=1");

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select * from expense_claim where id = ?",
        "exp1"
    );
    assertThat(row.get("TENANT_ID")).isEqualTo(1L);
    assertThat(row.get("STORE_ID")).isEqualTo("rg1");
    assertThat(row.get("MONTH")).isEqualTo("2026-05");
    assertThat(row.get("AMOUNT")).isEqualTo(new BigDecimal("128.50"));
    assertThat(row.get("CATEGORY")).isEqualTo("物料采购");
    assertThat(row.get("REASON")).isEqualTo("牛奶采购");
    assertThat(row.get("STATUS")).isEqualTo("待审核");
    assertThat(row.get("IMAGE_URL")).isEqualTo("data:image/jpeg;base64,abc");
    assertThat(row.get("SUBMITTED_BY")).isEqualTo(1L);

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'legacy_kv_structured_migration' and target_type = 'expense_claim'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void runsLegacyKvInspectionsMigrationIntoStructuredInspectionTable() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "inspections",
        """
        [
          {
            "id":"insp1",
            "sid":"rg1",
            "brand":"茹果奶茶",
            "date":"2026-05-21",
            "inspector":"督导A",
            "fullScore":100,
            "deductions":[{"dim":"卫生","item":"吧台","issue":"台面污渍","deduct":8}],
            "redlines":[{"item":"食品安全红线"}],
            "photos":[{"id":"p1","src":"data:image/jpeg;base64,abc","category":"吧台"}],
            "note":"需要复查"
          },
          {"id":"insp2","sid":"missing","date":"2026-05-22","deductions":[]}
        ]
        """
    );
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "rg1")).thenReturn(true);
    org.mockito.Mockito.when(financeRepository.storeExists(boss.tenantId(), "missing")).thenReturn(false);

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("inspections"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    LegacyKvMigrationRunItemResponse inspections = response.items().getFirst();
    assertThat(inspections.key()).isEqualTo("inspections");
    assertThat(inspections.targetTable()).isEqualTo("inspection_record");
    assertThat(inspections.result()).isEqualTo("MIGRATED");
    assertThat(inspections.migratedRecordCount()).isEqualTo(1);
    assertThat(inspections.message()).contains("skipped=1");

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select * from inspection_record where id = ?",
        "insp1"
    );
    assertThat(row.get("TENANT_ID")).isEqualTo(1L);
    assertThat(row.get("STORE_ID")).isEqualTo("rg1");
    assertThat(row.get("INSPECTION_DATE").toString()).isEqualTo("2026-05-21");
    assertThat(row.get("INSPECTOR")).isEqualTo("督导A");
    assertThat(row.get("BRAND")).isEqualTo("茹果奶茶");
    assertThat(row.get("FULL_SCORE")).isEqualTo(new BigDecimal("100.00"));
    assertThat(row.get("SCORE")).isEqualTo(new BigDecimal("92.00"));
    assertThat(((Number) row.get("PASSED")).intValue()).isZero();
    assertThat(row.get("DEDUCTIONS_JSON").toString()).contains("台面污渍");
    assertThat(row.get("REDLINES_JSON").toString()).contains("食品安全红线");
    assertThat(row.get("PHOTOS_JSON").toString()).contains("data:image/jpeg");
    assertThat(row.get("NOTE")).isEqualTo("需要复查");

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'legacy_kv_structured_migration' and target_type = 'inspection_record'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void runsLegacyKvLogsMigrationIntoOperationLogIdempotently() {
    AuthUser boss = user("BOSS");
    jdbcTemplate.update(
        "insert into kv_storage(storage_key, storage_value) values (?, ?)",
        "logs",
        """
        [
          {
            "at":"2026-05-21T08:30:00Z",
            "by":"财务",
            "type":"修改",
            "dataType":"数据录入",
            "store":"保利店",
            "month":"2026-05",
            "before":"{\\"sales\\":100}",
            "after":"{\\"sales\\":120}",
            "reason":"修正营业额"
          }
        ]
        """
    );

    LegacyKvMigrationRunResponse response = service.legacyKvRun(
        boss,
        new LegacyKvMigrationRunRequest(List.of("logs"))
    );

    assertThat(response.executed()).isTrue();
    assertThat(response.migratedKeyCount()).isEqualTo(1);
    LegacyKvMigrationRunItemResponse logs = response.items().getFirst();
    assertThat(logs.key()).isEqualTo("logs");
    assertThat(logs.targetTable()).isEqualTo("operation_log");
    assertThat(logs.result()).isEqualTo("MIGRATED");
    assertThat(logs.migratedRecordCount()).isEqualTo(1);

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select * from operation_log where action = ? and target_type = ?",
        "修改",
        "数据录入"
    );
    assertThat(row.get("TENANT_ID")).isEqualTo(1L);
    assertThat(row.get("OPERATOR_NAME")).isEqualTo("财务");
    assertThat(row.get("STORE_ID")).isEqualTo("保利店");
    assertThat(row.get("MONTH")).isEqualTo("2026-05");
    assertThat(row.get("BEFORE_JSON")).isEqualTo("{\"sales\":100}");
    assertThat(row.get("AFTER_JSON")).isEqualTo("{\"sales\":120}");
    assertThat(row.get("REASON")).isEqualTo("修正营业额");
    assertThat(row.get("TARGET_ID").toString()).startsWith("legacy-log-");
    assertThat(row.get("CREATED_AT")).isNotNull();

    service.legacyKvRun(boss, new LegacyKvMigrationRunRequest(List.of("logs")));

    Integer importedLogCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = ? and target_type = ?",
        Integer.class,
        "修改",
        "数据录入"
    );
    assertThat(importedLogCount).isEqualTo(1);
  }

  private LegacyKvKeyStatusResponse statusFor(MigrationStatusResponse response, String key) {
    return response.legacyBusinessKeys().stream()
        .filter(status -> status.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private LegacyKvMigrationPreviewItemResponse previewItemFor(LegacyKvMigrationPreviewResponse response, String key) {
    return response.items().stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private BrowserStoragePreviewItemResponse browserItemFor(BrowserStoragePreviewResponse response, String key) {
    return response.items().stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private BrowserStorageMigrationRunItemResponse runItemFor(BrowserStorageMigrationRunResponse response, String key) {
    return response.items().stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private AuthUser user(String role) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, null, true);
  }
}
