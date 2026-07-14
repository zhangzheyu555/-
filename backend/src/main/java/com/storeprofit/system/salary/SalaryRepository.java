package com.storeprofit.system.salary;

import com.storeprofit.system.platform.authorization.DataScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.EmptyResultDataAccessException;

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
      sr.position, sr.attendance, sr.gross, sr.net_pay, sr.normal_hours, sr.ot_hours,
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

  private static final String EMPLOYEE_RECORD_COLUMNS = """
      coalesce(sr.id, '') as id, e.store_id, s.code as store_code, s.name as store_name,
      s.brand_id, b.name as brand_name, :month as month, e.id as employee_id, e.name as employee_name,
      coalesce(e.position, e.role) as position, sr.attendance, sr.gross, sr.net_pay, sr.normal_hours, sr.ot_hours,
      sr.work_hours, sr.vacation_left, sr.vacation_note, sr.base,
      sr.social, sr.post, sr.meal, sr.full_attendance, sr.commission, sr.overtime,
      sr.seniority, sr.late_night, sr.subsidy, sr.performance, sr.deduct_uniform, sr.return_uniform,
      coalesce(sr.status, 'PENDING_GENERATION') as status, sr.submitted_by, sr.reviewed_by,
      sr.reviewed_at, sr.review_note, sr.paid_at, coalesce(sr.version, 0) as version
      """;

  private static final String EMPLOYEE_RECORD_FROM = """
      from employee e
      join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
      left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
      left join salary_record sr on sr.tenant_id = e.tenant_id and sr.store_id = e.store_id
        and sr.employee_id = e.id and sr.month = :month
      where e.tenant_id = :tenantId
        and (
          sr.id is not null
          or upper(coalesce(e.status, '')) not in ('离职', '停用', '删除', 'INACTIVE', 'DELETED')
        )
      """;

  public List<SalaryRecordResponse> records(long tenantId, String month, Long brandId, String storeId) {
    return records(tenantId, month, brandId, storeId, null);
  }

  public List<SalaryRecordResponse> records(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    StringBuilder sql = new StringBuilder("select ").append(RECORD_COLUMNS).append(RECORD_FROM);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    appendFilters(sql, params, month, brandId, storeId, dataScope);
    sql.append(" order by sr.month desc, s.code, sr.employee_name, sr.id");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public List<SalaryRecordResponse> employeeSalaryRows(long tenantId, String month, Long brandId, String storeId) {
    return employeeSalaryRows(tenantId, month, brandId, storeId, null);
  }

  public List<SalaryRecordResponse> employeeSalaryRows(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    StringBuilder sql = new StringBuilder("select ")
        .append(EMPLOYEE_RECORD_COLUMNS)
        .append(EMPLOYEE_RECORD_FROM);
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("month", month);
    appendEmployeeFilters(sql, params, brandId, storeId, null, null, dataScope);
    sql.append(" order by s.code, coalesce(e.position, e.role), e.name, e.id");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public SalaryEmployeePageResult employeeSalaryPage(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      String status,
      String keyword,
      int page,
      int size,
      DataScope dataScope
  ) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("month", month);
    StringBuilder countSql = new StringBuilder("select count(*) ").append(EMPLOYEE_RECORD_FROM);
    appendEmployeeFilters(countSql, params, brandId, storeId, status, keyword, dataScope);
    Integer totalValue = namedJdbcTemplate.queryForObject(countSql.toString(), params, Integer.class);
    int total = totalValue == null ? 0 : totalValue;

    StringBuilder dataSql = new StringBuilder("select ")
        .append(EMPLOYEE_RECORD_COLUMNS)
        .append(EMPLOYEE_RECORD_FROM);
    appendEmployeeFilters(dataSql, params, brandId, storeId, status, keyword, dataScope);
    dataSql.append(" order by s.code, coalesce(e.position, e.role), e.name, e.id")
        .append(" limit :limit offset :offset");
    params.addValue("limit", size);
    params.addValue("offset", (page - 1) * size);
    List<SalaryRecordResponse> rows = namedJdbcTemplate.query(dataSql.toString(), params, this::mapRecord);
    return new SalaryEmployeePageResult(rows, total, page, size);
  }

  public SalaryPageResult page(long tenantId, String month, Long brandId, String storeId, int page, int size) {
    return page(tenantId, month, brandId, storeId, page, size, null);
  }

  public SalaryPageResult page(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      int page,
      int size,
      DataScope dataScope
  ) {
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    StringBuilder countSql = new StringBuilder("select count(*) ").append(RECORD_FROM);
    appendFilters(countSql, params, month, brandId, storeId, dataScope);
    Integer totalObj = namedJdbcTemplate.queryForObject(countSql.toString(), params, Integer.class);
    int total = totalObj == null ? 0 : totalObj;

    StringBuilder dataSql = new StringBuilder("select ").append(RECORD_COLUMNS).append(RECORD_FROM);
    appendFilters(dataSql, params, month, brandId, storeId, dataScope);
    dataSql.append(" order by sr.month desc, s.code, sr.employee_name, sr.id");
    dataSql.append(" limit :limit offset :offset");
    params.addValue("limit", size);
    params.addValue("offset", (page - 1) * size);

    List<SalaryRecordResponse> rows = namedJdbcTemplate.query(dataSql.toString(), params, this::mapRecord);
    return new SalaryPageResult(rows, total, page, size);
  }

  private void appendFilters(
      StringBuilder sql,
      MapSqlParameterSource params,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
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
    appendStoreScope(sql, params, "sr.store_id", dataScope);
  }

  private void appendEmployeeFilters(
      StringBuilder sql,
      MapSqlParameterSource params,
      Long brandId,
      String storeId,
      String status,
      String keyword,
      DataScope dataScope
  ) {
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and e.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    if (status != null && !status.isBlank()) {
      sql.append(" and coalesce(sr.status, 'PENDING_GENERATION') = :status");
      params.addValue("status", status.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      sql.append(" and (lower(e.name) like :keyword")
          .append(" or lower(e.id) like :keyword")
          .append(" or lower(coalesce(e.position, e.role, '')) like :keyword)");
      params.addValue("keyword", "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%");
    }
    appendStoreScope(sql, params, "e.store_id", dataScope);
  }

  private void appendStoreScope(
      StringBuilder sql,
      MapSqlParameterSource params,
      String storeColumn,
      DataScope dataScope
  ) {
    if (dataScope == null || dataScope.allowsAllStores()) {
      return;
    }
    Collection<String> storeIds = dataScope.storeIds();
    if (dataScope.deniesStoreAccess() || storeIds == null || storeIds.isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and ").append(storeColumn).append(" in (:scopeStoreIds)");
    params.addValue("scopeStoreIds", storeIds);
  }

  public record SalaryPageResult(List<SalaryRecordResponse> rows, int total, int page, int size) {
    public int totalPages() { return total == 0 ? 1 : (int) Math.ceil((double) total / size); }
  }

  public record SalaryEmployeePageResult(List<SalaryRecordResponse> rows, int total, int page, int size) {
    public int totalPages() { return total == 0 ? 1 : (int) Math.ceil((double) total / size); }
  }

  public List<SalaryAvailableMonth> availableMonths(long tenantId, String storeId) {
    return availableMonths(tenantId, storeId, null);
  }

  public List<SalaryAvailableMonth> availableMonths(long tenantId, String storeId, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("""
        select sr.month, count(*) as record_count, max(sr.status) as latest_status
        from salary_record sr
        where sr.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and sr.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    }
    appendStoreScope(sql, params, "sr.store_id", dataScope);
    sql.append(" group by sr.month order by sr.month desc");
    return namedJdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new SalaryAvailableMonth(
        rs.getString("month"), rs.getInt("record_count"), rs.getString("latest_status")));
  }

  public Optional<SalaryRecordResponse> record(long tenantId, String id) {
    return record(tenantId, id, null);
  }

  public Optional<SalaryRecordResponse> record(long tenantId, String id, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("select ")
        .append(RECORD_COLUMNS)
        .append(RECORD_FROM)
        .append(" and sr.id = :id");
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("id", id);
    appendStoreScope(sql, params, "sr.store_id", dataScope);
    List<SalaryRecordResponse> rows = namedJdbcTemplate.query(
        sql.toString(),
        params,
        this::mapRecord
    );
    return rows.stream().findFirst();
  }

  public Optional<String> recordStoreId(long tenantId, String id) {
    return jdbcTemplate.queryForList(
        "select store_id from salary_record where tenant_id = ? and id = ?",
        String.class,
        tenantId,
        id
    ).stream().findFirst();
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

  public Optional<String> recordIdForEmployeeId(
      long tenantId,
      String storeId,
      String month,
      String employeeId
  ) {
    return jdbcTemplate.queryForList("""
        select id
        from salary_record
        where tenant_id = ? and store_id = ? and month = ? and employee_id = ?
        order by id
        limit 1
        """,
        String.class,
        tenantId,
        storeId,
        month,
        employeeId
    ).stream().findFirst();
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

  public int updateStatus(long tenantId, String id, String status, Long submittedBy, Long reviewedBy, int expectedVersion) {
    return jdbcTemplate.update("""
        update salary_record
        set status = ?,
            submitted_by = coalesce(?, submitted_by),
            reviewed_by = ?,
            reviewed_at = case when ? is null then reviewed_at else current_timestamp end,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and version = ?
        """,
        status,
        submittedBy,
        reviewedBy,
        reviewedBy,
        tenantId,
        id,
        expectedVersion
    );
  }

  public int updateStatusWithNote(long tenantId, String id, String status, Long reviewedBy, String reviewNote, int expectedVersion) {
    return jdbcTemplate.update("""
        update salary_record
        set status = ?,
            reviewed_by = ?,
            review_note = ?,
            reviewed_at = current_timestamp,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and version = ?
        """,
        status,
        reviewedBy,
        reviewNote,
        tenantId,
        id,
        expectedVersion
    );
  }

  public int markPaid(long tenantId, String id, int expectedVersion) {
    return jdbcTemplate.update("""
        update salary_record
        set status = 'PAID',
            paid_at = current_timestamp,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'APPROVED' and version = ?
        """,
        tenantId,
        id,
        expectedVersion
    );
  }

  public int lockRecord(long tenantId, String id, int expectedVersion) {
    return jdbcTemplate.update("""
        update salary_record
        set status = 'LOCKED',
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = ? and id = ? and status in ('APPROVED', 'PAID') and version = ?
        """,
        tenantId,
        id,
        expectedVersion
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

  public Optional<SalaryProfileRow> salaryProfile(long tenantId, String employeeId, String month) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select policy_id, base_salary, overtime_hour_rate, commission_type, performance_type
          from employee_salary_profile
          where tenant_id = ? and employee_id = ?
            and effective_from <= last_day(concat(?, '-01'))
            and (effective_to is null or effective_to >= concat(?, '-01'))
          order by effective_from desc, id desc
          limit 1
          """, (rs, rowNum) -> new SalaryProfileRow(
              rs.getString("policy_id"), amount(rs.getBigDecimal("base_salary")),
              nullableAmount(rs.getBigDecimal("overtime_hour_rate")), rs.getString("commission_type"),
              rs.getString("performance_type")
          ), tenantId, employeeId, month, month));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<SalaryPolicyRow> activePolicy(long tenantId, String policyId, String month) {
    if (policyId == null || policyId.isBlank()) return Optional.empty();
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select id, name, version, overtime_hour_rate, overtime_hour_rate_source,
                 guarantee_enabled, guarantee_full_attendance_days
          from salary_policy
          where tenant_id = ? and id = ? and status = 'ACTIVE'
            and (effective_from is null or effective_from <= last_day(concat(?, '-01')))
            and (effective_to is null or effective_to >= concat(?, '-01'))
          limit 1
          """, (rs, rowNum) -> new SalaryPolicyRow(
              rs.getString("id"), rs.getString("name"), rs.getInt("version"),
              nullableAmount(rs.getBigDecimal("overtime_hour_rate")), rs.getString("overtime_hour_rate_source"),
              rs.getBoolean("guarantee_enabled"), nullableAmount(rs.getBigDecimal("guarantee_full_attendance_days"))
          ), tenantId, policyId, month, month));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<AttendanceRow> attendance(long tenantId, String storeId, String employeeId, String month) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select attendance_days, normal_hours, overtime_hours, total_hours, vacation_balance, source, status
          from employee_month_attendance
          where tenant_id = ? and store_id = ? and employee_id = ? and month = ? and status = 'CONFIRMED'
          limit 1
          """, (rs, rowNum) -> new AttendanceRow(
              amount(rs.getBigDecimal("attendance_days")), amount(rs.getBigDecimal("normal_hours")),
              amount(rs.getBigDecimal("overtime_hours")), amount(rs.getBigDecimal("total_hours")),
              amount(rs.getBigDecimal("vacation_balance")), rs.getString("source"), rs.getString("status")
          ), tenantId, storeId, employeeId, month));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public void saveCalculationSnapshot(
      long tenantId, String salaryId, SalaryPolicyRow policy, String policySnapshot,
      String calculationSnapshot, BigDecimal baseAmount, BigDecimal overtimeAmount, BigDecimal netPay
  ) {
    jdbcTemplate.update("""
        update salary_record
        set policy_id = ?, policy_version = ?, policy_snapshot_json = ?, calculation_snapshot_json = ?, net_pay = ?
        where tenant_id = ? and id = ?
        """, policy.id(), policy.version(), policySnapshot, calculationSnapshot, amount(netPay), tenantId, salaryId);
    jdbcTemplate.update("delete from salary_record_item where tenant_id = ? and salary_record_id = ?", tenantId, salaryId);
    insertSalaryItem(tenantId, salaryId, "BASE", "基础工资", "EARNING", baseAmount, "PROFILE", 10);
    if (overtimeAmount != null && overtimeAmount.compareTo(BigDecimal.ZERO) > 0) {
      insertSalaryItem(tenantId, salaryId, "OVERTIME", "加班工资", "EARNING", overtimeAmount, "CALCULATED", 20);
    }
  }

  private void insertSalaryItem(long tenantId, String salaryId, String code, String name, String type,
                                BigDecimal value, String source, int sortOrder) {
    jdbcTemplate.update("""
        insert into salary_record_item(id, tenant_id, salary_record_id, item_code, item_name, item_type,
          amount, source, calculation_note, sort_order, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, "SRI-" + salaryId + "-" + code, tenantId, salaryId, code, name, type,
        amount(value), source, "后端工资政策计算", sortOrder);
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
    logAction(tenantId, operatorId, operatorName, action, id, storeId, month, reason, null, null);
  }

  /**
   * Log an action with before/after status for audit trail.
   * The status values are stored as JSON in the before_json/after_json columns.
   */
  public void logAction(
      long tenantId,
      long operatorId,
      String operatorName,
      String action,
      String id,
      String storeId,
      String month,
      String reason,
      String beforeStatus,
      String afterStatus
  ) {
    String beforeJson = beforeStatus == null ? null : "{\"status\":\"" + beforeStatus + "\"}";
    String afterJson = afterStatus == null ? null : "{\"status\":\"" + afterStatus + "\"}";
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, before_json, after_json, created_at
        )
        values (?, ?, ?, ?, 'salary_record', ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        operatorId,
        operatorName,
        action,
        id,
        storeId,
        month,
        reason,
        beforeJson,
        afterJson
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
          version = version + 1,
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

  public int updateWithVersion(long tenantId, String id, SalaryRecordRequest request, int expectedVersion) {
    return jdbcTemplate.update("""
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
          version = version + 1,
          updated_at = current_timestamp
        where tenant_id = ? and id = ? and version = ?
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
        id,
        expectedVersion
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
        nullableAmount(rs.getBigDecimal("net_pay")),
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

  private BigDecimal nullableAmount(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record SalaryProfileRow(String policyId, BigDecimal baseSalary, BigDecimal overtimeHourRate,
                                 String commissionType, String performanceType) {}

  public record SalaryPolicyRow(String id, String name, int version, BigDecimal overtimeHourRate,
                                String overtimeHourRateSource, boolean guaranteeEnabled,
                                BigDecimal guaranteeFullAttendanceDays) {}

  public record AttendanceRow(BigDecimal attendanceDays, BigDecimal normalHours, BigDecimal overtimeHours,
                              BigDecimal totalHours, BigDecimal vacationBalance, String source, String status) {}
}
