package com.storeprofit.system.inspection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InspectionRecordRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public InspectionRecordRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<InspectionRecordResponse> records(
      long tenantId,
      String dateFrom,
      String dateTo,
      Long brandId,
      String storeId,
      Boolean passed
  ) {
    return records(tenantId, dateFrom, dateTo, brandId, storeId, passed, null);
  }

  public List<InspectionRecordResponse> records(
      long tenantId,
      String dateFrom,
      String dateTo,
      Long brandId,
      String storeId,
      Boolean passed,
      Collection<String> allowedStoreIds
  ) {
    StringBuilder sql = new StringBuilder("""
        select ir.id, ir.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ir.inspection_date, ir.inspector,
               ir.brand, ir.full_score, ir.score, ir.passed, ir.deductions_json,
               ir.redlines_json, ir.photos_json, ir.note, ir.standard_version_id,
               ir.standard_version, ir.material_score, ir.hygiene_score, ir.service_score,
               ir.result_code,
               repair.id as repair_id,
               repair.original_standard_version_id as repair_original_standard_version_id,
               repair.original_standard_version as repair_original_standard_version,
               repair.original_full_score as repair_original_full_score,
               repair.original_pass_score as repair_original_pass_score,
               repair.original_score as repair_original_score,
               repair.original_material_score as repair_original_material_score,
               repair.original_hygiene_score as repair_original_hygiene_score,
               repair.original_service_score as repair_original_service_score,
               repair.original_result_code as repair_original_result_code,
               repair.original_passed as repair_original_passed,
               repair.repaired_standard_version_id as repair_repaired_standard_version_id,
               repair.repaired_standard_version as repair_repaired_standard_version,
               repair.repaired_full_score as repair_repaired_full_score,
               repair.repaired_pass_score as repair_repaired_pass_score,
               repair.repaired_score as repair_repaired_score,
               repair.repaired_material_score as repair_repaired_material_score,
               repair.repaired_hygiene_score as repair_repaired_hygiene_score,
               repair.repaired_service_score as repair_repaired_service_score,
               repair.repaired_result_code as repair_repaired_result_code,
               repair.repaired_passed as repair_repaired_passed,
               repair.repair_status, repair.repair_reason,
               repair.snapshot_item_count, repair.expected_item_count,
               repair.repaired_by, repair.repaired_at,
               scale_audit.id as scale_audit_id,
               scale_audit.migration_key as scale_migration_key,
               scale_audit.original_full_score as scale_original_full_score,
               scale_audit.original_pass_score as scale_original_pass_score,
               scale_audit.original_score as scale_original_score,
               scale_audit.original_material_score as scale_original_material_score,
               scale_audit.original_hygiene_score as scale_original_hygiene_score,
               scale_audit.original_service_score as scale_original_service_score,
               scale_audit.original_passed as scale_original_passed,
               scale_audit.original_result_code as scale_original_result_code,
               scale_audit.converted_full_score as scale_converted_full_score,
               scale_audit.converted_pass_score as scale_converted_pass_score,
               scale_audit.converted_score as scale_converted_score,
               scale_audit.converted_material_score as scale_converted_material_score,
               scale_audit.converted_hygiene_score as scale_converted_hygiene_score,
               scale_audit.converted_service_score as scale_converted_service_score,
               scale_audit.converted_passed as scale_converted_passed,
               scale_audit.converted_result_code as scale_converted_result_code,
               scale_audit.migrated_at as scale_migrated_at
        from inspection_record ir
        join store_branch s on s.id = ir.store_id and s.tenant_id = ir.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        left join inspection_result_repair_audit repair on repair.id = (
          select max(latest_repair.id)
          from inspection_result_repair_audit latest_repair
          where latest_repair.tenant_id = ir.tenant_id
            and latest_repair.inspection_record_id = ir.id
        )
        left join inspection_score_scale_migration_audit scale_audit
          on scale_audit.tenant_id = ir.tenant_id
         and scale_audit.inspection_record_id = ir.id
         and scale_audit.migration_key = 'V41_100_TO_200'
        where ir.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (dateFrom != null && !dateFrom.isBlank()) {
      sql.append(" and ir.inspection_date >= :dateFrom");
      params.addValue("dateFrom", dateFrom);
    }
    if (dateTo != null && !dateTo.isBlank()) {
      sql.append(" and ir.inspection_date <= :dateTo");
      params.addValue("dateTo", dateTo);
    }
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and ir.store_id = :storeId");
      params.addValue("storeId", storeId);
    } else if (allowedStoreIds != null) {
      List<String> normalizedStoreIds = allowedStoreIds.stream()
          .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
          .map(String::trim)
          .distinct()
          .toList();
      if (normalizedStoreIds.isEmpty()) {
        sql.append(" and 1 = 0");
      } else {
        sql.append(" and ir.store_id in (:allowedStoreIds)");
        params.addValue("allowedStoreIds", normalizedStoreIds);
      }
    }
    sql.append(" order by ir.inspection_date asc, s.code, ir.id");
    List<InspectionRecordResponse> rows = namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
    if (passed == null) {
      return rows;
    }
    return rows.stream()
        .filter(row -> !"MANUAL_REVIEW".equals(row.displayResultCode()))
        .filter(row -> row.displayPassed() == passed)
        .toList();
  }

  public Optional<InspectionRecordResponse> record(long tenantId, String id) {
    List<InspectionRecordResponse> rows = namedJdbcTemplate.query("""
        select ir.id, ir.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ir.inspection_date, ir.inspector,
               ir.brand, ir.full_score, ir.score, ir.passed, ir.deductions_json,
               ir.redlines_json, ir.photos_json, ir.note, ir.standard_version_id,
               ir.standard_version, ir.material_score, ir.hygiene_score, ir.service_score,
               ir.result_code,
               repair.id as repair_id,
               repair.original_standard_version_id as repair_original_standard_version_id,
               repair.original_standard_version as repair_original_standard_version,
               repair.original_full_score as repair_original_full_score,
               repair.original_pass_score as repair_original_pass_score,
               repair.original_score as repair_original_score,
               repair.original_material_score as repair_original_material_score,
               repair.original_hygiene_score as repair_original_hygiene_score,
               repair.original_service_score as repair_original_service_score,
               repair.original_result_code as repair_original_result_code,
               repair.original_passed as repair_original_passed,
               repair.repaired_standard_version_id as repair_repaired_standard_version_id,
               repair.repaired_standard_version as repair_repaired_standard_version,
               repair.repaired_full_score as repair_repaired_full_score,
               repair.repaired_pass_score as repair_repaired_pass_score,
               repair.repaired_score as repair_repaired_score,
               repair.repaired_material_score as repair_repaired_material_score,
               repair.repaired_hygiene_score as repair_repaired_hygiene_score,
               repair.repaired_service_score as repair_repaired_service_score,
               repair.repaired_result_code as repair_repaired_result_code,
               repair.repaired_passed as repair_repaired_passed,
               repair.repair_status, repair.repair_reason,
               repair.snapshot_item_count, repair.expected_item_count,
               repair.repaired_by, repair.repaired_at,
               scale_audit.id as scale_audit_id,
               scale_audit.migration_key as scale_migration_key,
               scale_audit.original_full_score as scale_original_full_score,
               scale_audit.original_pass_score as scale_original_pass_score,
               scale_audit.original_score as scale_original_score,
               scale_audit.original_material_score as scale_original_material_score,
               scale_audit.original_hygiene_score as scale_original_hygiene_score,
               scale_audit.original_service_score as scale_original_service_score,
               scale_audit.original_passed as scale_original_passed,
               scale_audit.original_result_code as scale_original_result_code,
               scale_audit.converted_full_score as scale_converted_full_score,
               scale_audit.converted_pass_score as scale_converted_pass_score,
               scale_audit.converted_score as scale_converted_score,
               scale_audit.converted_material_score as scale_converted_material_score,
               scale_audit.converted_hygiene_score as scale_converted_hygiene_score,
               scale_audit.converted_service_score as scale_converted_service_score,
               scale_audit.converted_passed as scale_converted_passed,
               scale_audit.converted_result_code as scale_converted_result_code,
               scale_audit.migrated_at as scale_migrated_at
        from inspection_record ir
        join store_branch s on s.id = ir.store_id and s.tenant_id = ir.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        left join inspection_result_repair_audit repair on repair.id = (
          select max(latest_repair.id)
          from inspection_result_repair_audit latest_repair
          where latest_repair.tenant_id = ir.tenant_id
            and latest_repair.inspection_record_id = ir.id
        )
        left join inspection_score_scale_migration_audit scale_audit
          on scale_audit.tenant_id = ir.tenant_id
         and scale_audit.inspection_record_id = ir.id
         and scale_audit.migration_key = 'V41_100_TO_200'
        where ir.tenant_id = :tenantId and ir.id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRecord
    );
    return rows.stream().findFirst().map(row -> row.withItemResults(snapshotItems(tenantId, id)));
  }

  /**
   * Reads the un-normalised historical score facts used to decide whether a record can be repaired
   * deterministically.  Do not derive defaults here: a null value is evidence that must either be
   * recovered from a complete snapshot or reported to the operator.
   */
  public Optional<ScoreEvidence> scoreEvidence(long tenantId, String id) {
    List<ScoreEvidence> rows = jdbcTemplate.query("""
        select ir.id,
               ir.full_score,
               ir.pass_score,
               ir.score,
               ir.material_score,
               ir.hygiene_score,
               ir.service_score,
               ir.passed,
               ir.result_code,
               ir.standard_version_id,
               ir.standard_version,
               (select count(*)
                  from inspection_record_standard_snapshot snapshot
                 where snapshot.tenant_id = ir.tenant_id
                   and snapshot.inspection_record_id = ir.id) as snapshot_count,
               (select count(snapshot.standard_id)
                  from inspection_record_standard_snapshot snapshot
                 where snapshot.tenant_id = ir.tenant_id
                   and snapshot.inspection_record_id = ir.id) as snapshot_standard_id_count,
               (select count(distinct item.standard_version_id)
                  from inspection_record_standard_snapshot snapshot
                  join inspection_standard_item item on item.id = snapshot.standard_id
                 where snapshot.tenant_id = ir.tenant_id
                   and snapshot.inspection_record_id = ir.id) as snapshot_version_count,
               (select min(item.standard_version_id)
                  from inspection_record_standard_snapshot snapshot
                  join inspection_standard_item item on item.id = snapshot.standard_id
                 where snapshot.tenant_id = ir.tenant_id
                   and snapshot.inspection_record_id = ir.id) as snapshot_standard_version_id
          from inspection_record ir
         where ir.tenant_id = ? and ir.id = ?
        """, (rs, rowNum) -> new ScoreEvidence(
            rs.getString("id"),
            nullableAmount(rs.getBigDecimal("full_score")),
            nullableAmount(rs.getBigDecimal("pass_score")),
            nullableAmount(rs.getBigDecimal("score")),
            nullableAmount(rs.getBigDecimal("material_score")),
            nullableAmount(rs.getBigDecimal("hygiene_score")),
            nullableAmount(rs.getBigDecimal("service_score")),
            getBooleanOrNull(rs, "passed"),
            rs.getString("result_code"),
            getLongOrNull(rs, "standard_version_id"),
            rs.getString("standard_version"),
            rs.getInt("snapshot_count"),
            rs.getInt("snapshot_standard_id_count"),
            rs.getInt("snapshot_version_count"),
            getLongOrNull(rs, "snapshot_standard_version_id")
        ), tenantId, id);
    return rows.stream().findFirst();
  }

  /** Serialize detection decisions for one record inside the caller's transaction. */
  public void lockRecord(long tenantId, String id) {
    jdbcTemplate.queryForObject(
        "select id from inspection_record where tenant_id = ? and id = ? for update",
        String.class,
        tenantId,
        id
    );
  }

  public List<InspectionItemResultResponse> snapshotItems(long tenantId, String inspectionRecordId) {
    return jdbcTemplate.query("""
        select id, standard_id, dimension, standard_code, standard_title,
               standard_description, check_method, suggested_score, actual_score,
               actual_deduction_score, risk_level, red_line, problem_description,
               photo_attachment_ids_json, responsible_person, rectification_deadline,
               rectification_status, review_result, before_photo_attachment_ids_json,
               after_photo_attachment_ids_json, sort_order
        from inspection_record_standard_snapshot
        where tenant_id = ? and inspection_record_id = ?
        order by sort_order, id
        """, this::mapSnapshotItem, tenantId, inspectionRecordId);
  }

  public boolean insertRepairAudit(
      long tenantId,
      String inspectionRecordId,
      InspectionResultRepairWrite repair
  ) {
    try {
      jdbcTemplate.update("""
          insert into inspection_result_repair_audit(
            tenant_id, inspection_record_id,
            original_standard_version_id, original_standard_version,
            original_full_score, original_pass_score, original_score,
            original_material_score, original_hygiene_score, original_service_score,
            original_result_code, original_passed,
            repaired_standard_version_id, repaired_standard_version,
            repaired_full_score, repaired_pass_score, repaired_score,
            repaired_material_score, repaired_hygiene_score, repaired_service_score,
            repaired_result_code, repaired_passed,
            repair_status, repair_reason, snapshot_item_count, expected_item_count,
            repaired_by, repaired_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          tenantId,
          inspectionRecordId,
          repair.originalStandardVersionId(),
          blankToNull(repair.originalStandardVersion()),
          amount(repair.originalFullScore()),
          nullableAmount(repair.originalPassScore()),
          amount(repair.originalScore()),
          nullableAmount(repair.originalMaterialScore()),
          nullableAmount(repair.originalHygieneScore()),
          nullableAmount(repair.originalServiceScore()),
          blankToNull(repair.originalResultCode()),
          repair.originalPassed() ? 1 : 0,
          repair.repairedStandardVersionId(),
          blankToNull(repair.repairedStandardVersion()),
          nullableAmount(repair.repairedFullScore()),
          nullableAmount(repair.repairedPassScore()),
          nullableAmount(repair.repairedScore()),
          nullableAmount(repair.repairedMaterialScore()),
          nullableAmount(repair.repairedHygieneScore()),
          nullableAmount(repair.repairedServiceScore()),
          blankToNull(repair.repairedResultCode()),
          repair.repairedPassed() == null ? null : repair.repairedPassed() ? 1 : 0,
          repair.repairStatus(),
          repair.repairReason(),
          repair.snapshotItemCount(),
          repair.expectedItemCount(),
          repair.repairedBy()
      );
      return true;
    } catch (DuplicateKeyException duplicate) {
      return false;
    }
  }

  public boolean hasRepairAudit(long tenantId, String inspectionRecordId) {
    Integer repairCount = jdbcTemplate.queryForObject(
        "select count(*) from inspection_result_repair_audit where tenant_id = ? and inspection_record_id = ?",
        Integer.class,
        tenantId,
        inspectionRecordId
    );
    if (repairCount != null && repairCount > 0) {
      return true;
    }
    Integer scaleMigrationCount = jdbcTemplate.queryForObject(
        "select count(*) from inspection_score_scale_migration_audit "
            + "where tenant_id = ? and inspection_record_id = ?",
        Integer.class,
        tenantId,
        inspectionRecordId
    );
    return scaleMigrationCount != null && scaleMigrationCount > 0;
  }

  public void upsert(long tenantId, String id, InspectionRecordRequest request) {
    if (recordExists(tenantId, id)) {
      update(tenantId, id, request);
      return;
    }
    insert(tenantId, id, request);
  }

  public int delete(long tenantId, String id) {
    jdbcTemplate.update(
        "delete from inspection_record_standard_snapshot where tenant_id = ? and inspection_record_id = ?",
        tenantId,
        id
    );
    return jdbcTemplate.update("delete from inspection_record where tenant_id = ? and id = ?", tenantId, id);
  }

  public void replaceStandardSnapshots(
      long tenantId,
      String inspectionRecordId,
      List<InspectionStandardSnapshot> snapshots
  ) {
    jdbcTemplate.update(
        "delete from inspection_record_standard_snapshot where tenant_id = ? and inspection_record_id = ?",
        tenantId,
        inspectionRecordId
    );
    int fallbackSortOrder = 0;
    for (InspectionStandardSnapshot snapshot : snapshots) {
      jdbcTemplate.update("""
          insert into inspection_record_standard_snapshot(
            tenant_id, inspection_record_id, standard_id, standard_version, dimension,
            standard_title, standard_description, suggested_score, actual_deduction_score,
            red_line, problem_description, sort_order, standard_code, check_method,
            actual_score, risk_level, photo_attachment_ids_json, responsible_person,
            rectification_deadline, rectification_status, review_result,
            before_photo_attachment_ids_json, after_photo_attachment_ids_json, created_at
          )
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          tenantId,
          inspectionRecordId,
          snapshot.standardId(),
          blankToNull(snapshot.standardVersion()),
          blankToNull(snapshot.dimension()),
          blankToNull(snapshot.standardTitle()),
          blankToNull(snapshot.standardDescription()),
          amount(snapshot.suggestedScore()),
          amount(snapshot.actualDeductionScore()),
          snapshot.redLine() ? 1 : 0,
          blankToNull(snapshot.problemDescription()),
          snapshot.sortOrder() > 0 ? snapshot.sortOrder() : ++fallbackSortOrder,
          blankToNull(snapshot.standardCode()),
          blankToNull(snapshot.checkMethod()),
          amount(snapshot.actualScore()),
          blankToNull(snapshot.riskLevel()),
          idsJson(snapshot.photoAttachmentIds()),
          blankToNull(snapshot.responsiblePerson()),
          snapshot.rectificationDeadline(),
          blankToNull(snapshot.rectificationStatus()),
          blankToNull(snapshot.reviewResult()),
          idsJson(snapshot.beforePhotoAttachmentIds()),
          idsJson(snapshot.afterPhotoAttachmentIds())
      );
    }
  }

  public boolean storeExists(long tenantId, String storeId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from store_branch where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        storeId
    );
    return count != null && count > 0;
  }

  public void logAction(
      long tenantId,
      long operatorId,
      String operatorName,
      String action,
      String id,
      String storeId,
      String inspectionDate,
      String reason
  ) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        )
        values (?, ?, ?, ?, 'inspection_record', ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        operatorId,
        operatorName,
        action,
        id,
        storeId,
        inspectionDate == null || inspectionDate.length() < 7 ? null : inspectionDate.substring(0, 7),
        reason
    );
  }

  private boolean recordExists(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from inspection_record where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        id
    );
    return count != null && count > 0;
  }

  private void insert(long tenantId, String id, InspectionRecordRequest request) {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand, full_score,
          score, passed, deductions_json, redlines_json, photos_json, note,
          standard_version_id, standard_version, material_score, hygiene_score,
          service_score, result_code, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        id,
        tenantId,
        request.storeId(),
        request.inspectionDate(),
        blankToNull(request.inspector()),
        blankToNull(request.brand()),
        amount(request.fullScore()),
        amount(request.score()),
        request.passed() == null || request.passed() ? 1 : 0,
        blankToNull(request.deductionsJson()),
        blankToNull(request.redlinesJson()),
        blankToNull(request.photosJson()),
        blankToNull(request.note()),
        request.standardVersionId(),
        blankToNull(request.standardVersion()),
        nullableAmount(request.materialScore()),
        nullableAmount(request.hygieneScore()),
        nullableAmount(request.serviceScore()),
        blankToNull(request.resultCode())
    );
  }

  private void update(long tenantId, String id, InspectionRecordRequest request) {
    jdbcTemplate.update("""
        update inspection_record set
          store_id = ?,
          inspection_date = ?,
          inspector = ?,
          brand = ?,
          full_score = ?,
          score = ?,
          passed = ?,
          deductions_json = ?,
          redlines_json = ?,
          photos_json = ?,
          note = ?,
          standard_version_id = ?,
          standard_version = ?,
          material_score = ?,
          hygiene_score = ?,
          service_score = ?,
          result_code = ?,
          updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        request.storeId(),
        request.inspectionDate(),
        blankToNull(request.inspector()),
        blankToNull(request.brand()),
        amount(request.fullScore()),
        amount(request.score()),
        request.passed() == null || request.passed() ? 1 : 0,
        blankToNull(request.deductionsJson()),
        blankToNull(request.redlinesJson()),
        blankToNull(request.photosJson()),
        blankToNull(request.note()),
        request.standardVersionId(),
        blankToNull(request.standardVersion()),
        nullableAmount(request.materialScore()),
        nullableAmount(request.hygieneScore()),
        nullableAmount(request.serviceScore()),
        blankToNull(request.resultCode()),
        tenantId,
        id
    );
  }

  private InspectionRecordResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
    boolean passed = rs.getInt("passed") != 0;
    String resultCode = rs.getString("result_code");
    if (resultCode == null || resultCode.isBlank()) {
      resultCode = passed ? "PASSED" : "FAILED";
    }
    InspectionResultRepairAudit repair = mapRepair(rs);
    InspectionScoreScaleMigrationAudit scaleMigration = mapScaleMigration(rs);
    BigDecimal originalFullScore = scaleMigration == null
        ? rs.getBigDecimal("full_score") : scaleMigration.originalFullScore();
    BigDecimal originalScore = scaleMigration == null
        ? rs.getBigDecimal("score") : scaleMigration.originalScore();
    BigDecimal originalMaterialScore = scaleMigration == null
        ? rs.getBigDecimal("material_score") : scaleMigration.originalMaterialScore();
    BigDecimal originalHygieneScore = scaleMigration == null
        ? rs.getBigDecimal("hygiene_score") : scaleMigration.originalHygieneScore();
    BigDecimal originalServiceScore = scaleMigration == null
        ? rs.getBigDecimal("service_score") : scaleMigration.originalServiceScore();
    boolean originalPassed = scaleMigration == null ? passed : scaleMigration.originalPassed();
    String originalResultCode = scaleMigration == null
        ? resultCode : scaleMigration.originalResultCode();
    if (originalResultCode == null || originalResultCode.isBlank()) {
      originalResultCode = originalPassed ? "PASSED" : "FAILED";
    }
    InspectionResultPresentation presentation = InspectionResultPolicy.present(
        repair == null ? originalFullScore : rs.getBigDecimal("full_score"),
        repair == null ? originalScore : rs.getBigDecimal("score"),
        repair == null ? originalMaterialScore : rs.getBigDecimal("material_score"),
        repair == null ? originalHygieneScore : rs.getBigDecimal("hygiene_score"),
        repair == null ? originalServiceScore : rs.getBigDecimal("service_score"),
        repair == null ? originalPassed : passed,
        repair == null ? originalResultCode : resultCode,
        rs.getString("redlines_json"),
        rs.getString("standard_version"),
        repair
    );
    return new InspectionRecordResponse(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        getLongOrNull(rs, "brand_id"),
        rs.getString("brand_name"),
        rs.getDate("inspection_date").toString(),
        rs.getString("inspector"),
        rs.getString("brand"),
        amount(originalFullScore),
        amount(originalScore),
        originalPassed,
        rs.getString("deductions_json"),
        rs.getString("redlines_json"),
        rs.getString("photos_json"),
        rs.getString("note"),
        getLongOrNull(rs, "standard_version_id"),
        rs.getString("standard_version"),
        nullableAmount(originalMaterialScore),
        nullableAmount(originalHygieneScore),
        nullableAmount(originalServiceScore),
        originalResultCode,
        List.of(),
        presentation,
        scaleMigration
    );
  }

  private InspectionResultRepairAudit mapRepair(ResultSet rs) throws SQLException {
    Long id = getLongOrNull(rs, "repair_id");
    if (id == null) {
      return null;
    }
    java.sql.Timestamp repairedAt = rs.getTimestamp("repaired_at");
    return new InspectionResultRepairAudit(
        id,
        getLongOrNull(rs, "repair_original_standard_version_id"),
        rs.getString("repair_original_standard_version"),
        nullableAmount(rs.getBigDecimal("repair_original_full_score")),
        nullableAmount(rs.getBigDecimal("repair_original_pass_score")),
        nullableAmount(rs.getBigDecimal("repair_original_score")),
        nullableAmount(rs.getBigDecimal("repair_original_material_score")),
        nullableAmount(rs.getBigDecimal("repair_original_hygiene_score")),
        nullableAmount(rs.getBigDecimal("repair_original_service_score")),
        rs.getString("repair_original_result_code"),
        rs.getInt("repair_original_passed") != 0,
        getLongOrNull(rs, "repair_repaired_standard_version_id"),
        rs.getString("repair_repaired_standard_version"),
        nullableAmount(rs.getBigDecimal("repair_repaired_full_score")),
        nullableAmount(rs.getBigDecimal("repair_repaired_pass_score")),
        nullableAmount(rs.getBigDecimal("repair_repaired_score")),
        nullableAmount(rs.getBigDecimal("repair_repaired_material_score")),
        nullableAmount(rs.getBigDecimal("repair_repaired_hygiene_score")),
        nullableAmount(rs.getBigDecimal("repair_repaired_service_score")),
        rs.getString("repair_repaired_result_code"),
        getBooleanOrNull(rs, "repair_repaired_passed"),
        rs.getString("repair_status"),
        rs.getString("repair_reason"),
        rs.getInt("snapshot_item_count"),
        rs.getInt("expected_item_count"),
        getLongOrNull(rs, "repaired_by"),
        repairedAt == null ? null : repairedAt.toLocalDateTime()
    );
  }

  private InspectionScoreScaleMigrationAudit mapScaleMigration(ResultSet rs) throws SQLException {
    Long id = getLongOrNull(rs, "scale_audit_id");
    if (id == null) {
      return null;
    }
    java.sql.Timestamp migratedAt = rs.getTimestamp("scale_migrated_at");
    return new InspectionScoreScaleMigrationAudit(
        id,
        rs.getString("scale_migration_key"),
        nullableAmount(rs.getBigDecimal("scale_original_full_score")),
        nullableAmount(rs.getBigDecimal("scale_original_pass_score")),
        nullableAmount(rs.getBigDecimal("scale_original_score")),
        nullableAmount(rs.getBigDecimal("scale_original_material_score")),
        nullableAmount(rs.getBigDecimal("scale_original_hygiene_score")),
        nullableAmount(rs.getBigDecimal("scale_original_service_score")),
        rs.getInt("scale_original_passed") != 0,
        rs.getString("scale_original_result_code"),
        nullableAmount(rs.getBigDecimal("scale_converted_full_score")),
        nullableAmount(rs.getBigDecimal("scale_converted_pass_score")),
        nullableAmount(rs.getBigDecimal("scale_converted_score")),
        nullableAmount(rs.getBigDecimal("scale_converted_material_score")),
        nullableAmount(rs.getBigDecimal("scale_converted_hygiene_score")),
        nullableAmount(rs.getBigDecimal("scale_converted_service_score")),
        rs.getInt("scale_converted_passed") != 0,
        rs.getString("scale_converted_result_code"),
        migratedAt == null ? null : migratedAt.toLocalDateTime()
    );
  }

  private InspectionItemResultResponse mapSnapshotItem(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Date deadline = rs.getDate("rectification_deadline");
    return new InspectionItemResultResponse(
        rs.getLong("id"),
        getLongOrNull(rs, "standard_id"),
        rs.getString("dimension"),
        rs.getString("standard_code"),
        rs.getString("standard_title"),
        rs.getString("standard_description"),
        rs.getString("check_method"),
        amount(rs.getBigDecimal("suggested_score")),
        amount(rs.getBigDecimal("actual_score")),
        amount(rs.getBigDecimal("actual_deduction_score")),
        rs.getBigDecimal("actual_deduction_score").signum() > 0
            || (rs.getString("problem_description") != null
                && !rs.getString("problem_description").isBlank()),
        rs.getString("risk_level"),
        rs.getInt("red_line") != 0,
        rs.getString("problem_description"),
        parseIds(rs.getString("photo_attachment_ids_json")),
        rs.getString("responsible_person"),
        deadline == null ? null : deadline.toLocalDate(),
        rs.getString("rectification_status"),
        rs.getString("review_result"),
        parseIds(rs.getString("before_photo_attachment_ids_json")),
        parseIds(rs.getString("after_photo_attachment_ids_json")),
        rs.getInt("sort_order")
    );
  }

  private Long getLongOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private Boolean getBooleanOrNull(ResultSet rs, String column) throws SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }

  private BigDecimal nullableAmount(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String idsJson(List<Long> ids) {
    try {
      return OBJECT_MAPPER.writeValueAsString(ids == null ? List.of() : ids);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return "[]";
    }
  }

  private List<Long> parseIds(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return OBJECT_MAPPER.readValue(json, new TypeReference<List<Long>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return List.of();
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record ScoreEvidence(
      String inspectionRecordId,
      BigDecimal fullScore,
      BigDecimal passScore,
      BigDecimal score,
      BigDecimal materialScore,
      BigDecimal hygieneScore,
      BigDecimal serviceScore,
      Boolean passed,
      String resultCode,
      Long standardVersionId,
      String standardVersion,
      int snapshotCount,
      int snapshotStandardIdCount,
      int snapshotVersionCount,
      Long snapshotStandardVersionId
  ) {
  }
}
