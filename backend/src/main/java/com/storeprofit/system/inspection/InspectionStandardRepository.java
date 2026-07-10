package com.storeprofit.system.inspection;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InspectionStandardRepository {
  private final JdbcTemplate jdbcTemplate;

  public InspectionStandardRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<VersionRow> activeVersion(long tenantId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select id, title, full_score, version, effective_date
          from inspection_standard_version
          where tenant_id = ? and status = 'ACTIVE'
          order by effective_date desc, id desc
          limit 1
          """, this::mapVersion, tenantId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<InspectionStandardItemResponse> items(long tenantId, long versionId) {
    return jdbcTemplate.query("""
        select id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order
        from inspection_standard_item
        where tenant_id = ? and standard_version_id = ? and enabled = 1
        order by red_line desc, dimension, sort_order, id
        """, this::mapItem, tenantId, versionId);
  }

  private VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Date effectiveDate = rs.getDate("effective_date");
    return new VersionRow(
        rs.getLong("id"),
        rs.getString("title"),
        amount(rs.getBigDecimal("full_score")),
        rs.getString("version"),
        effectiveDate == null ? null : effectiveDate.toLocalDate()
    );
  }

  private InspectionStandardItemResponse mapItem(ResultSet rs, int rowNum) throws SQLException {
    return new InspectionStandardItemResponse(
        rs.getLong("id"),
        rs.getString("dimension"),
        rs.getString("code"),
        rs.getString("title"),
        rs.getString("description"),
        amount(rs.getBigDecimal("suggested_score")),
        rs.getBoolean("red_line"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order")
    );
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
  }

  public record VersionRow(long id, String title, BigDecimal fullScore, String version, LocalDate effectiveDate) {
  }
}
