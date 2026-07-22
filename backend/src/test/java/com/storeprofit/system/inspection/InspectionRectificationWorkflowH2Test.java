package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.inspection.InspectionRectificationRepository.InspectionRectificationRecord;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class InspectionRectificationWorkflowH2Test {
  private static final long TENANT_ID = 1L;
  private static final String STORE_A = "INS_RECT_A";
  private static final String STORE_B = "INS_RECT_B";

  @Test
  void successfulReviewPersistsReviewerDecisionAndAuditAndASecondReviewCannotOverwriteIt() {
    Fixture fixture = fixture();
    fixture.pendingReview(TENANT_ID, "rectification-review-1", "inspection-review-1", STORE_A);
    InspectionRecordResponse record = record("inspection-review-1", STORE_A);
    when(fixture.records().record(TENANT_ID, record.id())).thenReturn(Optional.of(record));

    InspectionRectificationResponse result = fixture.service().review(
        fixture.supervisor(), record.id(), new InspectionRectificationReviewRequest("APPROVED", "证据已核对"));

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(fixture.jdbc().queryForMap("""
        select status, reviewed_by, reviewed_by_name, review_note, reviewed_at
        from inspection_rectification where tenant_id = ? and inspection_record_id = ?
        """, TENANT_ID, record.id()))
        .containsEntry("STATUS", "APPROVED")
        .containsEntry("REVIEWED_BY", fixture.supervisor().id())
        .containsEntry("REVIEWED_BY_NAME", fixture.supervisor().displayName())
        .containsEntry("REVIEW_NOTE", "证据已核对");
    assertThat(fixture.jdbc().queryForObject("""
        select count(*) from inspection_rectification_action
        where tenant_id = ? and inspection_record_id = ? and action = 'APPROVED' and status = 'APPROVED'
        """, Integer.class, TENANT_ID, record.id())).isEqualTo(1);
    assertThat(fixture.jdbc().queryForObject("""
        select count(*) from operation_log
        where tenant_id = ? and target_id = ? and action = 'inspection_rectification_review_approved'
        """, Integer.class, TENANT_ID, record.id())).isEqualTo(1);

    assertThatThrownBy(() -> fixture.service().review(
        fixture.supervisor(), record.id(), new InspectionRectificationReviewRequest("REJECTED", "不应覆盖")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("RECTIFICATION_STATE_CONFLICT");
    assertThat(fixture.jdbc().queryForObject("""
        select review_note from inspection_rectification where tenant_id = ? and inspection_record_id = ?
        """, String.class, TENANT_ID, record.id())).isEqualTo("证据已核对");
    assertThat(fixture.jdbc().queryForObject("""
        select count(*) from inspection_rectification_action
        where tenant_id = ? and inspection_record_id = ?
        """, Integer.class, TENANT_ID, record.id())).isEqualTo(1);
  }

  @Test
  void crossStoreReviewIsForbiddenBeforeTheWorkflowChangesAndCrossTenantRecordIsInvisible() {
    Fixture fixture = fixture();
    fixture.pendingReview(TENANT_ID, "rectification-store-b", "inspection-store-b", STORE_B);
    InspectionRecordResponse crossStore = record("inspection-store-b", STORE_B);
    when(fixture.records().record(TENANT_ID, crossStore.id())).thenReturn(Optional.of(crossStore));
    BusinessException denied = new BusinessException("FORBIDDEN", "门店不在当前账号范围内", HttpStatus.FORBIDDEN);
    doThrow(denied).when(fixture.access()).requireStoreAccess(
        eq(fixture.supervisor()), eq(DataScopeDomains.INSPECTION), eq(STORE_B), anyString());

    assertThatThrownBy(() -> fixture.service().review(
        fixture.supervisor(), crossStore.id(), new InspectionRectificationReviewRequest("APPROVED", "越店复核")))
        .isSameAs(denied);
    assertThat(status(fixture, TENANT_ID, crossStore.id())).isEqualTo("PENDING_REVIEW");
    assertThat(operationCount(fixture, TENANT_ID, crossStore.id())).isZero();

    fixture.jdbc().update("""
        insert into tenant(id, name, industry, scale, status, created_at)
        values (2, '巡检隔离租户', 'test', 'test', 'ACTIVE', current_timestamp)
        """);
    fixture.pendingReview(2L, "rectification-foreign", "inspection-foreign", "INS_FOREIGN");
    when(fixture.records().record(TENANT_ID, "inspection-foreign")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> fixture.service().review(
        fixture.supervisor(), "inspection-foreign", new InspectionRectificationReviewRequest("APPROVED", "越租户复核")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getStatus())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(status(fixture, 2L, "inspection-foreign")).isEqualTo("PENDING_REVIEW");
    assertThat(operationCount(fixture, TENANT_ID, "inspection-foreign")).isZero();
  }

  private Fixture fixture() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:inspection_rectification_" + UUID.randomUUID()
        + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    InspectionRecordRepository records = mock(InspectionRecordRepository.class);
    AccessControlService access = mock(AccessControlService.class);
    InspectionRectificationRepository rectifications = new InspectionRectificationRepository(
        new NamedParameterJdbcTemplate(dataSource));
    InspectionRectificationService service = new InspectionRectificationService(
        access, records, rectifications, mock(StorageService.class));
    return new Fixture(jdbc, records, access, rectifications, service);
  }

  private InspectionRecordResponse record(String id, String storeId) {
    return new InspectionRecordResponse(
        id, storeId, storeId, "合成巡检门店", 1L, "合成品牌", "2026-07-21", "测试督导", "合成品牌",
        new BigDecimal("200"), new BigDecimal("176"), false,
        "[]", "[{\"code\":\"R-1\"}]", "[]", "需整改");
  }

  private static String status(Fixture fixture, long tenantId, String recordId) {
    return fixture.jdbc().queryForObject("""
        select status from inspection_rectification where tenant_id = ? and inspection_record_id = ?
        """, String.class, tenantId, recordId);
  }

  private static int operationCount(Fixture fixture, long tenantId, String recordId) {
    return fixture.jdbc().queryForObject("""
        select count(*) from operation_log where tenant_id = ? and target_id = ?
        """, Integer.class, tenantId, recordId);
  }

  private record Fixture(
      JdbcTemplate jdbc,
      InspectionRecordRepository records,
      AccessControlService access,
      InspectionRectificationRepository rectifications,
      InspectionRectificationService service
  ) {
    AuthUser supervisor() {
      return new AuthUser(802L, TENANT_ID, "巡检租户", "inspection_supervisor", "hash",
          "测试督导", "SUPERVISOR", null, true, 1L);
    }

    void pendingReview(long tenantId, String rectificationId, String recordId, String storeId) {
      rectifications.create(tenantId, rectificationId, recordId, storeId);
      boolean submitted = rectifications.submit(tenantId, recordId, "店长已提交整改", 801L, "测试店长");
      assertThat(submitted).isTrue();
      assertThat(status(this, tenantId, recordId)).isEqualTo("PENDING_REVIEW");
    }
  }
}
