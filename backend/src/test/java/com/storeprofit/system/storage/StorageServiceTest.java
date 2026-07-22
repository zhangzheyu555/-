package com.storeprofit.system.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.util.Set;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class StorageServiceTest {
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final StorageService storageService = new StorageService(jdbcTemplate, accessControl);

  @Test
  void financeCanWriteFinanceKeysOnly() {
    AuthUser finance = user("FINANCE");

    assertThatNoException().isThrownBy(() -> storageService.set(finance, "entries", "{}"));
    assertThatNoException().isThrownBy(() -> storageService.set(finance, "expenses", "[]"));
    assertThatNoException().isThrownBy(() -> storageService.set(finance, "salary", "[]"));

    assertThatThrownBy(() -> storageService.set(finance, "inspections", "[]"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("legacy KV");
  }

  @Test
  void supervisorAndStoreManagerCanWriteOnlyTheirBrowserMigrationKeys() {
    AuthUser supervisor = user("SUPERVISOR");
    AuthUser storeManager = user("STORE_MANAGER");

    assertThatNoException().isThrownBy(() -> storageService.set(supervisor, "inspections", "[]"));
    assertThatNoException().isThrownBy(() -> storageService.set(storeManager, "expenses", "[]"));

    assertThatThrownBy(() -> storageService.set(supervisor, "entries", "{}"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.set(storeManager, "entries", "{}"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void browserAuthKeysRemainBlockedEvenForBoss() {
    AuthUser boss = user("BOSS");

    assertThatThrownBy(() -> storageService.set(boss, "accounts", "[]"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.set(boss, "app_pin", "\"123\""))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void browserAuthKeysArePermanentlyBlockedOnRead() {
    AuthUser boss = user("BOSS");

    assertThatThrownBy(() -> storageService.get(boss, "accounts"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("LEGACY_STORAGE_SENSITIVE_KEY_BLOCKED"));
    assertThatThrownBy(() -> storageService.get(boss, "tokens"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storageService.get(boss, "unknown_key"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("LEGACY_STORAGE_KEY_NOT_ALLOWED"));
  }

  @Test
  void allowlistedBusinessKeyCanStillBeRead() {
    AuthUser boss = user("BOSS");
    when(jdbcTemplate.queryForObject(
        "select storage_value from kv_storage where storage_key = ?", String.class, "entries"))
        .thenReturn("{}");

    assertThat(storageService.get(boss, " entries ")).contains("{}");

    verify(accessControl).requireLegacyStorageAccess(boss);
  }

  @Test
  void ordinaryRoleCannotReadOrWriteLegacyKvWithoutMigrationPermission() {
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    AccessControlService protectedAccess = new AccessControlService(
        mock(AuthService.class), mock(AuthRepository.class), auditRepository, authorizationService, null);
    StorageService protectedStorage = new StorageService(jdbcTemplate, protectedAccess);
    AuthUser finance = user("FINANCE");

    assertThatThrownBy(() -> protectedStorage.get(finance, "entries"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> protectedStorage.set(finance, "entries", "{}"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void returnAttachmentDownloadUsesStoreScopeAndWritesAuditLog() {
    AttachmentFixture fixture = attachmentFixture();
    AuthUser manager = user(11L, "STORE_MANAGER", "s1");
    fixture.allowAttachmentRead(manager, Set.of("s1"));
    long sameStoreAttachmentId = fixture.insertReturnAttachment("ret-s1", "s1", "WAREHOUSE_RETURN", 21L);
    long otherStoreAttachmentId = fixture.insertReturnAttachment("ret-s2", "s2", "RETURN_ORDER", 22L);

    assertThat(fixture.storageService.attachment(manager, sameStoreAttachmentId)).isPresent();
    assertThatThrownBy(() -> fixture.storageService.attachment(manager, otherStoreAttachmentId))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    assertThat(fixture.auditCount("attachment_download", "WAREHOUSE_RETURN", "ret-s1", "s1")).isEqualTo(1);
    assertThat(fixture.auditCount("attachment_download", "RETURN_ORDER", "ret-s2", "s2")).isZero();
    assertThat(fixture.auditCount("permission_denied", "STORE", "s2", "s2")).isEqualTo(1);
  }

  @Test
  void warehouseAndBossCanDownloadReturnAttachmentsAndEachSuccessfulReadIsAudited() {
    AttachmentFixture fixture = attachmentFixture();
    AuthUser warehouse = user(12L, "WAREHOUSE", null);
    AuthUser boss = user(13L, "BOSS", null);
    fixture.allowAttachmentReadForAllStores(warehouse);
    long attachmentId = fixture.insertReturnAttachment("ret-s3", "s3", "WAREHOUSE_RETURN", 23L);

    assertThat(fixture.storageService.attachment(warehouse, attachmentId)).isPresent();
    assertThat(fixture.storageService.attachment(boss, attachmentId)).isPresent();

    assertThat(fixture.auditCount("attachment_download", "WAREHOUSE_RETURN", "ret-s3", "s3")).isEqualTo(2);
  }

  @Test
  void onlyExactInspectionStoreDraftCanUploadBeforeRecordExists() {
    AuthUser supervisor = user("SUPERVISOR");
    when(jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class, 1L, "s1")).thenReturn(1);
    MockMultipartFile photo = new MockMultipartFile(
        "file", "shop.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

    assertThatNoException().isThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s1-draft", "s1"));

    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_HISTORICAL_EVIDENCE_SPECIAL_ENDPOINT_REQUIRED"));
    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s2-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_HISTORICAL_EVIDENCE_SPECIAL_ENDPOINT_REQUIRED"));
    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "EXPENSE", "inspection-s1-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("ATTACHMENT_BUSINESS_NOT_FOUND"));
  }

  @Test
  void crossStoreDraftUploadStillRequiresAttachmentWritePermission() {
    AuthUser supervisor = user("SUPERVISOR");
    MockMultipartFile photo = new MockMultipartFile(
        "file", "shop.jpg", "image/jpeg", new byte[]{1});
    doThrow(new BusinessException("FORBIDDEN", "跨店禁止上传", org.springframework.http.HttpStatus.FORBIDDEN))
        .when(accessControl).requireAttachmentWrite(supervisor, "s2");

    assertThatThrownBy(() -> storageService.upload(
        supervisor, photo, "INSPECTION_RECORD", "inspection-s2-draft", "s2"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void expenseAttachmentCannotUseGenericAttachmentPermissionAsWriteBypass() {
    ExpenseAttachmentFixture fixture = expenseAttachmentFixture();
    AuthUser supervisor = user(31L, "SUPERVISOR", "s1");
    fixture.insertExpenseClaim("exp-1", "s1", "2026-07", "草稿");
    fixture.allowGenericAttachmentWrite(supervisor, Set.of("s1"));

    assertThatThrownBy(() -> fixture.storageService.upload(
        supervisor,
        jpeg("receipt.jpg"),
        "EXPENSE_CLAIM",
        "exp-1",
        "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    assertThat(fixture.attachmentCount()).isZero();
  }

  @Test
  void expenseAttachmentRejectsOversizeInvalidTypeAndNonEditableClaimWithoutWritingRows() {
    ExpenseAttachmentFixture fixture = expenseAttachmentFixture();
    AuthUser manager = user(32L, "STORE_MANAGER", "s1");
    fixture.insertExpenseClaim("exp-draft", "s1", "2026-07", "草稿");
    fixture.insertExpenseClaim("exp-pending", "s1", "2026-07", "待审核");
    fixture.allowExpenseAttachmentAccess(manager, Set.of("s1"));

    MockMultipartFile oversized = new MockMultipartFile(
        "file",
        "receipt.jpg",
        "image/jpeg",
        new byte[10 * 1024 * 1024 + 1]);
    assertThatThrownBy(() -> fixture.storageService.upload(
        manager, oversized, "EXPENSE", "exp-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EXPENSE_ATTACHMENT_TOO_LARGE"));

    MockMultipartFile extensionAndMimeMismatch = new MockMultipartFile(
        "file",
        "receipt.png",
        "image/jpeg",
        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
    assertThatThrownBy(() -> fixture.storageService.upload(
        manager, extensionAndMimeMismatch, "EXPENSE", "exp-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EXPENSE_ATTACHMENT_IMAGE_REQUIRED"));

    MockMultipartFile wrongMagic = new MockMultipartFile(
        "file",
        "receipt.jpg",
        "image/jpeg",
        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    assertThatThrownBy(() -> fixture.storageService.upload(
        manager, wrongMagic, "EXPENSE", "exp-draft", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EXPENSE_ATTACHMENT_IMAGE_REQUIRED"));

    assertThatThrownBy(() -> fixture.storageService.upload(
        manager, jpeg("receipt.jpg"), "EXPENSE", "exp-pending", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EXPENSE_ATTACHMENT_STATUS_INVALID"));

    assertThat(fixture.attachmentCount()).isZero();
  }

  @Test
  void expenseAttachmentAcceptsVerifiedImageAndAuditsUploadAndDownloadWithMonth() {
    ExpenseAttachmentFixture fixture = expenseAttachmentFixture();
    AuthUser manager = user(33L, "STORE_MANAGER", "s1");
    fixture.insertExpenseClaim("exp-1", "s1", "2026-07", "草稿");
    fixture.allowExpenseAttachmentAccess(manager, Set.of("s1"));

    StorageUploadResponse upload = fixture.storageService.upload(
        manager, jpeg("receipt.JPEG"), "expense_claim", "exp-1", "s1");

    assertThat(upload.id()).isNotNull();
    assertThat(upload.url()).isEqualTo("/api/storage/attachments/" + upload.id());
    assertThat(fixture.storageService.attachment(manager, upload.id())).isPresent();
    assertThat(fixture.attachmentCount()).isEqualTo(1);
    assertThat(fixture.auditCount(
        "reimbursement_attachment_upload", "expense_claim", "exp-1", "s1", "2026-07")).isEqualTo(1);
    assertThat(fixture.auditCount(
        "reimbursement_attachment_download", "expense_claim", "exp-1", "s1", "2026-07")).isEqualTo(1);
  }

  @Test
  void expenseAttachmentDownloadRequiresFinanceScopeEvenWhenExpenseReadIsGranted() {
    ExpenseAttachmentFixture fixture = expenseAttachmentFixture();
    AuthUser manager = user(34L, "STORE_MANAGER", "s1");
    fixture.insertExpenseClaim("exp-1", "s1", "2026-07", "草稿");
    long attachmentId = fixture.insertExpenseAttachment("exp-1", "s1");
    fixture.allowExpenseRead(manager, Set.of());

    assertThatThrownBy(() -> fixture.storageService.attachment(manager, attachmentId))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    assertThat(fixture.auditCount(
        "reimbursement_attachment_download", "expense_claim", "exp-1", "s1", "2026-07")).isZero();
    assertThat(fixture.permissionDeniedCount("exp-1", "s1", "2026-07")).isEqualTo(1);
  }

  @Test
  void forgedOwnStoreAndForeignExpenseIdIsDeniedAgainstTheActualExpenseScope() {
    ExpenseAttachmentFixture fixture = expenseAttachmentFixture();
    AuthUser manager = user(35L, "STORE_MANAGER", "s1");
    fixture.insertExpenseClaim("exp-own", "s1", "2026-07", "草稿");
    fixture.insertExpenseClaim("exp-foreign", "s2", "2026-07", "草稿");
    fixture.allowExpenseAttachmentAccess(manager, Set.of("s1"));

    assertThatThrownBy(() -> fixture.storageService.upload(
        manager, jpeg("receipt.jpg"), "EXPENSE_CLAIM", "exp-foreign", "s1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));

    assertThat(fixture.attachmentCount()).isZero();
    assertThat(fixture.permissionDeniedCountForAction(
        "exp-foreign", "s2", "2026-07", "上传报销凭证")).isEqualTo(1);
  }

  private MockMultipartFile jpeg(String fileName) {
    return new MockMultipartFile(
        "file", fileName, "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
  }

  private AuthUser user(String role) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, null, true);
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "default", role.toLowerCase() + id, "", role, role, storeId, true);
  }

  private AttachmentFixture attachmentFixture() {
    JdbcTemplate realJdbc = new JdbcTemplate(dataSource());
    realJdbc.execute("""
        create table warehouse_attachment(
          id bigint generated by default as identity primary key,
          tenant_id bigint not null,
          store_id varchar(64),
          business_type varchar(60) not null,
          business_id varchar(120) not null,
          file_name varchar(255) not null,
          content_type varchar(120),
          file_size bigint,
          storage_path varchar(500),
          content blob,
          uploaded_by bigint,
          uploaded_at timestamp default current_timestamp
        )
        """);
    realJdbc.execute("""
        create table warehouse_return_order(
          tenant_id bigint not null,
          id varchar(120) not null,
          return_store_id varchar(64) not null
        )
        """);
    realJdbc.execute("""
        create table operation_log(
          id bigint generated by default as identity primary key,
          tenant_id bigint,
          operator_id bigint,
          operator_name varchar(120),
          action varchar(120),
          target_type varchar(120),
          target_id varchar(120),
          store_id varchar(64),
          month varchar(7),
          reason varchar(500),
          created_at timestamp default current_timestamp
        )
        """);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    AccessControlService scopedAccess = new AccessControlService(
        mock(AuthService.class),
        mock(AuthRepository.class),
        new AuditRepository(realJdbc),
        authorizationService,
        dataScopeService
    );
    return new AttachmentFixture(
        realJdbc,
        new StorageService(realJdbc, scopedAccess),
        authorizationService,
        dataScopeService
    );
  }

  private ExpenseAttachmentFixture expenseAttachmentFixture() {
    JdbcTemplate realJdbc = new JdbcTemplate(dataSource());
    realJdbc.execute("""
        create table store_branch(
          tenant_id bigint not null,
          id varchar(64) not null,
          primary key(tenant_id, id)
        )
        """);
    realJdbc.execute("""
        create table expense_claim(
          tenant_id bigint not null,
          id varchar(120) not null,
          store_id varchar(64) not null,
          month varchar(7) not null,
          status varchar(30) not null,
          primary key(tenant_id, id)
        )
        """);
    realJdbc.execute("""
        create table warehouse_attachment(
          id bigint generated by default as identity primary key,
          tenant_id bigint not null,
          store_id varchar(64),
          business_type varchar(60) not null,
          business_id varchar(120) not null,
          file_name varchar(255) not null,
          content_type varchar(120),
          file_size bigint,
          storage_path varchar(500),
          content blob,
          uploaded_by bigint,
          uploaded_at timestamp default current_timestamp
        )
        """);
    realJdbc.execute("""
        create table operation_log(
          id bigint generated by default as identity primary key,
          tenant_id bigint,
          operator_id bigint,
          operator_name varchar(120),
          action varchar(120),
          target_type varchar(120),
          target_id varchar(120),
          store_id varchar(64),
          month varchar(7),
          reason varchar(500),
          created_at timestamp default current_timestamp
        )
        """);
    AuthorizationService authorizationService = mock(AuthorizationService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    AccessControlService scopedAccess = new AccessControlService(
        mock(AuthService.class),
        mock(AuthRepository.class),
        new AuditRepository(realJdbc),
        authorizationService,
        dataScopeService
    );
    return new ExpenseAttachmentFixture(
        realJdbc,
        new StorageService(realJdbc, scopedAccess),
        authorizationService,
        dataScopeService
    );
  }

  private DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:storage-service-" + System.nanoTime()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static final class AttachmentFixture {
    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final AuthorizationService authorizationService;
    private final DataScopeService dataScopeService;

    private AttachmentFixture(
        JdbcTemplate jdbcTemplate,
        StorageService storageService,
        AuthorizationService authorizationService,
        DataScopeService dataScopeService
    ) {
      this.jdbcTemplate = jdbcTemplate;
      this.storageService = storageService;
      this.authorizationService = authorizationService;
      this.dataScopeService = dataScopeService;
    }

    private void allowAttachmentRead(AuthUser user, Set<String> storeIds) {
      when(authorizationService.hasPermission(user, PermissionCodes.ATTACHMENT_READ)).thenReturn(true);
      when(dataScopeService.hasAllDataScope(user, DataScopeDomains.STORE)).thenReturn(false);
      when(dataScopeService.allowedStoreIds(user, DataScopeDomains.STORE)).thenReturn(storeIds);
    }

    private void allowAttachmentReadForAllStores(AuthUser user) {
      when(authorizationService.hasPermission(user, PermissionCodes.ATTACHMENT_READ)).thenReturn(true);
      when(dataScopeService.hasAllDataScope(user, DataScopeDomains.STORE)).thenReturn(true);
    }

    private long insertReturnAttachment(String returnId, String storeId, String businessType, long uploadedBy) {
      jdbcTemplate.update("""
          insert into warehouse_return_order(tenant_id, id, return_store_id)
          values (1, ?, ?)
          """, returnId, storeId);
      jdbcTemplate.update("""
          insert into warehouse_attachment(
            tenant_id, store_id, business_type, business_id, file_name, content_type,
            file_size, content, uploaded_by
          ) values (1, ?, ?, ?, 'return.pdf', 'application/pdf', 4, ?, ?)
          """, storeId, businessType, returnId, new byte[] {1, 2, 3, 4}, uploadedBy);
      return jdbcTemplate.queryForObject("select max(id) from warehouse_attachment", Long.class);
    }

    private int auditCount(String action, String targetType, String targetId, String storeId) {
      Integer count = jdbcTemplate.queryForObject("""
          select count(*)
          from operation_log
          where action = ? and target_type = ? and target_id = ? and store_id = ?
          """, Integer.class, action, targetType, targetId, storeId);
      return count == null ? 0 : count;
    }
  }

  private static final class ExpenseAttachmentFixture {
    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final AuthorizationService authorizationService;
    private final DataScopeService dataScopeService;

    private ExpenseAttachmentFixture(
        JdbcTemplate jdbcTemplate,
        StorageService storageService,
        AuthorizationService authorizationService,
        DataScopeService dataScopeService
    ) {
      this.jdbcTemplate = jdbcTemplate;
      this.storageService = storageService;
      this.authorizationService = authorizationService;
      this.dataScopeService = dataScopeService;
    }

    private void insertExpenseClaim(String id, String storeId, String month, String status) {
      jdbcTemplate.update("merge into store_branch(tenant_id, id) key(tenant_id, id) values (1, ?)", storeId);
      jdbcTemplate.update("""
          insert into expense_claim(tenant_id, id, store_id, month, status)
          values (1, ?, ?, ?, ?)
          """, id, storeId, month, status);
    }

    private long insertExpenseAttachment(String expenseId, String storeId) {
      jdbcTemplate.update("""
          insert into warehouse_attachment(
            tenant_id, store_id, business_type, business_id, file_name, content_type,
            file_size, content, uploaded_by
          ) values (1, ?, 'EXPENSE', ?, 'receipt.jpg', 'image/jpeg', 4, ?, 1)
          """, storeId, expenseId, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
      return jdbcTemplate.queryForObject("select max(id) from warehouse_attachment", Long.class);
    }

    private void allowGenericAttachmentWrite(AuthUser user, Set<String> storeIds) {
      when(authorizationService.hasPermission(user, PermissionCodes.ATTACHMENT_WRITE)).thenReturn(true);
      allowScope(user, DataScopeDomains.STORE, storeIds);
    }

    private void allowExpenseAttachmentAccess(AuthUser user, Set<String> storeIds) {
      allowGenericAttachmentWrite(user, storeIds);
      when(authorizationService.hasPermission(user, PermissionCodes.EXPENSE_CREATE)).thenReturn(true);
      when(authorizationService.hasPermission(user, PermissionCodes.EXPENSE_READ)).thenReturn(true);
      allowScope(user, DataScopeDomains.FINANCE, storeIds);
    }

    private void allowExpenseRead(AuthUser user, Set<String> financeStoreIds) {
      when(authorizationService.hasPermission(user, PermissionCodes.EXPENSE_READ)).thenReturn(true);
      allowScope(user, DataScopeDomains.FINANCE, financeStoreIds);
    }

    private void allowScope(AuthUser user, String domain, Set<String> storeIds) {
      when(dataScopeService.hasAllDataScope(user, domain)).thenReturn(false);
      when(dataScopeService.allowedStoreIds(user, domain)).thenReturn(storeIds);
    }

    private int attachmentCount() {
      Integer count = jdbcTemplate.queryForObject("select count(*) from warehouse_attachment", Integer.class);
      return count == null ? 0 : count;
    }

    private int auditCount(String action, String targetType, String targetId, String storeId, String month) {
      Integer count = jdbcTemplate.queryForObject("""
          select count(*)
          from operation_log
          where action = ? and target_type = ? and target_id = ? and store_id = ? and month = ?
          """, Integer.class, action, targetType, targetId, storeId, month);
      return count == null ? 0 : count;
    }

    private int permissionDeniedCount(String expenseId, String storeId, String month) {
      Integer count = jdbcTemplate.queryForObject("""
          select count(*)
          from operation_log
          where action = 'permission_denied' and target_type = 'expense_claim'
            and target_id = ? and store_id = ? and month = ? and reason like '查看报销凭证%'
          """, Integer.class, expenseId, storeId, month);
      return count == null ? 0 : count;
    }

    private int permissionDeniedCountForAction(String expenseId, String storeId, String month, String action) {
      Integer count = jdbcTemplate.queryForObject("""
          select count(*)
          from operation_log
          where action = 'permission_denied' and target_type = 'expense_claim'
            and target_id = ? and store_id = ? and month = ? and reason like ?
          """, Integer.class, expenseId, storeId, month, action + "%");
      return count == null ? 0 : count;
    }
  }
}
