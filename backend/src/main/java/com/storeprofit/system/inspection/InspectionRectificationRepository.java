package com.storeprofit.system.inspection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists only the rectification workflow.  It intentionally has no update path to
 * inspection_record or inspection_record_standard_snapshot.
 */
@Repository
public class InspectionRectificationRepository {
  private static final String RECTIFICATION_ATTACHMENT_TYPE = "INSPECTION_RECTIFICATION";

  private final NamedParameterJdbcTemplate jdbc;

  public InspectionRectificationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<InspectionRectificationRecord> find(long tenantId, String inspectionRecordId) {
    return jdbc.query(selectSql(false), params(tenantId, inspectionRecordId), this::mapRecord)
        .stream()
        .findFirst();
  }

  public Optional<InspectionRectificationRecord> findForUpdate(long tenantId, String inspectionRecordId) {
    return jdbc.query(selectSql(true), params(tenantId, inspectionRecordId), this::mapRecord)
        .stream()
        .findFirst();
  }

  public InspectionRectificationRecord create(
      long tenantId,
      String id,
      String inspectionRecordId,
      String storeId
  ) {
    jdbc.update("""
        insert into inspection_rectification(
          id, tenant_id, inspection_record_id, store_id, status, version, created_at, updated_at
        ) values (
          :id, :tenantId, :inspectionRecordId, :storeId, 'PENDING_SUBMISSION', 0,
          current_timestamp, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId)
            .addValue("inspectionRecordId", inspectionRecordId)
            .addValue("storeId", storeId));
    return findForUpdate(tenantId, inspectionRecordId)
        .orElseThrow(() -> new IllegalStateException("Inspection rectification was not created"));
  }

  public boolean submit(
      long tenantId,
      String inspectionRecordId,
      String note,
      Long submittedBy,
      String submittedByName
  ) {
    return jdbc.update("""
        update inspection_rectification
        set status = 'PENDING_REVIEW',
            manager_note = :note,
            submitted_by = :submittedBy,
            submitted_by_name = :submittedByName,
            submitted_at = current_timestamp,
            review_note = null,
            reviewed_by = null,
            reviewed_by_name = null,
            reviewed_at = null,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = :tenantId
          and inspection_record_id = :inspectionRecordId
          and status in ('PENDING_SUBMISSION', 'REJECTED')
        """,
        params(tenantId, inspectionRecordId)
            .addValue("note", note)
            .addValue("submittedBy", submittedBy)
            .addValue("submittedByName", submittedByName)) > 0;
  }

  public boolean review(
      long tenantId,
      String inspectionRecordId,
      InspectionRectificationStatus decision,
      String note,
      Long reviewedBy,
      String reviewedByName
  ) {
    return jdbc.update("""
        update inspection_rectification
        set status = :status,
            review_note = :note,
            reviewed_by = :reviewedBy,
            reviewed_by_name = :reviewedByName,
            reviewed_at = current_timestamp,
            version = version + 1,
            updated_at = current_timestamp
        where tenant_id = :tenantId
          and inspection_record_id = :inspectionRecordId
          and status = 'PENDING_REVIEW'
        """,
        params(tenantId, inspectionRecordId)
            .addValue("status", decision.name())
            .addValue("note", note)
            .addValue("reviewedBy", reviewedBy)
            .addValue("reviewedByName", reviewedByName)) > 0;
  }

  public List<InspectionRectificationRecord> pendingReview(
      long tenantId,
      boolean allStores,
      List<String> storeIds
  ) {
    if (!allStores && (storeIds == null || storeIds.isEmpty())) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder("""
        select id, tenant_id, inspection_record_id, store_id, status,
               manager_note, submitted_by, submitted_by_name, submitted_at,
               review_note, reviewed_by, reviewed_by_name, reviewed_at,
               version, created_at, updated_at
        from inspection_rectification
        where tenant_id = :tenantId and status = 'PENDING_REVIEW'
        """);
    MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId);
    if (!allStores) {
      sql.append(" and store_id in (:storeIds)");
      parameters.addValue("storeIds", storeIds);
    }
    sql.append(" order by updated_at asc, id asc");
    return jdbc.query(sql.toString(), parameters, this::mapRecord);
  }

  public List<Long> evidenceAttachmentIds(long tenantId, String rectificationId, String storeId) {
    return jdbc.query("""
        select id
        from warehouse_attachment
        where tenant_id = :tenantId
          and store_id = :storeId
          and business_type = :businessType
          and business_id = :rectificationId
        order by uploaded_at asc, id asc
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("storeId", storeId)
            .addValue("businessType", RECTIFICATION_ATTACHMENT_TYPE)
            .addValue("rectificationId", rectificationId),
        (rs, rowNum) -> rs.getLong("id"));
  }

