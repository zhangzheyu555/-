package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

class ExpenseSupplementServiceTest {
  @TempDir
  Path storageRoot;

  private JdbcTemplate jdbcTemplate;
  private ExpenseRepository expenseRepository;
  private ExpenseSupplementRepository supplementRepository;
  private AccessControlService accessControl;
  private ExpenseSupplementService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    expenseRepository = new ExpenseRepository(jdbcTemplate, new NamedParameterJdbcTemplate(dataSource));
    supplementRepository = new ExpenseSupplementRepository(jdbcTemplate);
    accessControl = mock(AccessControlService.class);
    service = new ExpenseSupplementService(expenseRepository, supplementRepository, accessControl, storageRoot);
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values ('s1', 1, 1, '001', 'One'), ('s2', 1, 1, '002', 'Two')
        """);
    jdbcTemplate.update("""
        insert into expense_claim(
          id, tenant_id, store_id, month, amount, category, reason, status
        ) values ('exp-1', 1, 's1', '2026-05', 100, '物料采购', '采购说明', '待补资料')
        """);
  }

  @Test
  void submitsAllowedFilesPersistsMetadataAndCanReadAfterRefresh() throws Exception {
    List<MockMultipartFile> files = List.of(
        file("../../invoice.jpg", "image/jpeg", jpeg()),
        file("invoice.png", "image/png", png()),
        file("invoice.webp", "image/webp", webp()),
        file("invoice.pdf", "application/pdf", pdf())
    );

    ExpenseClaimResponse response = service.submit(manager("s1"), "exp-1", "补充付款凭证", new ArrayList<>(files));
    List<ExpenseSupplementResponse> refreshed = service.supplements(manager("s1"), "exp-1");

    assertThat(response.status()).isEqualTo("待审核");
    assertThat(response.latestSupplementNote()).isEqualTo("补充付款凭证");
    assertThat(response.supplementAttachmentCount()).isEqualTo(4);
    assertThat(refreshed).hasSize(1);
    assertThat(refreshed.getFirst().attachments()).hasSize(4);
    assertThat(refreshed.getFirst().attachments().getFirst().fileName()).isEqualTo("invoice.jpg");
    assertThat(refreshed.getFirst().attachments().getFirst().previewUrl())
        .startsWith("/api/expenses/exp-1/attachments/")
        .endsWith("/content")
        .doesNotContain("storage", "..", storageRoot.toString());
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from expense_supplement_attachment where expense_id = 'exp-1'", Integer.class))
        .isEqualTo(4);
    assertThat(Files.walk(storageRoot).filter(Files::isRegularFile)).hasSize(4);
    assertThat(jdbcTemplate.queryForObject(
        "select reason from operation_log where action = 'reimbursement_attachment_upload'", String.class))
        .isEqualTo("提交报销补充资料，附件数量：4");

    long pdfAttachmentId = refreshed.getFirst().attachments().stream()
        .filter(item -> "application/pdf".equals(item.contentType()))
        .findFirst()
        .orElseThrow()
        .id();
    ExpenseSupplementService.AttachmentContent downloaded = service.attachment(
        manager("s1"), "exp-1", pdfAttachmentId, false);
    assertThat(downloaded.fileName()).isEqualTo("invoice.pdf");
    assertThat(downloaded.bytes()).isEqualTo(pdf());
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'reimbursement_attachment_download'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectsEmptyFakeOversizeAndTooManyFilesWithoutWritingAnything() throws Exception {
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", " ", List.of()),
        "SUPPLEMENT_EMPTY"
    );
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", null,
            List.of(file("fake.png", "image/png", "not an image".getBytes()))),
        "FILE_TYPE_MISMATCH"
    );
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", null,
            List.of(file("malware.exe", "application/octet-stream", png()))),
        "FILE_TYPE_NOT_ALLOWED"
    );
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", null,
            List.of(file("large.png", "image/png", oversizedPng()))),
        "FILE_TOO_LARGE"
    );
    List<MockMultipartFile> sevenFiles = new ArrayList<>();
    for (int index = 0; index < 7; index++) {
      sevenFiles.add(file("invoice-" + index + ".png", "image/png", png()));
    }
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", null, new ArrayList<>(sevenFiles)),
        "TOO_MANY_FILES"
    );
    assertThat(jdbcTemplate.queryForObject("select count(*) from expense_supplement", Integer.class)).isZero();
    assertThat(Files.walk(storageRoot).filter(Files::isRegularFile)).isEmpty();
  }

  @Test
  void validatesEveryFileBeforePersistingTheBatch() throws Exception {
    assertBusinessCode(
        () -> service.submit(manager("s1"), "exp-1", "有说明", List.of(
            file("first.png", "image/png", png()),
            file("second.png", "image/png", "fake".getBytes())
        )),
        "FILE_TYPE_MISMATCH"
    );

    assertThat(jdbcTemplate.queryForObject("select count(*) from expense_supplement", Integer.class)).isZero();
    assertThat(Files.walk(storageRoot).filter(Files::isRegularFile)).isEmpty();
  }

  @Test
  void cleansWrittenFileWhenDatabaseInsertFails() throws Exception {
    ExpenseRepository mockedExpenseRepository = mock(ExpenseRepository.class);
    ExpenseSupplementRepository mockedSupplementRepository = mock(ExpenseSupplementRepository.class);
    when(mockedExpenseRepository.claim(1L, "exp-1")).thenReturn(java.util.Optional.of(claim()));
    when(mockedSupplementRepository.insertSupplement(1L, "exp-1", "有说明", 3L, "Manager"))
        .thenReturn(10L);
    doThrow(new IllegalStateException("database failed")).when(mockedSupplementRepository).insertAttachment(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        anyString(),
        anyString(),
        anyString(),
        org.mockito.ArgumentMatchers.anyLong(),
        anyString(),
        org.mockito.ArgumentMatchers.anyLong()
    );
    ExpenseSupplementService failingService = new ExpenseSupplementService(
        mockedExpenseRepository, mockedSupplementRepository, accessControl, storageRoot);

    assertThatThrownBy(() -> failingService.submit(
        manager("s1"), "exp-1", "有说明", List.of(file("invoice.png", "image/png", png()))))
        .isInstanceOf(IllegalStateException.class);

    assertThat(Files.walk(storageRoot).filter(Files::isRegularFile)).isEmpty();
  }

  @Test
  void enforcesExpenseRoleAndStoreScopeForSubmitAndRead() {
    AuthUser warehouse = user(5L, "WAREHOUSE", "s1");
    doThrow(new BusinessException("FORBIDDEN", "forbidden", HttpStatus.FORBIDDEN))
        .when(accessControl).requireExpenseWrite(warehouse, "exp-1", "s1", "2026-05");
    assertBusinessCode(
        () -> service.submit(warehouse, "exp-1", "补充说明", List.of()),
        "FORBIDDEN"
    );

    AuthUser otherManager = manager("s2");
    doThrow(new BusinessException("FORBIDDEN", "forbidden", HttpStatus.FORBIDDEN))
        .when(accessControl).requireExpenseStoreAccess(
            otherManager, "exp-1", "s1", "2026-05", "补充报销资料");
    assertBusinessCode(
        () -> service.submit(otherManager, "exp-1", "补充说明", List.of()),
        "FORBIDDEN"
    );
    verify(accessControl).requireExpenseWrite(warehouse, "exp-1", "s1", "2026-05");
  }

  @Test
  void supplementRestoresOnlyTheAffectedStoreExpenseReviewTodo() {
    BusinessTodoService todoService = mock(BusinessTodoService.class);
    ExpenseSupplementService reconciledService = new ExpenseSupplementService(
        expenseRepository, supplementRepository, accessControl, todoService, storageRoot);

    ExpenseClaimResponse response = reconciledService.submit(
        manager("s1"), "exp-1", "补充说明", List.of(file("invoice.png", "image/png", png())));

    assertThat(response.status()).isEqualTo("待审核");
    verify(todoService).reconcileExpenseReviewForStore(1L, "s1", "2026-05");
  }

  @Test
  void attachmentMustBelongToExpenseAndStoreScope() {
    service.submit(manager("s1"), "exp-1", null,
        List.of(file("invoice.png", "image/png", png())));
    long attachmentId = service.supplements(manager("s1"), "exp-1")
        .getFirst().attachments().getFirst().id();

    assertBusinessCode(
        () -> service.attachment(manager("s1"), "another-expense", attachmentId, false),
        "ATTACHMENT_NOT_FOUND"
    );

    AuthUser otherManager = manager("s2");
    doThrow(new BusinessException("FORBIDDEN", "forbidden", HttpStatus.FORBIDDEN))
        .when(accessControl).requireExpenseStoreAccess(
            otherManager, "exp-1", "s1", "2026-05", "查看报销补充资料附件");
    assertBusinessCode(
        () -> service.attachment(otherManager, "exp-1", attachmentId, false),
        "FORBIDDEN"
    );
  }

  @Test
  void rejectsTamperedStorageKeyWithoutLeavingStorageRoot() throws Exception {
    service.submit(manager("s1"), "exp-1", null,
        List.of(file("invoice.png", "image/png", png())));
    long attachmentId = service.supplements(manager("s1"), "exp-1")
        .getFirst().attachments().getFirst().id();
    Path outside = storageRoot.getParent().resolve("outside.png");
    Files.write(outside, png());
    jdbcTemplate.update(
        "update expense_supplement_attachment set storage_key = '../../outside.png' where id = ?",
        attachmentId
    );

    assertBusinessCode(
        () -> service.attachment(manager("s1"), "exp-1", attachmentId, false),
        "STORAGE_KEY_INVALID"
    );
    assertThat(Files.readAllBytes(outside)).isEqualTo(png());
  }

  private void createSchema() {
    jdbcTemplate.execute("create table brand (id bigint primary key, tenant_id bigint not null, name varchar(120))");
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) primary key, tenant_id bigint not null, brand_id bigint,
          code varchar(80), name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table expense_claim (
          id varchar(120) primary key, tenant_id bigint not null, store_id varchar(64) not null,
          month char(7), expense_date date, amount decimal(14,2), category varchar(80), reason text,
          status varchar(40), image_url varchar(500), submitted_by bigint, reviewed_by bigint,
          reviewed_at timestamp, created_at timestamp default current_timestamp,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key, tenant_id bigint not null, operator_id bigint,
          operator_name varchar(120), action varchar(80), target_type varchar(80),
          target_id varchar(120), store_id varchar(64), month char(7), reason varchar(255),
          created_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table expense_supplement (
          id bigint auto_increment primary key, tenant_id bigint not null, expense_id varchar(120) not null,
          note text, submitted_by bigint not null, submitted_by_name varchar(120) not null,
          submitted_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table expense_supplement_attachment (
          id bigint auto_increment primary key, tenant_id bigint not null, supplement_id bigint not null,
          expense_id varchar(120) not null, file_name varchar(255) not null,
          content_type varchar(120) not null, file_size bigint not null,
          storage_key varchar(160) not null, uploaded_by bigint not null,
          uploaded_at timestamp default current_timestamp
        )
        """);
  }

  private MockMultipartFile file(String name, String contentType, byte[] bytes) {
    return new MockMultipartFile("files", name, contentType, bytes);
  }

  private byte[] jpeg() {
    return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 1};
  }

  private byte[] png() {
    return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 1};
  }

  private byte[] webp() {
    return new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
  }

  private byte[] pdf() {
    return "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }

  private byte[] oversizedPng() {
    byte[] bytes = new byte[(int) ExpenseSupplementService.MAX_FILE_SIZE + 1];
    System.arraycopy(png(), 0, bytes, 0, png().length);
    return bytes;
  }

  private ExpenseClaimResponse claim() {
    return new ExpenseClaimResponse(
        "exp-1", "s1", "001", "One", 1L, "Tea", "2026-05",
        new BigDecimal("100"), "物料采购", "采购说明", "待补资料", null,
        null, null, null
    );
  }

  private AuthUser manager(String storeId) {
    return user(3L, "STORE_MANAGER", storeId);
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "default", role.toLowerCase(), "", role.equals("STORE_MANAGER") ? "Manager" : role,
        role, storeId, true);
  }

  private void assertBusinessCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action)
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(code));
  }
}
