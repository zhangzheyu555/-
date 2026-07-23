package com.storeprofit.system.finance;

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

@Repository
public class FinanceRepository {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;

  public FinanceRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.namedJdbcTemplate = namedJdbcTemplate;
  }

  public List<ProfitEntryResponse> entries(long tenantId, String month, Long brandId, String storeId) {
    return entries(tenantId, month, brandId, storeId, null);
  }

  public List<ProfitEntryResponse> entries(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      DataScope dataScope
  ) {
    StringBuilder sql = new StringBuilder("""
        select p.id, p.store_id, s.code as store_code, s.name as store_name, s.brand_id,
               b.name as brand_name, s.area, s.manager, p.month, p.sales, p.refund, p.discount,
               p.material, p.packaging, p.loss, p.cost_other, p.rent, p.labor, p.utility,
               p.property, p.commission, p.meituan, p.eleme, p.douyin, p.amap,
               p.promo, p.repair, p.equip, p.exp_other, p.note
        from profit_entry p
        join store_branch s on s.id = p.store_id and s.tenant_id = p.tenant_id
        left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where p.tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("tenantId", tenantId);
    if (month != null && !month.isBlank()) {
      sql.append(" and p.month = :month");
      params.addValue("month", month);
    }
    if (brandId != null) {
      sql.append(" and s.brand_id = :brandId");
      params.addValue("brandId", brandId);
    }
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and p.store_id = :storeId");
      params.addValue("storeId", storeId);
    }
    appendStoreScope(sql, params, "p.store_id", dataScope);
    sql.append(" order by p.month desc, net_sort desc, b.sort_order, s.code, s.id");
    String query = sql.toString().replace("net_sort", "(p.sales - p.refund - p.discount - p.material - p.packaging - p.loss - p.cost_other - p.rent - p.labor - p.utility - p.property - p.commission - p.meituan - p.eleme - p.douyin - p.amap - p.promo - p.repair - p.equip - p.exp_other)");
    return namedJdbcTemplate.query(query, params, this::mapEntry);
  }

  public Optional<ProfitEntryResponse> entry(long tenantId, String storeId, String month) {
    List<ProfitEntryResponse> rows = entries(tenantId, month, null, storeId);
    return rows.stream().findFirst();
  }

  public Optional<ProfitEntryResponse> entry(long tenantId, String storeId, String month, DataScope dataScope) {
    return entries(tenantId, month, null, storeId, dataScope).stream().findFirst();
  }

  public void upsert(long tenantId, ProfitEntryRequest request, Long userId) {
    jdbcTemplate.update("""
        insert into profit_entry(
          tenant_id, store_id, month, sales, refund, discount, material, packaging, loss, cost_other,
          rent, labor, utility, property, commission, meituan, eleme, douyin, amap,
          promo, repair, equip, exp_other,
          note, created_by, updated_by, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          sales = values(sales),
          refund = values(refund),
          discount = values(discount),
          material = values(material),
          packaging = values(packaging),
          loss = values(loss),
          cost_other = values(cost_other),
          rent = values(rent),
          labor = values(labor),
          utility = values(utility),
          property = values(property),
          commission = values(commission),
          meituan = values(meituan),
          eleme = values(eleme),
          douyin = values(douyin),
          amap = values(amap),
          promo = values(promo),
          repair = values(repair),
          equip = values(equip),
          exp_other = values(exp_other),
          note = values(note),
          updated_by = values(updated_by),
          updated_at = current_timestamp
        """,
        tenantId,
        request.storeId(),
        request.month(),
        amount(request.sales()),
        amount(request.refund()),
        amount(request.discount()),
        amount(request.material()),
        amount(request.packaging()),
        amount(request.loss()),
        amount(request.costOther()),
        amount(request.rent()),
        amount(request.labor()),
        amount(request.utility()),
        amount(request.property()),
        amount(request.commission()),
        amount(request.meituan()),
        amount(request.eleme()),
        amount(request.douyin()),
        amount(request.amap()),
        amount(request.promo()),
        amount(request.repair()),
        amount(request.equip()),
        amount(request.expOther()),
        blankToNull(request.note()),
        userId,
        userId
    );
  }

  public List<String> availableMonths(long tenantId) {
    return availableMonths(tenantId, null);
  }

  public List<String> availableMonths(long tenantId, DataScope dataScope) {
    StringBuilder sql = new StringBuilder("""
        select distinct month
        from profit_entry
        where tenant_id = :tenantId
        """);
    MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
    appendStoreScope(sql, params, "store_id", dataScope);
    sql.append(" order by month desc");
    return namedJdbcTemplate.queryForList(sql.toString(), params, String.class);
  }

  public int profitCount(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from profit_entry where tenant_id = ?", Integer.class, tenantId);
    return count == null ? 0 : count;
  }

  public List<String> storeIds(long tenantId) {
    return jdbcTemplate.queryForList("select id from store_branch where tenant_id = ? order by id", String.class, tenantId);
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

  public Optional<Long> storeBrandId(long tenantId, String storeId) {
    return jdbcTemplate.query(
        "select brand_id from store_branch where tenant_id = ? and id = ?",
        (rs, rowNum) -> rs.getObject("brand_id", Long.class),
        tenantId,
        storeId
    ).stream().filter(java.util.Objects::nonNull).findFirst();
  }

  public boolean entryExists(long tenantId, String storeId, String month) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from profit_entry where tenant_id = ? and store_id = ? and month = ?",
        Integer.class,
        tenantId,
        storeId,
        month
    );
    return count != null && count > 0;
  }

  public void deleteEntry(long tenantId, String storeId, String month) {
    jdbcTemplate.update(
        "delete from profit_entry where tenant_id = ? and store_id = ? and month = ?",
        tenantId,
        storeId,
        month
    );
  }

  public void logSave(long tenantId, Long operatorId, String operatorName, String storeId, String month) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, month, reason, created_at)
        values (?, ?, ?, '保存', 'profit_entry', ?, ?, ?, '利润录入保存', current_timestamp)
        """, tenantId, operatorId, operatorName, storeId + "|" + month, storeId, month);
  }

  public void logDelete(long tenantId, Long operatorId, String operatorName, String storeId, String month) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, store_id, month, reason, created_at)
        values (?, ?, ?, 'delete', 'profit_entry', ?, ?, ?, 'profit entry deleted', current_timestamp)
        """, tenantId, operatorId, operatorName, storeId + "|" + month, storeId, month);
  }

  private ProfitEntryResponse mapEntry(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal sales = amount(rs.getBigDecimal("sales"));
    BigDecimal refund = amount(rs.getBigDecimal("refund"));
    BigDecimal discount = amount(rs.getBigDecimal("discount"));
    BigDecimal income = sales.subtract(refund).subtract(discount);
    BigDecimal material = amount(rs.getBigDecimal("material"));
    BigDecimal packaging = amount(rs.getBigDecimal("packaging"));
    BigDecimal loss = amount(rs.getBigDecimal("loss"));
    BigDecimal costOther = amount(rs.getBigDecimal("cost_other"));
    BigDecimal costSum = material.add(packaging).add(loss).add(costOther);
    BigDecimal gross = income.subtract(costSum);
    BigDecimal rent = amount(rs.getBigDecimal("rent"));
    BigDecimal labor = amount(rs.getBigDecimal("labor"));
    BigDecimal utility = amount(rs.getBigDecimal("utility"));
    BigDecimal property = amount(rs.getBigDecimal("property"));
    BigDecimal commission = amount(rs.getBigDecimal("commission"));
    BigDecimal meituan = amount(rs.getBigDecimal("meituan"));
    BigDecimal eleme = amount(rs.getBigDecimal("eleme"));
    BigDecimal douyin = amount(rs.getBigDecimal("douyin"));
    BigDecimal amap = amount(rs.getBigDecimal("amap"));
    BigDecimal promo = amount(rs.getBigDecimal("promo"));
    BigDecimal repair = amount(rs.getBigDecimal("repair"));
    BigDecimal equip = amount(rs.getBigDecimal("equip"));
    BigDecimal expOther = amount(rs.getBigDecimal("exp_other"));
    BigDecimal expenseSum = rent.add(labor).add(utility).add(property).add(commission)
        .add(meituan).add(eleme).add(douyin).add(amap)
        .add(promo).add(repair).add(equip).add(expOther);
    BigDecimal net = gross.subtract(expenseSum);
    BigDecimal margin = ratio(net, income);
    return new ProfitEntryResponse(
        rs.getLong("id"),
        rs.getString("store_id"),
        rs.getString("store_code"),
        rs.getString("store_name"),
        rs.getLong("brand_id"),
        rs.getString("brand_name"),
        rs.getString("area"),
        rs.getString("manager"),
        rs.getString("month"),
        sales,
        refund,
        discount,
        income,
        material,
        packaging,
        loss,
        costOther,
        costSum,
        ratio(costSum, income),
        gross,
        ratio(gross, income),
        rent,
        labor,
        utility,
        property,
        commission,
        meituan,
        eleme,
        douyin,
        amap,
        promo,
        repair,
        equip,
        expOther,
        expenseSum,
        net,
        margin,
        risk(net, margin),
        rs.getString("note")
    );
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
  }

  private String risk(BigDecimal net, BigDecimal margin) {
    if (net.compareTo(BigDecimal.ZERO) < 0) {
      return "亏损";
    }
    if (margin.compareTo(new BigDecimal("0.08")) < 0) {
      return "关注";
    }
    return "健康";
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
}
