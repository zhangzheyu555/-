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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class InspectionRecordServiceTest {
  private JdbcTemplate jdbcTemplate;
  private InspectionService service;

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
          full_score decimal(8,2) not null default 100,
          score decimal(8,2) not null default 100,
          passed tinyint(1) not null default 1,
          deductions_json longtext null,
          redlines_json longtext null,
          photos_json longtext null,
          note text null,
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
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea'), (2, 2, 'Other')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values
          ('s1', 1, 1, '001', 'One'),
          ('s2', 1, 1, '002', 'Two'),
          ('other', 2, 2, '099', 'Other')
        """);
    InspectionRecordRepository repository = new InspectionRecordRepository(
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
    assertThat(detail.score()).isEqualByComparingTo("92.00");
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

    assertThat(updated.score()).isEqualByComparingTo("98.00");
    assertThat(service.records(boss(), "2026-05-01", "2026-05-31", null, null, null)).isEmpty();
    Integer auditCount = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where target_type = 'inspection_record' and target_id = 'insp-1'",
        Integer.class
    );
    assertThat(auditCount).isEqualTo(3);
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
    assertThat(bound.score()).isEqualByComparingTo("98.00");
    assertThat(bound.passed()).isFalse();
    assertThat(bound.deductionsJson()).contains("img-1", "floor litter");
    assertThat(bound.redlinesJson()).contains("not clean", "paper");
    assertThat(bound.photosJson()).contains("floor.jpg", "data:image/png;base64,abc");

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
        new BigDecimal("100.00"),
        new BigDecimal(score),
        passed,
        "[{\"item\":\"counter\",\"deduct\":8}]",
        passed ? "[]" : "[{\"item\":\"food safety\"}]",
        "[{\"src\":\"https://example.test/photo.jpg\"}]",
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
