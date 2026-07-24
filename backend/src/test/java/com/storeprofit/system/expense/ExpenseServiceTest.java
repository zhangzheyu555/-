package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.organization.StoreBusinessGuard;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExpenseServiceTest {
  private JdbcTemplate jdbcTemplate;
  private ExpenseService service;
  private ExpenseRepository repository;

  @Test
  void inactiveStoreCannotCreateANewExpenseClaimThroughTheService() {
    ExpenseRepository expenseRepository = mock(ExpenseRepository.class);
    StoreBusinessGuard guard = mock(StoreBusinessGuard.class);
    AuthUser manager = storeManager();
    doThrow(new BusinessException(
        "STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN",
        "门店已停用，不能创建新的费用单",
        org.springframework.http.HttpStatus.CONFLICT
    )).when(guard).requireActive(manager, "s1", "费用单");
    ExpenseService guarded = new ExpenseService(
        expenseRepository, null, null, null, null, null, guard);

    assertThatThrownBy(() -> guarded.save(
        manager, null, request("s1", "2026-05", "128.50")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("STORE_INACTIVE_NEW_BUSINESS_FORBIDDEN"));

    verify(expenseRepository, never()).upsert(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()
    );
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("""
        create table brand (
          id bigint not null primary key,
          tenant_id bigint not null,
          name varchar(120) not null
        )
        """);
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) not null primary key,
          tenant_id bigint not null,
          brand_id bigint null,
          code varchar(80) null,
          name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table expense_claim (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) null,
          expense_date date null,
          amount decimal(14,2) not null default 0,
          category varchar(80) null,
          reason text null,
          status varchar(40) not null default '待审核',
          image_url varchar(500) null,
          submitted_by bigint null,
          reviewed_by bigint null,
          reviewed_at timestamp null,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null default null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_attachment (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          store_id varchar(64) null,
          business_type varchar(60) not null,
          business_id varchar(120) not null,
          file_name varchar(255) not null,
          content_type varchar(120) null,
          file_size bigint null,
          storage_path varchar(500) null,
          content blob null,
          uploaded_by bigint null,
          uploaded_at timestamp null default current_timestamp
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
          reason varchar(255) null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea'), (2, 2, 'Other')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values
          ('s1', 1, 1, '001', 'One'),
          ('s2', 1, 1, '002', 'Two'),
          ('other', 2, 2, '099', 'Other')
        """);
    repository = new ExpenseRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    service = new ExpenseService(repository);
  }

  @Test
  void storeManagerCreatesDraftForOwnStoreAndBossListsTenantOnly() {
    ExpenseClaimResponse claim = service.save(storeManager(), null, request("s1", "2026-05", "128.50"));
    jdbcTemplate.update("""
        insert into expense_claim(id, tenant_id, store_id, month, amount, category, reason, status)
        values ('other-exp', 2, 'other', '2026-05', 999, 'Other', 'Other tenant', '草稿')
        """);

    List<ExpenseClaimResponse> records = service.claims(boss(), "2026-05", null, null, null);

    assertThat(claim.id()).isNotBlank();
    assertThat(claim.status()).isEqualTo("草稿");
    assertThat(claim.submittedBy()).isNull();
    assertThat(records).extracting(ExpenseClaimResponse::id).containsExactly(claim.id());
    assertThat(records.getFirst().storeName()).isEqualTo("One");
    assertThat(records.getFirst().amount()).isEqualByComparingTo("128.50");
  }

  @Test
  void storeManagerCannotWriteOrReadOtherStores() {
    service.save(boss(), "exp-s1", request("s1", "2026-05", "100"));
    service.save(boss(), "exp-s2", request("s2", "2026-05", "200"));

    List<ExpenseClaimResponse> records = service.claims(storeManager(), "2026-05", null, null, null);

    assertThat(records).extracting(ExpenseClaimResponse::id).containsExactly("exp-s1");
    assertThatThrownBy(() -> service.save(storeManager(), null, request("s2", "2026-05", "200")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.claims(storeManager(), "2026-05", null, "s2", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.save(storeManager(), "exp-s2", request("s1", "2026-05", "200")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void configuredFinanceStoreListFiltersExpenseRowsAndRejectsForgedStore() {
    service.save(boss(), "exp-s1", request("s1", "2026-05", "100"));
    service.save(boss(), "exp-s2", request("s2", "2026-05", "200"));
    AccessControlService accessControl = mock(AccessControlService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    DataScope scope = new DataScope(DataScopeModes.STORE_LIST, List.of("s1"));
    when(dataScopeService.scope(finance(), DataScopeDomains.FINANCE)).thenReturn(scope);
    ExpenseService scopedService = new ExpenseService(repository, null, accessControl, null, dataScopeService);

    List<ExpenseClaimResponse> records = scopedService.claims(finance(), "2026-05", null, null, null);

    assertThat(records).extracting(ExpenseClaimResponse::id).containsExactly("exp-s1");
    assertThatThrownBy(() -> scopedService.claims(finance(), "2026-05", null, "s2", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> scopedService.approve(finance(), "exp-s2", new ExpenseReviewRequest("OK")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void expenseNoneScopeReturnsNoRows() {
    service.save(boss(), "exp-s1", request("s1", "2026-05", "100"));
    AccessControlService accessControl = mock(AccessControlService.class);
    DataScopeService dataScopeService = mock(DataScopeService.class);
    when(dataScopeService.scope(finance(), DataScopeDomains.FINANCE)).thenReturn(DataScope.none());
    ExpenseService scopedService = new ExpenseService(repository, null, accessControl, null, dataScopeService);

    assertThat(scopedService.claims(finance(), "2026-05", null, null, null)).isEmpty();
  }

  @Test
  void submitApproveAndRejectFollowStatusFlowWithAudit() {
    service.save(storeManager(), "exp-1", request("s1", "2026-05", "128.50"));
    attachImage("exp-1", "s1");

    ExpenseClaimResponse submitted = service.submit(storeManager(), "exp-1");
    ExpenseClaimResponse rejected = service.reject(finance(), "exp-1", new ExpenseReviewRequest("Need invoice"));
    ExpenseClaimResponse resubmitted = service.submit(storeManager(), "exp-1");
    ExpenseClaimResponse approved = service.approve(boss(), "exp-1", new ExpenseReviewRequest("OK"));

    assertThat(submitted.status()).isEqualTo("待审核");
    assertThat(submitted.submittedBy()).isEqualTo(storeManager().id());
    assertThat(rejected.status()).isEqualTo("已驳回");
    assertThat(rejected.reviewedBy()).isEqualTo(finance().id());
    assertThat(rejected.reviewNote()).isEqualTo("Need invoice");
    assertThat(resubmitted.status()).isEqualTo("待审核");
    assertThat(approved.status()).isEqualTo("已完成");
    assertThat(approved.reviewedBy()).isEqualTo(boss().id());
    assertThat(approved.reviewedAt()).isNotNull();
    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where target_type = 'expense_claim' and target_id = 'exp-1'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(5);
  }

  @Test
  void approveRejectRequirePendingStatusAndReviewRole() {
    service.save(storeManager(), "exp-1", request("s1", "2026-05", "128.50"));

    assertThatThrownBy(() -> service.approve(boss(), "exp-1", new ExpenseReviewRequest("OK")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_STATUS"));

    attachImage("exp-1", "s1");
    service.submit(storeManager(), "exp-1");

    assertThatThrownBy(() -> service.approve(storeManager(), "exp-1", new ExpenseReviewRequest("OK")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void financeCanRequestSupplementThenStoreManagerCanEditAndResubmit() {
    service.save(storeManager(), "exp-supplement", request("s1", "2026-05", "128.50"));
    attachImage("exp-supplement", "s1");
    service.submit(storeManager(), "exp-supplement");

    ExpenseClaimResponse supplement = service.requestInfo(finance(), "exp-supplement", new ExpenseReviewRequest("请补充发票"));
    ExpenseClaimResponse edited = service.save(storeManager(), "exp-supplement", request("s1", "2026-05", "128.50"));
    ExpenseClaimResponse resubmitted = service.submit(storeManager(), "exp-supplement");

    assertThat(supplement.status()).isEqualTo("待补资料");
    assertThat(edited.status()).isEqualTo("待补资料");
    assertThat(resubmitted.status()).isEqualTo("待审核");
  }

  @Test
  void saveRejectsBadMonthMissingStoreAndNonPositiveAmount() {
    assertThatThrownBy(() -> service.save(boss(), null, request("s1", "202605", "128.50")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_MONTH"));
    assertThatThrownBy(() -> service.save(boss(), null, request("missing", "2026-05", "128.50")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_NOT_FOUND"));
    assertThatThrownBy(() -> service.save(boss(), null, request("s1", "2026-05", "0")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_AMOUNT"));
  }

  @Test
  void deleteRemovesDraftButNotApprovedClaim() {
    service.save(boss(), "draft", request("s1", "2026-05", "100"));
    service.save(boss(), "approved", request("s1", "2026-05", "200"));
    attachImage("approved", "s1");
    service.submit(boss(), "approved");
    service.approve(boss(), "approved", new ExpenseReviewRequest("OK"));

    service.delete(boss(), "draft");

    assertThat(service.claims(boss(), "2026-05", null, null, null))
        .extracting(ExpenseClaimResponse::id)
        .containsExactly("approved");
    assertThatThrownBy(() -> service.delete(boss(), "approved"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_STATUS"));
  }

  @Test
  void submitRequiresControlledImageForPositiveReimbursement() {
    service.save(storeManager(), "exp-image-required", request("s1", "2026-05", "128.50"));

    assertThatThrownBy(() -> service.submit(storeManager(), "exp-image-required"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("REIMBURSEMENT_IMAGE_REQUIRED"));

    attachImage("exp-image-required", "s1");

    assertThat(service.submit(storeManager(), "exp-image-required").status()).isEqualTo("待审核");
  }

  @Test
  void persistsDeclaredExpenseDateAndRejectsInvalidRequiredBusinessFields() {
    ExpenseClaimResponse saved = service.save(
        boss(), "dated", request("s1", "2026-05", "128.50"));

    assertThat(saved.expenseDate()).hasToString("2026-05-15");
    assertThat(jdbcTemplate.queryForObject(
        "select expense_date from expense_claim where id = 'dated'", java.sql.Date.class))
        .hasToString("2026-05-15");

    assertThatThrownBy(() -> service.save(boss(), null, new ExpenseClaimRequest(
        "s1", "2026-05", "2026-06-01", new BigDecimal("1.00"), "物料", "说明", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("EXPENSE_DATE_MONTH_MISMATCH"));
    assertThatThrownBy(() -> service.save(boss(), null, new ExpenseClaimRequest(
        "s1", "2026-05", "2026-05-16", new BigDecimal("1.00"), " ", "说明", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("CATEGORY_REQUIRED"));
    assertThatThrownBy(() -> service.save(boss(), null, new ExpenseClaimRequest(
        "s1", "2026-05", "2026-05-16", new BigDecimal("1.001"), "物料", "说明", null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_AMOUNT"));
  }

  @Test
  void duplicateSubmitAndStaleStatusUpdateCannotCreateASecondWorkflowEvent() {
    service.save(storeManager(), "exp-once", request("s1", "2026-05", "128.50"));
    attachImage("exp-once", "s1");

    assertThat(service.submit(storeManager(), "exp-once").status()).isEqualTo("待审核");
    assertThatThrownBy(() -> service.submit(storeManager(), "exp-once"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_STATUS"));
    assertThat(repository.updateStatus(
        1L,
        "exp-once",
        ExpenseService.STATUS_DRAFT,
        ExpenseService.STATUS_APPROVED,
        null,
        boss().id())).isZero();

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'reimbursement_submit' and target_id = 'exp-once'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select status from expense_claim where id = 'exp-once'", String.class)).isEqualTo("待审核");
  }

  @Test
  void sameIdempotencyKeyCreatesOneDraftAndOneAuditRecordAcrossRetries() {
    ExpenseClaimRequest initial = request("s1", "2026-05", "128.50");

    ExpenseClaimResponse first = service.save(storeManager(), null, initial, "expense-retry-001");
    ExpenseClaimResponse replay = service.save(storeManager(), null, initial, "expense-retry-001");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from expense_claim where id = ?", Integer.class, first.id())).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'expense_save' and target_id = ?", Integer.class, first.id()))
        .isEqualTo(1);
    assertThatThrownBy(() -> service.save(storeManager(), null,
        request("s1", "2026-05", "129.00"), "expense-retry-001"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void concurrentIdempotentCreateReturnsTheSameDraftWithoutDuplicateAudit() throws Exception {
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      java.util.concurrent.Callable<ExpenseClaimResponse> create = () -> {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("并发创建测试未能同时开始");
        }
        return service.save(storeManager(), null, request("s1", "2026-05", "128.50"), "expense-retry-concurrent");
      };
      Future<ExpenseClaimResponse> first = workers.submit(create);
      Future<ExpenseClaimResponse> second = workers.submit(create);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      ExpenseClaimResponse firstResult = first.get(5, TimeUnit.SECONDS);
      ExpenseClaimResponse secondResult = second.get(5, TimeUnit.SECONDS);
      assertThat(secondResult.id()).isEqualTo(firstResult.id());
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from expense_claim where id = ?", Integer.class, firstResult.id())).isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from operation_log where action = 'expense_save' and target_id = ?", Integer.class, firstResult.id()))
          .isEqualTo(1);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void concurrentApprovalCompareAndSetAllowsExactlyOneDatabaseTransition() throws Exception {
    service.save(storeManager(), "exp-concurrent", request("s1", "2026-05", "128.50"));
    attachImage("exp-concurrent", "s1");
    service.submit(storeManager(), "exp-concurrent");

    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      java.util.concurrent.Callable<Integer> approve = () -> {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("并发测试未能同时开始");
        }
        return repository.updateStatus(
            1L,
            "exp-concurrent",
            ExpenseService.STATUS_PENDING,
            ExpenseService.STATUS_APPROVED,
            null,
            boss().id());
      };
      Future<Integer> first = workers.submit(approve);
      Future<Integer> second = workers.submit(approve);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(0, 1);
      assertThat(jdbcTemplate.queryForObject(
          "select status from expense_claim where id = 'exp-concurrent'", String.class)).isEqualTo("已完成");
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void rejectionRequiresReasonAndDraftAttachmentDeletionIsAuditedAndImmutableAfterSubmit() {
    service.save(storeManager(), "exp-reject", request("s1", "2026-05", "128.50"));
    attachImage("exp-reject", "s1");
    service.submit(storeManager(), "exp-reject");

    assertThatThrownBy(() -> service.reject(finance(), "exp-reject", new ExpenseReviewRequest(" ")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("REJECT_REASON_REQUIRED"));

    long submittedAttachmentId = jdbcTemplate.queryForObject(
        "select id from warehouse_attachment where business_id = 'exp-reject'", Long.class);
    assertThatThrownBy(() -> service.deleteAttachment(storeManager(), "exp-reject", submittedAttachmentId))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_STATUS"));

    service.save(storeManager(), "exp-draft-receipt", request("s1", "2026-05", "88.00"));
    attachImage("exp-draft-receipt", "s1");
    long draftAttachmentId = jdbcTemplate.queryForObject(
        "select id from warehouse_attachment where business_id = 'exp-draft-receipt'", Long.class);
    service.deleteAttachment(storeManager(), "exp-draft-receipt", draftAttachmentId);

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_attachment where business_id = 'exp-draft-receipt'", Integer.class)).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'reimbursement_attachment_delete' and target_id = 'exp-draft-receipt'",
        Integer.class)).isEqualTo(1);
  }

  @Test
  void reviewNotesOverAuditLimitAreRejectedBeforeAnyStateOrAuditChange() {
    service.save(storeManager(), "exp-long-note", request("s1", "2026-05", "128.50"));
    attachImage("exp-long-note", "s1");
    service.submit(storeManager(), "exp-long-note");
    String tooLong = "审".repeat(256);

    assertThatThrownBy(() -> service.requestInfo(finance(), "exp-long-note", new ExpenseReviewRequest(tooLong)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("REVIEW_NOTE_REQUIRED_TOO_LONG"));
    assertThatThrownBy(() -> service.reject(finance(), "exp-long-note", new ExpenseReviewRequest(tooLong)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("REJECT_REASON_REQUIRED_TOO_LONG"));

    assertThat(jdbcTemplate.queryForObject(
        "select status from expense_claim where id = 'exp-long-note'", String.class)).isEqualTo("待审核");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from operation_log where target_id = 'exp-long-note' and action in ('expense_request_info', 'reimbursement_reject')",
        Integer.class)).isZero();
  }

  @Test
  void storeManagerExpenseMutationUsesInternalReconciliationNotProfitWritePermission() {
    BusinessTodoService todoService = mock(BusinessTodoService.class);
    ExpenseService reconciledService = new ExpenseService(repository, null, null, todoService, null);

    ExpenseClaimResponse saved = reconciledService.save(
        storeManager(), "exp-manager-reconcile", request("s1", "2026-05", "128.50"));
    attachImage("exp-manager-reconcile", "s1");
    ExpenseClaimResponse submitted = reconciledService.submit(storeManager(), "exp-manager-reconcile");

    assertThat(saved.status()).isEqualTo("草稿");
    assertThat(submitted.status()).isEqualTo("待审核");
    verify(todoService, times(2)).reconcileExpenseReviewForStore(storeManager().tenantId(), "s1", "2026-05");
  }

  @Test
  void updatingAnExistingClaimCannotMoveItsStoreAndLeavePrimaryReceiptsOrphaned() {
    service.save(boss(), "exp-store-lock", request("s1", "2026-05", "100"));
    attachImage("exp-store-lock", "s1");

    assertThatThrownBy(() -> service.save(boss(), "exp-store-lock", request("s2", "2026-05", "100")))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("EXPENSE_STORE_IMMUTABLE"));

    assertThat(jdbcTemplate.queryForObject(
        "select store_id from expense_claim where id = 'exp-store-lock'", String.class)).isEqualTo("s1");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_attachment where business_id = 'exp-store-lock' and store_id = 's1'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void deletingDraftCleansPrimaryReceiptsButSubmittedClaimCannotBeDeleted() {
    service.save(boss(), "draft-with-receipt", request("s1", "2026-05", "100"));
    attachImage("draft-with-receipt", "s1");

    service.delete(boss(), "draft-with-receipt");

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from warehouse_attachment where business_id = 'draft-with-receipt'", Integer.class)).isZero();

    service.save(boss(), "pending-delete", request("s1", "2026-05", "100"));
    attachImage("pending-delete", "s1");
    service.submit(boss(), "pending-delete");
    assertThatThrownBy(() -> service.delete(boss(), "pending-delete"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_STATUS"));
  }

  private ExpenseClaimRequest request(String storeId, String month, String amount) {
    return new ExpenseClaimRequest(
        storeId,
        month,
        month + "-15",
        new BigDecimal(amount),
        "物料采购",
        "牛奶采购",
        "https://example.test/invoice.jpg"
    );
  }

  private void attachImage(String expenseId, String storeId) {
    jdbcTemplate.update("""
        insert into warehouse_attachment(
          tenant_id, store_id, business_type, business_id, file_name, content_type, file_size
        )
        values (1, ?, 'EXPENSE_CLAIM', ?, 'invoice.jpg', 'image/jpeg', 12)
        """, storeId, expenseId);
  }

  private AuthUser boss() {
    return user(1L, "BOSS", null);
  }

  private AuthUser finance() {
    return user(2L, "FINANCE", null);
  }

  private AuthUser storeManager() {
    return user(3L, "STORE_MANAGER", "s1");
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "default", role.toLowerCase(), "", role, role, storeId, true);
  }
}
