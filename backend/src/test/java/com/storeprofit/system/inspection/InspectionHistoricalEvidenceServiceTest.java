package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

class InspectionHistoricalEvidenceServiceTest {
  private JdbcTemplate jdbcTemplate;
  private AccessControlService accessControl;
  private InspectionRecordRepository repository;
  private StorageService storageService;
  private InspectionService service;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa", "");
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, '品牌')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values ('s1', 1, 1, '001', '一店'), ('s2', 1, 1, '002', '二店')
        """);
    accessControl = mock(AccessControlService.class);
    repository = new InspectionRecordRepository(jdbcTemplate, new NamedParameterJdbcTemplate(dataSource));
    storageService = new StorageService(jdbcTemplate, accessControl);
    service = new InspectionService(
        repository, accessControl, null, storageService,
        "http://127.0.0.1:8000/detect", "http://127.0.0.1:8000/export", Duration.ofSeconds(1));
  }

  @Test
  void existingAttachmentCanBeLinkedWithoutChangingHistoricScoreOrSnapshotFactsAndWritesPairAudit() {
    insertRecord("history-1", "[{\"attachmentId\":41}]");
    insertSnapshot("history-1", 11L, "[]");
    insertInspectionAttachment(41L, "s1", "history-1", "现场.jpg", "image/jpeg", jpegBytes(), 8L);
    Map<String, Object> before = jdbcTemplate.queryForMap("""
        select score, passed, deductions_json, redlines_json, standard_version, result_code
          from inspection_record where id = 'history-1'
        """);
    Map<String, Object> snapshotBefore = jdbcTemplate.queryForMap("""
        select standard_version, suggested_score, actual_deduction_score, actual_score,
               problem_description, red_line, rectification_status
          from inspection_record_standard_snapshot
         where inspection_record_id = 'history-1' and standard_id = 11
        """);

    InspectionEvidenceCandidatesResponse candidates = service.historicalEvidenceCandidates(supervisor(), "history-1");
    assertThat(candidates.candidates()).singleElement().satisfies(candidate -> {
      assertThat(candidate.attachmentId()).isEqualTo(41L);
      assertThat(candidate.status()).isEqualTo("UNLINKED");
    });

    InspectionEvidenceLinkResponse response = service.linkHistoricalEvidence(
        supervisor(), "history-1", InspectionEvidenceLinkRequest.forStandardItems(List.of(41L), List.of(11L)));

    assertThat(response.recordId()).isEqualTo("history-1");
    assertThat(response.action()).isEqualTo("ASSOCIATE");
    assertThat(response.record().score()).isEqualByComparingTo("176.00");
    assertThat(jdbcTemplate.queryForMap("""
        select score, passed, deductions_json, redlines_json, standard_version, result_code
          from inspection_record where id = 'history-1'
        """)).isEqualTo(before);
    assertThat(jdbcTemplate.queryForMap("""
        select standard_version, suggested_score, actual_deduction_score, actual_score,
               problem_description, red_line, rectification_status
          from inspection_record_standard_snapshot
         where inspection_record_id = 'history-1' and standard_id = 11
        """)).isEqualTo(snapshotBefore);
    assertThat(jdbcTemplate.queryForObject("""
        select photo_attachment_ids_json from inspection_record_standard_snapshot
         where inspection_record_id = 'history-1' and standard_id = 11
        """, String.class)).isEqualTo("[41]");
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from operation_log
         where action = 'inspection_historical_evidence_link' and target_id = 'history-1'
           and reason like '%条款ID=11；附件ID=41%'
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void existingRecordWithoutPhotosOrRecordAttachmentsReturnsAnEmptyCandidateList() {
    insertRecord("history-empty", "[]");
    AuthUser user = supervisor();

    InspectionEvidenceCandidatesResponse response = service.historicalEvidenceCandidates(user, "history-empty");

    assertThat(response.recordId()).isEqualTo("history-empty");
    assertThat(response.storeId()).isEqualTo("s1");
    assertThat(response.candidates()).isEmpty();
    verify(accessControl).requireInspectionRead(user);
    verify(accessControl).requireInspectionManage(user);
    verify(accessControl).requireStoreAccess(
        user, DataScopeDomains.INSPECTION, "s1", "查看历史巡检证据");
  }

  @Test
  void candidateReadHonorsInspectionStoreScopeBeforeInspectingAttachmentRows() {
    insertRecord("history-out-of-scope", "[]");
    AuthUser user = supervisor();
    BusinessException forbidden = new BusinessException(
        "FORBIDDEN", "门店不在当前账号的数据范围内", HttpStatus.FORBIDDEN);
    doThrow(forbidden).when(accessControl).requireStoreAccess(
        user, DataScopeDomains.INSPECTION, "s1", "查看历史巡检证据");

    assertThatThrownBy(() -> service.historicalEvidenceCandidates(user, "history-out-of-scope"))
        .isSameAs(forbidden);

    verify(accessControl).requireInspectionRead(user);
    verify(accessControl).requireInspectionManage(user);
    verify(accessControl).requireStoreAccess(
        user, DataScopeDomains.INSPECTION, "s1", "查看历史巡检证据");
  }

  @Test
  void metadataOnlyOrDeletedImageIsReportedAsReuploadRequiredAndCanBeReplacedByExactPhotoIndex() {
    insertRecord("history-missing", "[{\"filename\":\"微信图片.jpg\"}]");
    insertSnapshot("history-missing", 11L, "[]");

    InspectionEvidenceCandidatesResponse before = service.historicalEvidenceCandidates(supervisor(), "history-missing");
    assertThat(before.candidates()).singleElement().satisfies(candidate -> {
      assertThat(candidate.photoIndex()).isZero();
      assertThat(candidate.attachmentId()).isNull();
      assertThat(candidate.status()).isEqualTo("ORIGINAL_NOT_STORED");
      assertThat(candidate.message()).isEqualTo("原图未入库，需补传");
    });

    InspectionEvidenceLinkResponse response = service.uploadAndLinkHistoricalEvidence(
        supervisor(), "history-missing",
        new MockMultipartFile("file", "wechat-original.jpg", "image/jpeg", jpegBytes()),
        List.of(11L), List.of(), 0);

    assertThat(response.action()).isEqualTo("SUPPLEMENT");
    assertThat(response.recordId()).isEqualTo("history-missing");
    Long attachmentId = response.attachmentIds().getFirst();
    assertThat(response.record().photosJson()).contains("attachmentId", attachmentId.toString(), "wechat-original.jpg");
    assertThat(storageService.attachment(supervisor(), attachmentId))
        .hasValueSatisfying(content -> assertThat(content.content()).isEqualTo(jpegBytes()));
    assertThat(jdbcTemplate.queryForObject("""
        select photo_attachment_ids_json from inspection_record_standard_snapshot
         where inspection_record_id = 'history-missing' and standard_id = 11
        """, String.class)).isEqualTo("[" + attachmentId + "]");
  }

  @Test
  void positiveDeletedPhotoIdCanBeReplacedAtTheSelectedIndexButForgedAttachmentIsForbidden() {
    insertRecord("history-deleted", "[{\"attachmentId\":404,\"filename\":\"gone.jpg\"}]");
    insertSnapshot("history-deleted", 11L, "[]");

    InspectionEvidenceLinkResponse response = service.uploadAndLinkHistoricalEvidence(
        supervisor(), "history-deleted",
        new MockMultipartFile("file", "new.jpg", "image/jpeg", jpegBytes()),
        List.of(11L), List.of(), 0);
    assertThat(response.record().photosJson()).doesNotContain("404");
    assertThat(response.record().photosJson()).contains("attachmentId");

    insertRecord("history-forged", "[]");
    insertSnapshot("history-forged", 11L, "[]");
    insertInspectionAttachment(99L, "s1", "another-record", "other.jpg", "image/jpeg", jpegBytes(), 8L);
    assertThatThrownBy(() -> service.linkHistoricalEvidence(
        supervisor(), "history-forged", InspectionEvidenceLinkRequest.forStandardItems(List.of(99L), List.of(11L))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("ATTACHMENT_SCOPE_MISMATCH"));
  }

  @Test
  void nonSupervisorAndCrossStoreAttachmentAreForbidden() {
    insertRecord("history-role", "[{\"attachmentId\":51}]");
    insertSnapshot("history-role", 11L, "[]");
    insertInspectionAttachment(51L, "s1", "history-role", "same.jpg", "image/jpeg", jpegBytes(), 8L);
    assertThatThrownBy(() -> service.linkHistoricalEvidence(
        storeManager(), "history-role", InspectionEvidenceLinkRequest.forStandardItems(List.of(51L), List.of(11L))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

    insertRecord("history-cross", "[{\"attachmentId\":52}]");
    insertSnapshot("history-cross", 11L, "[]");
    insertInspectionAttachment(52L, "s2", "history-cross", "cross.jpg", "image/jpeg", jpegBytes(), 8L);
    assertThatThrownBy(() -> service.linkHistoricalEvidence(
        supervisor(), "history-cross", InspectionEvidenceLinkRequest.forStandardItems(List.of(52L), List.of(11L))))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("ATTACHMENT_SCOPE_MISMATCH"));
  }

  @Test
  void newInspectionRejectsPhotoWithoutAnExplicitClauseLinkBeforeAnyWrite() {
    InspectionRecordRepository recordRepository = mock(InspectionRecordRepository.class);
    AccessControlService controls = mock(AccessControlService.class);
    when(recordRepository.storeExists(1L, "s1")).thenReturn(true);
    InspectionService unlinkedSaveService = new InspectionService(
        recordRepository, controls, null, null,
        "http://127.0.0.1:8000/detect", "http://127.0.0.1:8000/export", Duration.ofSeconds(1));
    InspectionRecordRequest request = new InspectionRecordRequest(
        "s1", "2026-07-14", "督导", "品牌", new BigDecimal("200"), new BigDecimal("200"), true,
        "[]", "[]", "[{\"attachmentId\":999}]", "", null, null, null, null, null, null, List.of());

    assertThatThrownBy(() -> unlinkedSaveService.save(supervisor(), null, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("INSPECTION_EVIDENCE_UNLINKED"));
    verify(recordRepository).storeExists(1L, "s1");
  }

  private void insertRecord(String id, String photosJson) {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand, full_score, score, passed,
          deductions_json, redlines_json, photos_json, standard_version_id, standard_version,
          material_score, hygiene_score, service_score, result_code
        ) values (?, 1, 's1', '2026-07-14', '督导', '品牌', 200, 176, 0,
          '[{\"standard_id\":11,\"deduct\":24}]', '[{\"standard_id\":11}]', ?, 38, '历史标准',
          50, 60, 66, 'RED_LINE_FAILED')
        """, id, photosJson);
  }

  private void insertSnapshot(String recordId, long standardId, String photoIds) {
    jdbcTemplate.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_id, standard_version, dimension, standard_title,
          suggested_score, actual_deduction_score, red_line, problem_description, sort_order,
          standard_code, actual_score, risk_level, photo_attachment_ids_json, rectification_status
        ) values (1, ?, ?, '历史标准', '卫生', '现场卫生', 24, 24, 1, '历史扣分原因', 1,
          'H-1', 0, 'RED', ?, 'PENDING')
        """, recordId, standardId, photoIds);
  }

  private void insertInspectionAttachment(
      long id,
      String storeId,
      String businessId,
      String fileName,
      String contentType,
      byte[] content,
      long uploadedBy
  ) {
    jdbcTemplate.update("""
        insert into warehouse_attachment(
          id, tenant_id, store_id, business_type, business_id, file_name, content_type,
          file_size, storage_path, content, uploaded_by, uploaded_at
        ) values (?, 1, ?, 'INSPECTION_RECORD', ?, ?, ?, ?, 'mysql://test', ?, ?, current_timestamp)
        """, id, storeId, businessId, fileName, contentType, content.length, content, uploadedBy);
  }

  private AuthUser supervisor() {
    return new AuthUser(7L, 1L, "default", "supervisor", "", "督导", "SUPERVISOR", null, true);
  }

  private AuthUser storeManager() {
    return new AuthUser(8L, 1L, "default", "manager", "", "店长", "STORE_MANAGER", "s1", true);
  }

  private byte[] jpegBytes() {
    return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
  }

  private void createSchema() {
    jdbcTemplate.execute("create table brand(id bigint primary key, tenant_id bigint not null, name varchar(120) not null)");
    jdbcTemplate.execute("""
        create table store_branch(
          id varchar(64) primary key, tenant_id bigint not null, brand_id bigint, code varchar(80), name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_record(
          id varchar(120) primary key, tenant_id bigint not null, store_id varchar(64) not null,
          inspection_date date not null, inspector varchar(120), brand varchar(120),
          full_score decimal(8,2), score decimal(8,2), passed tinyint, deductions_json longtext,
          redlines_json longtext, photos_json longtext, note longtext, standard_version_id bigint,
          standard_version varchar(64), material_score decimal(8,2), hygiene_score decimal(8,2),
          service_score decimal(8,2), result_code varchar(32), test_marker varchar(32),
          created_at timestamp default current_timestamp, updated_at timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_record_standard_snapshot(
          id bigint auto_increment primary key, tenant_id bigint not null, inspection_record_id varchar(120) not null,
          standard_id bigint, standard_version varchar(64), dimension varchar(120), standard_title varchar(500),
          standard_description longtext, suggested_score decimal(8,2), actual_deduction_score decimal(8,2),
          red_line tinyint, problem_description longtext, sort_order int, standard_code varchar(80),
          check_method longtext, actual_score decimal(8,2), risk_level varchar(16), photo_attachment_ids_json longtext,
          responsible_person varchar(160), rectification_deadline date, rectification_status varchar(32),
          review_result longtext, before_photo_attachment_ids_json longtext, after_photo_attachment_ids_json longtext,
          created_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_attachment(
          id bigint auto_increment primary key, tenant_id bigint not null, store_id varchar(64) not null,
          business_type varchar(60) not null, business_id varchar(120) not null, file_name varchar(255),
          content_type varchar(120), file_size bigint, storage_path varchar(500), content blob,
          uploaded_by bigint, uploaded_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log(
          id bigint auto_increment primary key, tenant_id bigint not null, operator_id bigint, operator_name varchar(120),
          action varchar(80) not null, target_type varchar(80) not null, target_id varchar(120),
          store_id varchar(64), month char(7), reason varchar(255), created_at timestamp default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_result_repair_audit(
          id bigint auto_increment primary key, tenant_id bigint not null, inspection_record_id varchar(120) not null,
          original_standard_version_id bigint, original_standard_version varchar(64), original_full_score decimal(8,2),
          original_pass_score decimal(8,2), original_score decimal(8,2), original_material_score decimal(8,2),
          original_hygiene_score decimal(8,2), original_service_score decimal(8,2), original_result_code varchar(32),
          original_passed tinyint, repaired_standard_version_id bigint, repaired_standard_version varchar(64),
          repaired_full_score decimal(8,2), repaired_pass_score decimal(8,2), repaired_score decimal(8,2),
          repaired_material_score decimal(8,2), repaired_hygiene_score decimal(8,2), repaired_service_score decimal(8,2),
          repaired_result_code varchar(32), repaired_passed tinyint, repair_status varchar(32), repair_reason varchar(500),
          snapshot_item_count int, expected_item_count int, repaired_by bigint, repaired_at timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_score_scale_migration_audit(
          id bigint auto_increment primary key, tenant_id bigint not null, inspection_record_id varchar(120) not null,
          migration_key varchar(64), original_full_score decimal(8,2), original_pass_score decimal(8,2),
          original_score decimal(8,2), original_material_score decimal(8,2), original_hygiene_score decimal(8,2),
          original_service_score decimal(8,2), original_passed tinyint, original_result_code varchar(32),
          converted_full_score decimal(8,2), converted_pass_score decimal(8,2), converted_score decimal(8,2),
          converted_material_score decimal(8,2), converted_hygiene_score decimal(8,2), converted_service_score decimal(8,2),
          converted_passed tinyint, converted_result_code varchar(32), migrated_at timestamp
        )
        """);
  }
}
