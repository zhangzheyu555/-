package com.storeprofit.system.salary;

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
public class SalaryRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public SalaryRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  private static final String RECORD_COLUMNS = """
      sr.id, sr.store_id, s.code as store_code, s.name as store_name,
      s.brand_id, b.name as brand_name, sr.month, sr.employee_id, sr.employee_name,
      sr.position, sr.attendance, sr.gross, sr.normal_hours, sr.ot_hours,
      sr.work_hours, sr.vacation_left, sr.vacation_note, sr.base, sr.social,
      sr.post, sr.meal, sr.full_attendance, sr.commission, sr.overtime,
      sr.seniority, sr.late_night, sr.subsidy, sr.performance,
      sr.deduct_uniform, sr.return_uniform, sr.status, sr.submitted_by, sr.reviewed_by, sr.reviewed_at,
      sr.review_note, sr.paid_at, sr.version
      """;

  private static final String RECORD_FROM = """
      from salary_record sr
      join store_branch s on s.id = sr.store_id and s.tenant_id = sr.tenant_id
      left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
      where sr.tenant_id = :tenantId
      """;

  public List<SalaryRecordResponse> records(long tenantId, String month, Long brandId, String storeId) {
    StringBuilder sql = new StringBuilder("select ").append(RECORD_COLUMNS).append(RECORD_FROM);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    appendFilters(sql, params, month, brandId, storeId);
    sql.append(" order by sr.month desc, s.code, sr.employee_name, sr.id");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public SalaryPageResult page(long tenantId, String month, Long brandId, String storeId, int page, int size) {
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    addFilterParams(params, month, brandId, storeId);

    String countSql = "select count(*) from salary_record sr join store_branch s on s.id = sr.store_id and s.tenant_id = sr.tenant_id left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id where sr.tenant_id = :tenantId"
        + filterSuffix(month, brandId, storeId);
    Integer totalObj = namedJdbcTemplate.queryForObject(countSql, params, Integer.class);
    int total = totalObj == null ? 0 : totalObj;

    StringBuilder dataSql = new StringBuilder("select ").append(RECORD_COLUMNS).append(RECORD_FROM);
    appendFilters(dataSql, params, month, brandId, storeId);
    dataSql.append(" order by sr.month desc, s.code, sr.employee_name, sr.id");
    dataSql.append(" limit :limit offset :offset");
    params.addValue("limit", size);
    params.addValue("offset", (page - 1) * size);

    List<SalaryRecordResponse> rows = namedJdbcTemplate.query(dataSql.toString(), params, this::mapRecord);
    return new SalaryPageResult(rows, total, page, size);
  }

  private void addFilterParams(MapSqlParameterSource params, String month, Long brandId, String storeId) {
    if (month != null && !month.isBlank()) params.addValue("month", month);
    if (brandId != null) params.addValue("brandId", brandId);
    if (storeId != null && !storeId.isBlank()) params.addValue("storeId", storeId);
  }

  private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String month, Long brandId, String storeId) {
    if (month != null && !month.isBlank()) {
      sql.append(" and sr.month = :month");
      params.addValue("month", month);
    }
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and sr.store_id = :storeId");
      params.addValue("storeId", storeId);
    }
  }

  private String filterSuffix(String month, Long brandId, String storeId) {
    StringBuilder suffix = new StringBuilder();
    if (month != null && !month.isBlank()) suffix.append(" and sr.month = :month");
    if (brandId != null) suffix.append(" and s.brand_id = :brandId");
    if (storeId != null && !storeId.isBlank()) suffix.append(" and sr.store_id = :storeId");
    return suffix.toString();
  }

  public record SalaryPageResult(List<SalaryRecordResponse> rows, int total, int page, int size) {
    public int totalPages() { return total == 0 ? 1 : (int) Math.ceil((double) total / size); }
  }

  public Optional<SalaryRecordResponse> record(long tenantId, String id) {
    List<SalaryRecordResponse> rows = namedJdbcTemplate.query(
        "select " + RECORD_COLUMNS + RECORD_FROM + " and sr.id = :id",
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRecord
    );
    return rows.stream().findFirst();
  }

  public boolean recordExistsForEmployee(long tenantId, String storeId, String month, String employeeName) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from salary_record
        where tenant_id = ? and store_id = ? and month = ? and employee_name = ?
        """,
        Integer.class,
        tenantId,
        storeId,
        month,
        employeeName
    );
    return count != null && count > 0;
  }

  public boolean recordExistsForEmployeeId(long tenantId, String storeId, String month, String employeeId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from salary_record
        where tenant_id = ? and store_id = ? and month = ? and employee_id = ?
        """,
        Integer.class,
        tenantId,
        storeId,
        month,
        employeeId
    );
    return count != null && count > 0;
  }

  public void upsert(long tenantId, String id, SalaryRecordRequest request) {
    if (recordExists(tenantId, id)) {
      update(tenantId, id, request);
      return;
    }
    insert(tenantId, id, request);
  }

  public int delete(long tenantId, String id) {
    return jdbcTemplate.update("delete from salary_record where tenant_id = ? and id = ?", tenantId, id);
  }

  public void updateStatus(long tenantId, String id, String status, Long submittedBy, Long reviewedBy) {
    jdbcTemplate.update("""
        update salary_record
        set status = ?,
            submitted_by = coalesce(?, submitted_by),
            reviewed_by = ?,
            reviewed_at = case when ? is null then reviewed_at else current_timestamp end,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        status,
        submittedBy,
        reviewedBy,
        reviewedBy,
        tenantId,
        id
    );
  }

  public void updateStatusWithNote(long tenantId, String id, String status, Long reviewedBy, String reviewNote) {
    jdbcTemplate.update("""
        update salary_record
        set status = ?,
            reviewed_by = ?,
            review_note = ?,
            reviewed_at = current_timestamp,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        status,
        reviewedBy,
        reviewNote,
        tenantId,
        id
    );
  }

  public void markPaid(long tenantId, String id) {
    jdbcTemplate.update("""
        update salary_record
        set status = 'PAID',
            paid_at = current_timestamp,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'APPROVED'
        """,
        tenantId,
        id
    );
  }

  public void lockRecord(long tenantId, String id) {
    jdbcTemplate.update("""
        update salary_record
        set status = 'LOCKED',
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status in ('APPROVED', 'PAID')
        """,
        tenantId,
        id
    );
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
      String month,
      String reason
  ) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        )
        values (?, ?, ?, ?, 'salary_record', ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        operatorId,
        operatorName,
        action,
        id,
        storeId,
        month,
        reason
    );
  }

  private boolean recordExists(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from salary_record where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        id
    );
    return count != null && count > 0;
  }

  private void insert(long tenantId, String id, SalaryRecordRequest request) {
    jdbcTemplate.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_id, employee_name, position, attendance,
          gross, normal_hours, ot_hours, work_hours, vacation_left, vacation_note,
          base, social, post, meal, full_attendance, commission, overtime,
          seniority, late_night, subsidy, performance, deduct_uniform,
          return_uniform, status, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', current_timestamp)
        """,
        id,
        tenantId,
        request.storeId(),
        request.month(),
        blankToNull(request.employeeId()),
        request.employeeName().trim(),
        blankToNull(request.position()),
        blankToNull(request.attendance()),
        amount(request.gross()),
        amount(request.normalHours()),
        amount(request.otHours()),
        amount(request.workHours()),
        amount(request.vacationLeft()),
        blankToNull(request.vacationNote()),
        amount(request.base()),
        amount(request.social()),
        amount(request.post()),
        amount(request.meal()),
        amount(request.fullAttendance()),
        amount(request.commission()),
        amount(request.overtime()),
        amount(request.seniority()),
        amount(request.lateNight()),
        amount(request.subsidy()),
        amount(request.performance()),
        amount(request.deductUniform()),
        amount(request.returnUniform())
    );
  }

  private void update(long tenantId, String id, SalaryRecordRequest request) {
    jdbcTemplate.update("""
        update salary_record set
          store_id = ?,
          month = ?,
          employee_id = ?,
          employee_name = ?,
          position = ?,
          attendance = ?,
          gross = ?,
          normal_hours = ?,
          ot_hours = ?,
          work_hours = ?,
          vacation_left = ?,
          vacation_note = ?,
          base = ?,
          social = ?,
          post = ?,
          meal = ?,
          full_attendance = ?,
          commission = ?,
          overtime = ?,
          seniority = ?,
          late_night = ?,
          subsidy = ?,
          performance = ?,
          deduct_uniform = ?,
          return_uniform = ?,
          updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """,
        request.storeId(),
        request.month(),
        blankToNull(request.employeeId()),
        request.employeeName().trim(),
        blankToNull(request.position()),
        blankToNull(request.attendance()),
        amount(request.gross()),
        amount(request.normalHours()),
        amount(request.otHours()),
        amount(request.workHours()),
        amount(request.vacationLeft()),
        blankToNull(request.vacationNote()),
        amount(request.base()),
        amount(request.social()),
        amount(request.post()),
        amount(request.meal()),
        amount(request.fullAttendance()),
        amount(request.commission()),
        amount(request.overtime()),
        amount(request.seniority()),
        amount(request.lateNight()),
        amount(request.subsidy()),
        amount(request.performance()),
        amount(request.deductUniform()),
        amount(request.returnUniform()),
        tenantId,
        id
    );
  }

  private SalaryRecordResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
    return new SalaryRecordResponse(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        getLongOrNull(rs, "brand_id"),
        rs.getString("brand_name"),
        rs.getString("month"),
        rs.getString("employee_id"),
        rs.getString("employee_name"),
        rs.getString("position"),
        rs.getString("attendance"),
        amount(rs.getBigDecimal("gross")),
        amount(rs.getBigDecimal("normal_hours")),
        amount(rs.getBigDecimal("ot_hours")),
        amount(rs.getBigDecimal("work_hours")),
        amount(rs.getBigDecimal("vacation_left")),
        rs.getString("vacation_note"),
        amount(rs.getBigDecimal("base")),
        amount(rs.getBigDecimal("social")),
        amount(rs.getBigDecimal("post")),
        amount(rs.getBigDecimal("meal")),
        amount(rs.getBigDecimal("full_attendance")),
        amount(rs.getBigDecimal("commission")),
        amount(rs.getBigDecimal("overtime")),
        amount(rs.getBigDecimal("seniority")),
        amount(rs.getBigDecimal("late_night")),
        amount(rs.getBigDecimal("subsidy")),
        amount(rs.getBigDecimal("performance")),
        amount(rs.getBigDecimal("deduct_uniform")),
        amount(rs.getBigDecimal("return_uniform")),
        rs.getString("status"),
        getLongOrNull(rs, "submitted_by"),
        getLongOrNull(rs, "reviewed_by"),
        rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
        rs.getString("review_note"),
        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toLocalDateTime(),
        rs.getInt("version")
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