  public boolean ownsEvidenceAttachments(
      long tenantId,
      String rectificationId,
      String storeId,
      List<Long> attachmentIds
  ) {
    if (attachmentIds == null || attachmentIds.isEmpty()) {
      return false;
    }
    Integer count = jdbc.queryForObject("""
        select count(*)
        from warehouse_attachment
        where tenant_id = :tenantId
          and store_id = :storeId
          and business_type = :businessType
          and business_id = :rectificationId
          and id in (:attachmentIds)
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("storeId", storeId)
            .addValue("businessType", RECTIFICATION_ATTACHMENT_TYPE)
            .addValue("rectificationId", rectificationId)
            .addValue("attachmentIds", attachmentIds),
        Integer.class);
    return count != null && count == attachmentIds.size();
  }

  public void saveAction(InspectionRectificationAction action) {
    jdbc.update("""
        insert into inspection_rectification_action(
          id, tenant_id, rectification_id, inspection_record_id, action, status, note,
          actor_user_id, actor_name, actor_role, created_at
        ) values (
          :id, :tenantId, :rectificationId, :inspectionRecordId, :action, :status, :note,
          :actorUserId, :actorName, :actorRole, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("id", action.id())
            .addValue("tenantId", action.tenantId())
            .addValue("rectificationId", action.rectificationId())
            .addValue("inspectionRecordId", action.inspectionRecordId())
            .addValue("action", action.action())
            .addValue("status", action.status().name())
            .addValue("note", action.note())
            .addValue("actorUserId", action.actorUserId())
            .addValue("actorName", action.actorName())
            .addValue("actorRole", action.actorRole()));
  }

  public void logOperation(
      long tenantId,
      Long operatorId,
      String operatorName,
      String action,
      String inspectionRecordId,
      String storeId,
      String inspectionDate,
      String reason
  ) {
    jdbc.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, reason, created_at
        ) values (
          :tenantId, :operatorId, :operatorName, :action, 'inspection_rectification', :targetId,
          :storeId, :month, :reason, current_timestamp
        )
        """,
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("operatorId", operatorId)
            .addValue("operatorName", operatorName)
            .addValue("action", action)
            .addValue("targetId", inspectionRecordId)
            .addValue("storeId", storeId)
            .addValue("month", monthOf(inspectionDate))
            .addValue("reason", reason));
  }

  private String selectSql(boolean forUpdate) {
    return """
        select id, tenant_id, inspection_record_id, store_id, status,
               manager_note, submitted_by, submitted_by_name, submitted_at,
               review_note, reviewed_by, reviewed_by_name, reviewed_at,
               version, created_at, updated_at
        from inspection_rectification
        where tenant_id = :tenantId and inspection_record_id = :inspectionRecordId
        """ + (forUpdate ? " for update" : "");
  }

  private MapSqlParameterSource params(long tenantId, String inspectionRecordId) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId)
        .addValue("inspectionRecordId", inspectionRecordId);
  }

  private InspectionRectificationRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
    return new InspectionRectificationRecord(
        rs.getString("id"),
        rs.getLong("tenant_id"),
        rs.getString("inspection_record_id"),
        rs.getString("store_id"),
        InspectionRectificationStatus.valueOf(rs.getString("status")),
        rs.getString("manager_note"),
        nullableLong(rs, "submitted_by"),
        rs.getString("submitted_by_name"),
        timestamp(rs.getTimestamp("submitted_at")),
        rs.getString("review_note"),
        nullableLong(rs, "reviewed_by"),
        rs.getString("reviewed_by_name"),
        timestamp(rs.getTimestamp("reviewed_at")),
        rs.getLong("version"),
        timestamp(rs.getTimestamp("created_at")),
        timestamp(rs.getTimestamp("updated_at"))
    );
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private String timestamp(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toString();
  }

  private String monthOf(String inspectionDate) {
    return inspectionDate != null && inspectionDate.length() >= 7 ? inspectionDate.substring(0, 7) : null;
  }

  public record InspectionRectificationRecord(
      String id,
      long tenantId,
      String inspectionRecordId,
      String storeId,
      InspectionRectificationStatus status,
      String managerNote,
      Long submittedBy,
      String submittedByName,
      String submittedAt,
      String reviewNote,
      Long reviewedBy,
      String reviewedByName,
      String reviewedAt,
      long version,
      String createdAt,
      String updatedAt
  ) {
  }

  public record InspectionRectificationAction(
      String id,
      long tenantId,
      String rectificationId,
      String inspectionRecordId,
      String action,
      InspectionRectificationStatus status,
      String note,
      Long actorUserId,
      String actorName,
      String actorRole
  ) {
  }
}
