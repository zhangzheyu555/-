package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExpenseServiceTest {
  private JdbcTemplate jdbcTemplate;
  private ExpenseService service;
  private ExpenseRepository repository;

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

  private ExpenseClaimRequest request(String storeId, String month, String amount) {
    return new ExpenseClaimRequest(
        storeId,
        month,
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
