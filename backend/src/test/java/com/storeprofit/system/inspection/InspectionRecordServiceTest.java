package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class InspectionRecordServiceTest {
  private JdbcTemplate jdbcTemplate;
  private InspectionService service;
  private InspectionRecordRepository repository;

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
        create table inspection_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          inspection_date date not null,
          inspector varchar(120) null,
          brand varchar(120) null,
          full_score decimal(8,2) not null default 200,
          score decimal(8,2) not null default 200,
          passed tinyint(1) not null default 1,
          deductions_json longtext null,
          redlines_json longtext null,
          photos_json longtext null,
          note text null,
          standard_version_id bigint null,
          standard_version varchar(64) null,
          material_score decimal(8,2) null,
          hygiene_score decimal(8,2) null,
          service_score decimal(8,2) null,
          result_code varchar(32) null,
          test_marker varchar(32) null,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null default null
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_record_standard_snapshot (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          inspection_record_id varchar(120) not null,
          standard_id bigint null,
          standard_version varchar(64) null,
          dimension varchar(120) null,
          standard_title varchar(500) null,
          standard_description text null,
          suggested_score decimal(8,2) not null default 0,
          actual_deduction_score decimal(8,2) not null default 0,
          red_line tinyint(1) not null default 0,
          problem_description text null,
          sort_order int not null default 0,
          standard_code varchar(80) null,
          check_method longtext null,
          actual_score decimal(8,2) not null default 0,
          risk_level varchar(16) not null default 'NORMAL',
          photo_attachment_ids_json longtext null,
          responsible_person varchar(160) null,
          rectification_deadline date null,
          rectification_status varchar(32) null,
          review_result longtext null,
          before_photo_attachment_ids_json longtext null,
          after_photo_attachment_ids_json longtext null,
          created_at timestamp not null default current_timestamp
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
    createRepairAuditTable();
    createScoreScaleMigrationAuditTable();
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea'), (2, 2, 'Other')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values
          ('s1', 1, 1, '001', 'One'),
          ('s2', 1, 1, '002', 'Two'),
          ('other', 2, 2, '099', 'Other')
        """);
    repository = new InspectionRecordRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    service = new InspectionService(repository, "http://127.0.0.1:8000/detect", "http://127.0.0.1:8000/export", Duration.ofSeconds(5));
  }

  @Test
  void supervisorCanCreateListAndReadTenantScopedInspectionRecords() {
    InspectionRecordResponse first = service.save(supervisor(), null, request("s1", "2026-05-21", "92.00", false));
    InspectionRecordResponse second = service.save(supervisor(), null, request("s2", "2026-05-22", "100.00", true));
    jdbcTemplate.update("""
        insert into inspection_record(id, tenant_id, store_id, inspection_date, inspector, brand, full_score, score, passed)
        values ('other-insp', 2, 'other', '2026-05-21', 'Other', 'Other', 100, 50, 0)
        """);

    List<InspectionRecordResponse> records = service.records(boss(), "2026-05-01", "2026-05-31", null, null, null);
    InspectionRecordResponse detail = service.record(boss(), first.id());

    assertThat(first.id()).isNotBlank();
    assertThat(second.id()).isNotBlank();
    assertThat(records).extracting(InspectionRecordResponse::storeName).containsExactly("One", "Two");
    assertThat(detail.score()).isEqualByComparingTo("184.00");
    assertThat(detail.passed()).isFalse();
    assertThat(detail.deductionsJson()).contains("counter");
    Integer snapshotCount = jdbcTemplate.queryForObject(
        "select count(*) from inspection_record_standard_snapshot where tenant_id = 1 and inspection_record_id = ?",
        Integer.class,
        first.id()
    );
    assertThat(snapshotCount).isEqualTo(2);
  }

  @Test
  void storeManagerReadsOnlyOwnStoreAndCannotWrite() {
    service.save(supervisor(), "insp-s1", request("s1", "2026-05-21", "92.00", false));
    service.save(supervisor(), "insp-s2", request("s2", "2026-05-22", "100.00", true));

    List<InspectionRecordResponse> records = service.records(storeManager(), "2026-05-01", "2026-05-31", null, null, null);

    assertThat(records).extracting(InspectionRecordResponse::id).containsExactly("insp-s1");
    assertThatThrownBy(() -> service.records(storeManager(), null, null, null, "s2", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.save(storeManager(), null, request("s1", "2026-05-21", "92.00", false)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.record(storeManager(), "insp-s2"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void saveRejectsBadDateMissingStoreAndInvalidScore() {
    assertThatThrownBy(() -> service.save(supervisor(), null, request("s1", "20260521", "92.00", false)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_DATE"));
    assertThatThrownBy(() -> service.save(supervisor(), null, request("missing", "2026-05-21", "92.00", false)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("STORE_NOT_FOUND"));
    assertThatThrownBy(() -> service.save(supervisor(), null, request("s1", "2026-05-21", "120.00", false)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_SCORE"));
  }

  @Test
  void updateAndDeleteWriteAuditLog() {
    service.save(supervisor(), "insp-1", request("s1", "2026-05-21", "92.00", false));

    InspectionRecordResponse updated = service.save(boss(), "insp-1", request("s1", "2026-05-21", "98.00", true));
    service.delete(boss(), "insp-1");

    assertThat(updated.score()).isEqualByComparingTo("196.00");
    assertThat(service.records(boss(), "2026-05-01", "2026-05-31", null, null, null)).isEmpty();
    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where target_type = 'inspection_record' and target_id = 'insp-1'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(3);
  }

  @Test
  void repairedHistoryRejectsUpdateAndDeleteWithoutChangingRawRecordSnapshotsOrAudit() {
    service.save(supervisor(), "repaired-immutable", request("s1", "2026-05-21", "92.00", false));
    assertThat(repository.insertRepairAudit(1L, "repaired-immutable", repairWriteForRace())).isTrue();
    Map<String, Object> originalRow = jdbcTemplate.queryForMap("""
        select inspector, score, passed, deductions_json, redlines_json, photos_json, note
        from inspection_record where tenant_id = 1 and id = 'repaired-immutable'
        """);
    List<Map<String, Object>> originalSnapshots = jdbcTemplate.queryForList("""
        select standard_id, standard_version, dimension, standard_title, suggested_score,
               actual_deduction_score, actual_score, problem_description
        from inspection_record_standard_snapshot
        where tenant_id = 1 and inspection_record_id = 'repaired-immutable'
        order by id
        """);
    Integer originalRepairCount = jdbcTemplate.queryForObject("""
        select count(*) from inspection_result_repair_audit
        where tenant_id = 1 and inspection_record_id = 'repaired-immutable'
        """, Integer.class);
    Integer originalLogCount = jdbcTemplate.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 1 and target_id = 'repaired-immutable'
        """, Integer.class);

    assertThatThrownBy(() -> service.save(
        boss(), "repaired-immutable", request("s1", "2026-05-22", "12.00", false)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertImmutableConflict((BusinessException) error));
    assertThatThrownBy(() -> service.delete(boss(), "repaired-immutable"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertImmutableConflict((BusinessException) error));
    assertThatThrownBy(() -> service.bindDetectionResults(
        boss(), "repaired-immutable", detectionBindingRequest()))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertImmutableConflict((BusinessException) error));

    assertThat(jdbcTemplate.queryForMap("""
        select inspector, score, passed, deductions_json, redlines_json, photos_json, note
        from inspection_record where tenant_id = 1 and id = 'repaired-immutable'
        """)).isEqualTo(originalRow);
    assertThat(jdbcTemplate.queryForList("""
        select standard_id, standard_version, dimension, standard_title, suggested_score,
               actual_deduction_score, actual_score, problem_description
        from inspection_record_standard_snapshot
        where tenant_id = 1 and inspection_record_id = 'repaired-immutable'
        order by id
        """)).isEqualTo(originalSnapshots);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from inspection_result_repair_audit
        where tenant_id = 1 and inspection_record_id = 'repaired-immutable'
        """, Integer.class)).isEqualTo(originalRepairCount);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from operation_log
        where tenant_id = 1 and target_id = 'repaired-immutable'
        """, Integer.class)).isEqualTo(originalLogCount);
  }

  @Test
  void supervisorCanBindDetectionResultsToExistingRecord() {
    service.save(supervisor(), "insp-1", request("s1", "2026-05-21", "100.00", true));

    InspectionRecordResponse bound = service.bindDetectionResults(
        supervisor(),
        "insp-1",
        new InspectionDetectionBindingRequest(
            "Inspector B",
            "Tea",
            new BigDecimal("100.00"),
            List.of(Map.ofEntries(
                Map.entry("image_id", "img-1"),
                Map.entry("filename", "floor.jpg"),
                Map.entry("passed", false),
                Map.entry("auto_status", "not clean"),
                Map.entry("detection_count", 2),
                Map.entry("detection_summary", "paper on floor"),
                Map.entry("deduction_project", "store interior"),
                Map.entry("deduction_content", "floor litter"),
                Map.entry("deduction_score", -2),
                Map.entry("detections", List.of(Map.of("class_name", "paper", "confidence", 0.91))),
                Map.entry("annotated_image", "data:image/png;base64,abc")
            )),
            "AI detection result"
        )
    );

    assertThat(bound.inspector()).isEqualTo("Inspector B");
    assertThat(bound.score()).isEqualByComparingTo("200.00");
    assertThat(bound.passed()).isTrue();
    assertThat(bound.deductionsJson()).contains("counter");
    assertThat(bound.redlinesJson()).isEqualTo("[]");
    assertThat(bound.photosJson()).contains("floor.jpg", "PENDING_MANUAL_CONFIRMATION");
    assertThat(bound.photosJson()).doesNotContain("base64");

    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where action = 'inspection_detection_bind' and target_id = 'insp-1'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void bindingDetectionResultsRejectsStoreManagerAndEmptyResults() {
    service.save(supervisor(), "insp-1", request("s1", "2026-05-21", "100.00", true));

    assertThatThrownBy(() -> service.bindDetectionResults(
        storeManager(),
        "insp-1",
        detectionBindingRequest()
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> service.bindDetectionResults(
        supervisor(),
        "insp-1",
        new InspectionDetectionBindingRequest(null, null, null, List.of(), null)
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_DETECTION_RESULTS"));
  }

  @Test
  void legacyNullCategoryScoresStayNullAndProblemDescriptionRestoresIssueFlag() {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand, full_score, score, passed,
          standard_version_id, standard_version, result_code
        ) values ('legacy-null', 1, 's1', '2026-05-21', '督导', 'Tea', 200, 179, 0, 38, '2025.11.06', null)
        """);
    jdbcTemplate.update("""
        insert into inspection_record_standard_snapshot(
          tenant_id, inspection_record_id, standard_id, standard_version, dimension,
          standard_title, suggested_score, actual_deduction_score, red_line,
          problem_description, sort_order, standard_code, actual_score, risk_level
        ) values (1, 'legacy-null', 22, '2025.11.06', '物料标准', '黄线提醒',
                  4, 0, 0, '需要关注但本次不扣分', 22, 'M-YELLOW-1', 4, 'YELLOW')
        """);

    InspectionRecordResponse record = service.record(boss(), "legacy-null");

    assertThat(record.materialScore()).isNull();
    assertThat(record.hygieneScore()).isNull();
    assertThat(record.serviceScore()).isNull();
    assertThat(record.resultCode()).isEqualTo("FAILED");
    assertThat(record.itemResults()).singleElement()
        .satisfies(item -> {
          assertThat(item.issueFound()).isTrue();
          assertThat(item.deductionScore()).isEqualByComparingTo("0.00");
        });
  }

  @Test
  void presentsLegacyHundredPointScoresOnTheCanonicalTwoHundredPointScale() {
    insertRecord("legacy-98", "100.00", "98.00", true, "PASSED", null);

    InspectionRecordResponse record = service.record(boss(), "legacy-98");

    assertThat(record.fullScore()).isEqualByComparingTo("100.00");
    assertThat(record.score()).isEqualByComparingTo("98.00");
    assertThat(record.originalPassScore()).isNull();
    assertThat(record.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(record.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(record.displayScore()).isEqualByComparingTo("196.00");
    assertThat(record.displayPassed()).isTrue();
    assertThat(record.displayResultCode()).isEqualTo("PASSED");
    assertThat(record.referenceScore200()).isEqualByComparingTo("196.00");
    assertThat(record.repairStatus()).isEqualTo("NOT_NEEDED");

    insertRecord("legacy-82", "100.00", "82.00", true, null, null);
    InspectionRecordResponse historicalPassed = service.record(boss(), "legacy-82");
    assertThat(historicalPassed.score()).isEqualByComparingTo("82.00");
    assertThat(historicalPassed.displayScore()).isEqualByComparingTo("164.00");
    assertThat(historicalPassed.displayPassed()).isFalse();
    assertThat(historicalPassed.displayResultCode()).isEqualTo("FAILED");
    assertThat(historicalPassed.referenceScore200()).isEqualByComparingTo("164.00");
  }

  @Test
  void exposesV41ScaleMigrationAuditAndProtectsMigratedHistory() {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand,
          full_score, score, passed, deductions_json, redlines_json, photos_json, result_code
        ) values ('scale-audited', 1, 's1', '2026-05-21', '督导', 'Tea',
                  200, 164, 0, '[{"item":"counter","deduct":8}]', '[]', '[]', 'FAILED')
        """);
    jdbcTemplate.update("""
        insert into inspection_score_scale_migration_audit(
          tenant_id, inspection_record_id, migration_key,
          original_full_score, original_pass_score, original_score,
          original_material_score, original_hygiene_score, original_service_score,
          original_passed, original_result_code,
          converted_full_score, converted_pass_score, converted_score,
          converted_material_score, converted_hygiene_score, converted_service_score,
          converted_passed, converted_result_code
        ) values (1, 'scale-audited', 'V41_100_TO_200',
                  100, null, 82, 20, null, 62, 1, 'PASSED',
                  200, 180, 164, 40, null, 124, 0, 'FAILED')
        """);

    InspectionRecordResponse record = service.record(boss(), "scale-audited");

    assertThat(record.fullScore()).isEqualByComparingTo("100.00");
    assertThat(record.score()).isEqualByComparingTo("82.00");
    assertThat(record.originalPassScore()).isNull();
    assertThat(record.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(record.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(record.displayScore()).isEqualByComparingTo("164.00");
    assertThat(record.displayPassed()).isFalse();
    assertThat(record.displayResultCode()).isEqualTo("FAILED");
    assertThat(record.displayMaterialScore()).isEqualByComparingTo("40.00");
    assertThat(record.displayServiceScore()).isEqualByComparingTo("124.00");
    assertThat(record.scoreScaleMigrationAuditId()).isNotNull();
    assertThat(record.scoreScaleMigrationStatus()).isEqualTo("SCORE_SCALE_MIGRATED");
    assertThat(record.repairStatus()).isEqualTo("SCORE_SCALE_MIGRATED");
    assertThat(record.scoreDetailStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(record.scoreScaleMigrationReason()).contains("100分制", "200分制");
    assertThat(record.migratedScore()).isEqualByComparingTo("164.00");

    assertThatThrownBy(() -> service.save(
        boss(), "scale-audited", request("s1", "2026-05-22", "90.00", true)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertImmutableConflict((BusinessException) error));
    assertThatThrownBy(() -> service.delete(boss(), "scale-audited"))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertImmutableConflict((BusinessException) error));
  }

  @Test
  void neverDisplaysNinetyEightOfTwoHundredAsPassed() {
    insertRecord("wrong-98", "200.00", "98.00", true, "PASSED", "2025.11.06");

    InspectionRecordResponse record = service.record(boss(), "wrong-98");

    assertThat(record.passed()).isTrue();
    assertThat(record.displayPassScore()).isEqualByComparingTo("180.00");
    assertThat(record.displayScore()).isEqualByComparingTo("98.00");
    assertThat(record.displayPassed()).isFalse();
    assertThat(record.displayResultCode()).isEqualTo("FAILED");
    assertThat(service.records(boss(), null, null, null, null, true)).isEmpty();
    assertThat(service.records(boss(), null, null, null, null, false))
        .extracting(InspectionRecordResponse::id).containsExactly("wrong-98");
  }

  @Test
  void exposesOriginalAndRecalculatedResultsWithoutOverwritingRawRecord() {
    insertRecord("repaired-98", "200.00", "98.00", true, "PASSED", "2025.11.06");
    jdbcTemplate.update("""
        update inspection_record
        set material_score = 20, hygiene_score = 30, service_score = 48
        where id = 'repaired-98'
        """);
    jdbcTemplate.update("""
        insert into inspection_result_repair_audit(
          tenant_id, inspection_record_id,
          original_standard_version, original_full_score, original_pass_score,
          original_score, original_material_score, original_hygiene_score, original_service_score,
          original_result_code, original_passed,
          repaired_standard_version_id, repaired_standard_version,
          repaired_full_score, repaired_pass_score, repaired_score,
          repaired_material_score, repaired_hygiene_score, repaired_service_score,
          repaired_result_code, repaired_passed,
          repair_status, repair_reason, snapshot_item_count, expected_item_count, repaired_by
        ) values (1, 'repaired-98', '2025.11.06', 200, 180, 98, 20, 30, 48, 'PASSED', 1,
                  40, '2025.11.06-R1', 200, 180, 196, 37, 63, 96, 'PASSED', 1,
                  'RECALCULATED', '按完整条款快照重新计算', 105, 105, 9)
        """);

    InspectionRecordResponse record = service.record(boss(), "repaired-98");

    assertThat(record.score()).isEqualByComparingTo("98.00");
    assertThat(record.standardVersion()).isEqualTo("2025.11.06");
    assertThat(record.originalStandardVersion()).isEqualTo("2025.11.06");
    assertThat(record.repaired()).isTrue();
    assertThat(record.repairStatus()).isEqualTo("REPAIRED");
    assertThat(record.repairedScore()).isEqualByComparingTo("196.00");
    assertThat(record.displayScore()).isEqualByComparingTo("196.00");
    assertThat(record.materialScore()).isEqualByComparingTo("20.00");
    assertThat(record.hygieneScore()).isEqualByComparingTo("30.00");
    assertThat(record.serviceScore()).isEqualByComparingTo("48.00");
    assertThat(record.displayMaterialScore()).isEqualByComparingTo("37.00");
    assertThat(record.displayHygieneScore()).isEqualByComparingTo("63.00");
    assertThat(record.displayServiceScore()).isEqualByComparingTo("96.00");
    assertThat(record.repairedMaterialScore()).isEqualByComparingTo("37.00");
    assertThat(record.repairedHygieneScore()).isEqualByComparingTo("63.00");
    assertThat(record.repairedServiceScore()).isEqualByComparingTo("96.00");
    assertThat(record.displayFullScore()).isEqualByComparingTo("200.00");
    assertThat(record.displayPassed()).isTrue();
    assertThat(record.repairReason()).isEqualTo("按完整条款快照重新计算");
    assertThat(record.repairAuditId()).isNotNull();
    assertThat(record.repairedBy()).isEqualTo(9L);
  }

  @Test
  void incompleteSnapshotIsPendingManualReviewInsteadOfGuessed() {
    insertRecord("manual-review", "200.00", "181.00", true, "PASSED", "2025.11.06");
    jdbcTemplate.update("""
        insert into inspection_result_repair_audit(
          tenant_id, inspection_record_id,
          original_standard_version, original_full_score, original_pass_score,
          original_score, original_result_code, original_passed, repaired_standard_version_id,
          repair_status, repair_reason, snapshot_item_count, expected_item_count
        ) values (1, 'manual-review', '2025.11.06', 200, 180, 181, 'PASSED', 1, 40,
                  'MANUAL_REVIEW', '条款快照不完整，禁止自动换算', 72, 105)
        """);

    InspectionRecordResponse record = service.record(boss(), "manual-review");

    assertThat(record.score()).isEqualByComparingTo("181.00");
    assertThat(record.repairStatus()).isEqualTo("MANUAL_REVIEW");
    assertThat(record.displayResultCode()).isEqualTo("MANUAL_REVIEW");
    assertThat(record.displayPassed()).isFalse();
    assertThat(record.repaired()).isFalse();
    assertThat(record.repairedScore()).isNull();
    assertThat(record.displayMaterialScore()).isNull();
    assertThat(record.displayHygieneScore()).isNull();
    assertThat(record.displayServiceScore()).isNull();
    assertThat(record.repairedMaterialScore()).isNull();
    assertThat(record.repairedHygieneScore()).isNull();
    assertThat(record.repairedServiceScore()).isNull();
    assertThat(record.repairReason()).contains("快照不完整");
    assertThat(service.records(boss(), null, null, null, null, true)).isEmpty();
    assertThat(service.records(boss(), null, null, null, null, false)).isEmpty();
  }

  @Test
  void concurrentRepairAuditInsertCreatesExactlyOneRow() throws Exception {
    insertRecord("repair-race", "200.00", "98.00", true, "PASSED", "2025.11.06");
    InspectionResultRepairWrite write = repairWriteForRace();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Boolean> insert = () -> {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return repository.insertRepairAudit(1L, "repair-race", write);
      };
      Future<Boolean> first = executor.submit(insert);
      Future<Boolean> second = executor.submit(insert);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from inspection_result_repair_audit where inspection_record_id = 'repair-race'",
          Integer.class
      )).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void repairAuditPreservesAbsentOriginalScoreEvidence() {
    insertRecord("repair-null-evidence", "200.00", "196.00", true, "PASSED", "2025.11.06");
    InspectionResultRepairWrite repair = new InspectionResultRepairWrite(
        38L, "历史标准", null, null, null, null, null, null, null, null,
        40L, "2025.11.06-R1", bd("200"), bd("180"), bd("196"),
        bd("37"), bd("59"), bd("100"), "PASSED", true,
        "RECALCULATED", "完整快照确定性重算", 105, 105, 1L
    );

    assertThat(repository.insertRepairAudit(1L, "repair-null-evidence", repair)).isTrue();
    Map<String, Object> row = jdbcTemplate.queryForMap("""
        select original_full_score, original_score, original_passed
        from inspection_result_repair_audit
        where tenant_id = 1 and inspection_record_id = 'repair-null-evidence'
        """);

    assertThat(row.get("ORIGINAL_FULL_SCORE")).isNull();
    assertThat(row.get("ORIGINAL_SCORE")).isNull();
    assertThat(row.get("ORIGINAL_PASSED")).isNull();
    assertThat(repository.record(1L, "repair-null-evidence")).isPresent();
  }

  private InspectionResultRepairWrite repairWriteForRace() {
    return new InspectionResultRepairWrite(
        38L, "2025.11.06", bd("200"), bd("180"), bd("98"),
        bd("20"), bd("30"), bd("48"), "PASSED", true,
        40L, "2025.11.06-R1", bd("200"), bd("180"), bd("196"),
        bd("37"), bd("63"), bd("96"), "PASSED", true,
        "RECALCULATED", "并发幂等测试", 105, 105, 1L
    );
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }

  private void assertImmutableConflict(BusinessException error) {
    assertThat(error.getCode()).isEqualTo("INSPECTION_REPAIRED_RECORD_IMMUTABLE");
    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(error.getMessage()).contains("禁止", "原始分数", "条款快照", "现场证据");
  }

  private void insertRecord(
      String id,
      String fullScore,
      String score,
      boolean passed,
      String resultCode,
      String standardVersion
  ) {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand,
          full_score, score, passed, deductions_json, redlines_json,
          standard_version, result_code
        ) values (?, 1, 's1', '2026-05-21', '督导', 'Tea', ?, ?, ?, '[]', '[]', ?, ?)
        """, id, new BigDecimal(fullScore), new BigDecimal(score), passed ? 1 : 0,
        standardVersion, resultCode);
  }

  private void createRepairAuditTable() {
    jdbcTemplate.execute("""
        create table inspection_result_repair_audit (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          inspection_record_id varchar(120) not null,
          original_standard_version_id bigint null,
          original_standard_version varchar(64) null,
          original_full_score decimal(8,2) null,
          original_pass_score decimal(8,2) null,
          original_score decimal(8,2) null,
          original_material_score decimal(8,2) null,
          original_hygiene_score decimal(8,2) null,
          original_service_score decimal(8,2) null,
          original_result_code varchar(32) null,
          original_passed tinyint(1) null,
          repaired_standard_version_id bigint not null,
          repaired_standard_version varchar(64) null,
          repaired_full_score decimal(8,2) null,
          repaired_pass_score decimal(8,2) null,
          repaired_score decimal(8,2) null,
          repaired_material_score decimal(8,2) null,
          repaired_hygiene_score decimal(8,2) null,
          repaired_service_score decimal(8,2) null,
          repaired_result_code varchar(32) null,
          repaired_passed tinyint(1) null,
          repair_status varchar(32) not null,
          repair_reason varchar(500) not null,
          snapshot_item_count int not null default 0,
          expected_item_count int not null default 0,
          repaired_by bigint null,
          repaired_at timestamp not null default current_timestamp,
          unique(tenant_id, inspection_record_id, repaired_standard_version_id)
        )
        """);
  }

  private void createScoreScaleMigrationAuditTable() {
    jdbcTemplate.execute("""
        create table inspection_score_scale_migration_audit (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          inspection_record_id varchar(120) not null,
          migration_key varchar(64) not null,
          original_full_score decimal(8,2) null,
          original_pass_score decimal(8,2) null,
          original_score decimal(8,2) null,
          original_material_score decimal(8,2) null,
          original_hygiene_score decimal(8,2) null,
          original_service_score decimal(8,2) null,
          original_passed tinyint not null,
          original_result_code varchar(32) null,
          converted_full_score decimal(8,2) not null,
          converted_pass_score decimal(8,2) not null,
          converted_score decimal(8,2) not null,
          converted_material_score decimal(8,2) null,
          converted_hygiene_score decimal(8,2) null,
          converted_service_score decimal(8,2) null,
          converted_passed tinyint not null,
          converted_result_code varchar(32) not null,
          migrated_at timestamp not null default current_timestamp,
          unique(tenant_id, inspection_record_id, migration_key)
        )
        """);
  }

  private InspectionDetectionBindingRequest detectionBindingRequest() {
    return new InspectionDetectionBindingRequest(
        "Inspector B",
        "Tea",
        new BigDecimal("100.00"),
        List.of(Map.of(
            "image_id", "img-1",
            "filename", "floor.jpg",
            "passed", true,
            "detection_count", 0,
            "detection_summary", ""
        )),
        "AI detection result"
    );
  }

  private InspectionRecordRequest request(String storeId, String date, String score, boolean passed) {
    return new InspectionRecordRequest(
        storeId,
        date,
        "Inspector A",
        "Tea",
        new BigDecimal("200.00"),
        new BigDecimal(score).multiply(new BigDecimal("2.00")),
        passed,
        "[{\"item\":\"counter\",\"deduct\":8}]",
        passed ? "[]" : "[{\"item\":\"food safety\"}]",
        "[]",
        "follow up"
    );
  }

  private AuthUser boss() {
    return user("BOSS", null);
  }

  private AuthUser supervisor() {
    return user("SUPERVISOR", null);
  }

  private AuthUser storeManager() {
    return user("STORE_MANAGER", "s1");
  }

  private AuthUser user(String role, String storeId) {
    return new AuthUser(1L, 1L, "default", role.toLowerCase(), "", role, role, storeId, true);
  }
}
