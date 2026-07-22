package com.storeprofit.system.employee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public EmployeeRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<EmployeeResponse> records(long tenantId, Long brandId, String storeId, String status) {
    return records(tenantId, brandId, storeId, status, null);
  }

  public List<EmployeeResponse> records(
      long tenantId,
      Long brandId,
      String storeId,
      String status,
      Collection<String> allowedStoreIds
  ) {
    StringBuilder sql = new StringBuilder("""
        select e.id, e.store_id, s.code as store_code,
               coalesce(e.store_name, s.name) as store_name,
               s.brand_id, coalesce(e.brand_name, b.name) as brand_name,
               e.name, e.phone, e.role, e.position, e.employment_type,
               e.base_salary, e.status, date_format(e.hire_date, '%Y-%m-%d') as hire_date,
               e.remark,
               e.birthday, e.id_card_no,
               date_format(e.health_cert_issue_date, '%Y-%m-%d') as health_cert_issue_date,
               date_format(e.health_cert_expire_date, '%Y-%m-%d') as health_cert_expire_date,
               e.contract_sign_text,
               date_format(e.regular_date, '%Y-%m-%d') as regular_date,
               date_format(e.trainer_date, '%Y-%m-%d') as trainer_date,
               date_format(e.shift_leader_date, '%Y-%m-%d') as shift_leader_date,
               date_format(e.manager_date, '%Y-%m-%d') as manager_date,
               e.auth_user_id, au.username as account_username, au.enabled as account_enabled,
               e.hourly_rate
        from employee e
        left join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        left join auth_user au on au.id = e.auth_user_id
        where e.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and e.store_id = :storeId");
      params.addValue("storeId", storeId.trim());
    } else if (allowedStoreIds != null) {
      List<String> normalizedStoreIds = allowedStoreIds.stream()
          .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
          .map(String::trim)
          .distinct()
          .toList();
      if (normalizedStoreIds.isEmpty()) {
        sql.append(" and 1 = 0");
      } else {
        sql.append(" and e.store_id in (:allowedStoreIds)");
        params.addValue("allowedStoreIds", normalizedStoreIds);
      }
    }
    if (status != null && !status.isBlank()) {
      sql.append(" and e.status = :status");
      params.addValue("status", status.trim());
    }
    sql.append(" order by s.code, e.status desc, e.role, e.name");
    return namedJdbcTemplate.query(sql.toString(), params, this::mapRecord);
  }

  public Optional<EmployeeResponse> record(long tenantId, String id) {
    List<EmployeeResponse> rows = namedJdbcTemplate.query("""
        select e.id, e.store_id, s.code as store_code,
               coalesce(e.store_name, s.name) as store_name,
               s.brand_id, coalesce(e.brand_name, b.name) as brand_name,
               e.name, e.phone, e.role, e.position, e.employment_type,
               e.base_salary, e.status, date_format(e.hire_date, '%Y-%m-%d') as hire_date,
               e.remark,
               e.birthday, e.id_card_no,
               date_format(e.health_cert_issue_date, '%Y-%m-%d') as health_cert_issue_date,
               date_format(e.health_cert_expire_date, '%Y-%m-%d') as health_cert_expire_date,
               e.contract_sign_text,
               date_format(e.regular_date, '%Y-%m-%d') as regular_date,
               date_format(e.trainer_date, '%Y-%m-%d') as trainer_date,
               date_format(e.shift_leader_date, '%Y-%m-%d') as shift_leader_date,
               date_format(e.manager_date, '%Y-%m-%d') as manager_date,
               e.auth_user_id, au.username as account_username, au.enabled as account_enabled,
               e.hourly_rate
        from employee e
        left join store_branch s on s.id = e.store_id and s.tenant_id = e.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        left join auth_user au on au.id = e.auth_user_id
        where e.tenant_id = :tenantId and e.id = :id
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("id", id),
        this::mapRecord
    );
    return rows.stream().findFirst();
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

  public Optional<String> storeName(long tenantId, String storeId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select name
          from store_branch
          where tenant_id = ? and id = ?
          """, String.class, tenantId, storeId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<String> brandName(long tenantId, String storeId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select b.name
          from store_branch s
          left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
          where s.tenant_id = ? and s.id = ?
          """, String.class, tenantId, storeId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public void upsertSeed(
      long tenantId,
      String id,
      String storeId,
      String storeName,
      String brandName,
      String name,
      String role,
      String position,
      String employmentType,
      BigDecimal baseSalary,
      String status,
      String remark,
      String dataSource
  ) {
    namedJdbcTemplate.update("""
        insert into employee(
          id, tenant_id, store_id, store_name, brand_name, name, role, position,
          employment_type, base_salary, status, remark, data_source, created_at, updated_at
        )
        values(
          :id, :tenantId, :storeId, :storeName, :brandName, :name, :role, :position,
          :employmentType, :baseSalary, :status, :remark, :dataSource, current_timestamp, current_timestamp
        )
        on duplicate key update
          store_name = if(data_source = 'LEGACY_SEED', values(store_name), store_name),
          brand_name = if(data_source = 'LEGACY_SEED', values(brand_name), brand_name),
          role = if(data_source = 'LEGACY_SEED', values(role), role),
          position = if(data_source = 'LEGACY_SEED', values(position), position),
          employment_type = if(data_source = 'LEGACY_SEED', values(employment_type), employment_type),
          base_salary = if(data_source = 'LEGACY_SEED', values(base_salary), base_salary),
          status = if(data_source = 'LEGACY_SEED', values(status), status),
          remark = if(data_source = 'LEGACY_SEED', values(remark), remark),
          data_source = if(data_source = 'LEGACY_SEED', values(data_source), data_source),
          updated_at = current_timestamp
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId)
            .addValue("storeId", storeId)
            .addValue("storeName", blankToNull(storeName))
            .addValue("brandName", blankToNull(brandName))
            .addValue("name", name)
            .addValue("role", blankToNull(role))
            .addValue("position", blankToNull(position))
            .addValue("employmentType", blankToNull(employmentType))
            .addValue("baseSalary", amount(baseSalary))
            .addValue("status", status == null || status.isBlank() ? "在职" : status)
            .addValue("remark", blankToNull(remark))
            .addValue("dataSource", dataSource == null || dataSource.isBlank() ? "MANUAL_ENTRY" : dataSource)
    );
  }

  private EmployeeResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
    return new EmployeeResponse(
        rs.getString("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        boxedLong(rs.getLong("brand_id"), rs.wasNull()),
        rs.getString("brand_name"),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("role"),
        rs.getString("position"),
        rs.getString("employment_type"),
        amount(rs.getBigDecimal("base_salary")),
        rs.getString("status"),
        rs.getString("hire_date"),
        rs.getString("remark"),
        rs.getString("birthday"),
        rs.getString("id_card_no"),
        rs.getString("health_cert_issue_date"),
        rs.getString("health_cert_expire_date"),
        rs.getString("contract_sign_text"),
        rs.getString("regular_date"),
        rs.getString("trainer_date"),
        rs.getString("shift_leader_date"),
        rs.getString("manager_date"),
        boxedLong(rs.getLong("auth_user_id"), rs.wasNull()),
        rs.getString("account_username"),
        boxedBoolean(rs.getBoolean("account_enabled"), rs.wasNull()),
        nullableAmount(rs.getBigDecimal("hourly_rate"))
    );
  }

  private Boolean boxedBoolean(boolean value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private Long boxedLong(long value, boolean wasNull) {
    return wasNull ? null : value;
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

  /* ---------------- 员工档案增删改（docs/员工信息管理设计文档.md） ---------------- */

  /** 新增或按 Excel 更新档案；导入与手工编辑共用（profile 字段以入参为准，工资底薪不动）。 */
  public void upsertProfile(long tenantId, String id, EmployeeUpsertRequest req, String dataSource) {
    namedJdbcTemplate.update("""
        insert into employee(
          id, tenant_id, store_id, name, phone, position, employment_type, status,
          hire_date, birthday, id_card_no, health_cert_issue_date, health_cert_expire_date,
          contract_sign_text, regular_date, trainer_date, shift_leader_date, manager_date,
          base_salary, hourly_rate, remark, data_source, created_at, updated_at
        ) values (
          :id, :tenantId, :storeId, :name, :phone, :position, :employmentType, :status,
          :hireDate, :birthday, :idCardNo, :healthIssue, :healthExpire,
          :contract, :regularDate, :trainerDate, :shiftLeaderDate, :managerDate,
          0, :hourlyRate, :remark, :dataSource, current_timestamp, current_timestamp
        )
        on duplicate key update
          phone = values(phone), position = values(position),
          employment_type = values(employment_type), status = values(status),
          hire_date = values(hire_date), birthday = values(birthday),
          id_card_no = values(id_card_no),
          health_cert_issue_date = values(health_cert_issue_date),
          health_cert_expire_date = values(health_cert_expire_date),
          contract_sign_text = values(contract_sign_text),
          regular_date = values(regular_date), trainer_date = values(trainer_date),
          shift_leader_date = values(shift_leader_date), manager_date = values(manager_date),
          hourly_rate = values(hourly_rate),
          remark = values(remark), data_source = values(data_source),
          updated_at = current_timestamp
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId)
            .addValue("storeId", req.storeId())
            .addValue("name", req.name().trim())
            .addValue("phone", blankToNull(req.phone()))
            .addValue("position", blankToNull(req.position()))
            .addValue("employmentType", blankToNull(req.employmentType()))
            .addValue("status", req.status() == null || req.status().isBlank() ? "在职" : req.status().trim())
            .addValue("hireDate", blankToNull(req.hireDate()))
            .addValue("birthday", blankToNull(req.birthday()))
            .addValue("idCardNo", blankToNull(req.idCardNo()))
            .addValue("healthIssue", blankToNull(req.healthCertIssueDate()))
            .addValue("healthExpire", blankToNull(req.healthCertExpireDate()))
            .addValue("contract", blankToNull(req.contractSignText()))
            .addValue("regularDate", blankToNull(req.regularDate()))
            .addValue("trainerDate", blankToNull(req.trainerDate()))
            .addValue("shiftLeaderDate", blankToNull(req.shiftLeaderDate()))
            .addValue("managerDate", blankToNull(req.managerDate()))
            .addValue("dataSource", dataSource)
            .addValue("hourlyRate", req.hourlyRate())
            .addValue("remark", blankToNull(req.remark()))
    );
  }

  public boolean exists(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from employee where tenant_id = ? and id = ?", Integer.class, tenantId, id);
    return count != null && count > 0;
  }

  /**
   * Distinguishes a foreign-tenant employee id from an unknown one without returning any
   * employee data. Services use this only to preserve the authorization boundary (403 vs 404).
   */
  public boolean employeeIdBelongsToOtherTenant(long tenantId, String id) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from employee where id = ? and tenant_id <> ?",
        Integer.class,
        id,
        tenantId
    );
    return count != null && count > 0;
  }

  public void updateStatus(long tenantId, String id, String status) {
    jdbcTemplate.update(
        "update employee set status = ?, updated_at = current_timestamp where tenant_id = ? and id = ?",
        status, tenantId, id);
  }

  /** 员工所属门店的店长账号（一店多店长取最早创建的），作为员工账号前缀。 */
  public Optional<String> managerUsername(long tenantId, String storeId) {
    List<String> rows = jdbcTemplate.queryForList(
        "select username from auth_user where tenant_id = ? and store_id = ? and role = 'STORE_MANAGER'"
            + " and enabled = 1 order by id limit 1",
        String.class, tenantId, storeId);
    return rows.stream().findFirst();
  }

  /** 前缀下已用的最大序号（ruguo1-3 → 3）；序号只增不复用。 */
  public int maxAccountSeq(long tenantId, String prefix) {
    List<String> usernames = jdbcTemplate.queryForList(
        "select username from auth_user where tenant_id = ? and username like ?",
        String.class, tenantId, prefix + "-%");
    int max = 0;
    for (String username : usernames) {
      String tail = username.substring(username.lastIndexOf('-') + 1);
      try {
        max = Math.max(max, Integer.parseInt(tail));
      } catch (NumberFormatException ignored) {
        // 非本规则的账号（如手工建的 ruguo1-test）不参与序号计算
      }
    }
    return max;
  }

  public Optional<Long> userIdByUsername(long tenantId, String username) {
    List<Long> rows = jdbcTemplate.queryForList(
        "select id from auth_user where tenant_id = ? and username = ?", Long.class, tenantId, username);
    return rows.stream().findFirst();
  }

  public void linkAccount(long tenantId, String employeeId, long authUserId) {
    jdbcTemplate.update(
        "update employee set auth_user_id = ?, updated_at = current_timestamp where tenant_id = ? and id = ?",
        authUserId, tenantId, employeeId);
  }

  public void setAccountEnabled(long authUserId, boolean enabled) {
    jdbcTemplate.update("update auth_user set enabled = ? where id = ?", enabled ? 1 : 0, authUserId);
  }

  public Optional<EmployeeResponse> byAuthUserId(long tenantId, long authUserId) {
    List<String> ids = jdbcTemplate.queryForList(
        "select id from employee where tenant_id = ? and auth_user_id = ?", String.class, tenantId, authUserId);
    return ids.stream().findFirst().flatMap(id -> record(tenantId, id));
  }

  /** 导入用：全部门店 id+name，做简称匹配。 */
  public List<String[]> storeIdNames(long tenantId) {
    return jdbcTemplate.query(
        "select id, name from store_branch where tenant_id = ?",
        (rs, i) -> new String[] {rs.getString("id"), rs.getString("name")},
        tenantId);
  }

  /** 导入时门店缺失则建档（brand 取第一个，状态与现网一致「营业中」）。 */
  public void createStore(long tenantId, String id, String code, String name) {
    jdbcTemplate.update("""
        insert into store_branch (id, tenant_id, brand_id, code, name, status, created_at, updated_at)
        values (?, ?, (select min(id) from brand where tenant_id = ?), ?, ?, '营业中',
                current_timestamp, current_timestamp)
        """, id, tenantId, tenantId, code, name);
  }
}
