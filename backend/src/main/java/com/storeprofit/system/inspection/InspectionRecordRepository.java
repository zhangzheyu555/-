package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InspectionRecordRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
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
    StringBuilder sql = new StringBuilder("""
        select ir.id, ir.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ir.inspection_date, ir.inspector,
               ir.brand, ir.full_score, ir.score, ir.passed, ir.deductions_json,
               ir.redlines_json, ir.photos_json, ir.note
        from inspection_record ir
        join store_branch s on s.id = ir.store_id and s.tenant_id = ir.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
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
    }
    if (passed != null) {
      sql.append(" and ir.passed = :passed");
      params.addValue("passed", passed ? 1 : 0);
    }
    sql.append(" order by ir.inspection_date asc, s.code, ir.id");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public Optional<InspectionRecordResponse> record(long tenantId, String id) {
    List<InspectionRecordResponse> rows = namedJdbcTemplate.query("""
        select ir.id, ir.store_id, s.code as store_code, s.name as store_name,
               s.brand_id, b.name as brand_name, ir.inspection_date, ir.inspector,
               ir.brand, ir.full_score, ir.score, ir.passed, ir.deductions_json,
               ir.redlines_json, ir.photos_json, ir.note
        from inspection_record ir
        join store_branch s on s.id = ir.store_id and s.tenant_id = ir.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where ir.tenant_id = :tenantId and ir.id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRecord
    );
    return rows.stream().findFirst();
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
            red_line, problem_description, sort_order, created_at
          )
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
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
          snapshot.sortOrder() > 0 ? snapshot.sortOrder() : ++fallbackSortOrder
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
          score, passed, deductions_json, redlines_json, photos_json, note, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
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
        blankToNull(request.note())
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
        tenantId,
        id
    );
  }

  private InspectionRecordResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
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
        amount(rs.getBigDecimal("full_score")),
        amount(rs.getBigDecimal("score")),
        rs.getInt("passed") != 0,
        rs.getString("deductions_json"),
        rs.getString("redlines_json"),
        rs.getString("photos_json"),
        rs.getString("note")
    );
  }

  private Long getLongOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
