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
          select id, title, full_score, pass_score, version, effective_date
          from inspection_standard_version
          where tenant_id = ? and status = 'ACTIVE'
          order by effective_date desc, id desc
          limit 1
          """, this::mapVersion, tenantId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public Optional<VersionRow> version(long tenantId, long versionId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select id, title, full_score, pass_score, version, effective_date
          from inspection_standard_version
          where tenant_id = ? and id = ?
          """, this::mapVersion, tenantId, versionId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<InspectionStandardItemResponse> items(long tenantId, long versionId) {
    return jdbcTemplate.query("""
        select id, dimension, code, title, description, check_method, suggested_score,
               risk_level, red_line, enabled, sort_order
        from inspection_standard_item
        where tenant_id = ? and standard_version_id = ?
        order by sort_order, id
        """, this::mapItem, tenantId, versionId);
  }

  private VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Date effectiveDate = rs.getDate("effective_date");
    return new VersionRow(
        rs.getLong("id"),
        rs.getString("title"),
        amount(rs.getBigDecimal("full_score")),
        amount(rs.getBigDecimal("pass_score")),
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
        rs.getInt("sort_order"),
        rs.getString("check_method"),
        normalizedRiskLevel(rs.getString("risk_level"), rs.getBoolean("red_line"))
    );
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
  }

  private String normalizedRiskLevel(String value, boolean redLine) {
    if (redLine) {
      return "RED";
    }
    String normalized = value == null ? "NORMAL" : value.trim().toUpperCase();
    return switch (normalized) {
      case "RED", "YELLOW" -> normalized;
      default -> "NORMAL";
    };
  }

  public record VersionRow(
      long id,
      String title,
      BigDecimal fullScore,
      BigDecimal passScore,
      String version,
      LocalDate effectiveDate
  ) {
    public VersionRow(long id, String title, BigDecimal fullScore, String version, LocalDate effectiveDate) {
      this(id, title, fullScore, BigDecimal.valueOf(180), version, effectiveDate);
    }
  }
}
