package com.storeprofit.system.employeeassistant;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Tenant-scoped persistence for approved knowledge, immutable versions, handoffs and feedback. */
@Repository
public class EmployeeAssistantKnowledgeRepository {
  private final JdbcTemplate jdbcTemplate;

  public EmployeeAssistantKnowledgeRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean hasPublishedKnowledge(long tenantId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from employee_assistant_knowledge
        where tenant_id = ? and status = 'PUBLISHED'
        """, Integer.class, tenantId);
    return count != null && count > 0;
  }

  public List<KnowledgeRow> publishedKnowledge(long tenantId) {
    return jdbcTemplate.query(knowledgeSelect() + " where tenant_id = ? and status = 'PUBLISHED' order by updated_at desc, id desc",
        this::mapKnowledge, tenantId);
  }

  public List<KnowledgeRow> listKnowledge(long tenantId) {
    return jdbcTemplate.query(knowledgeSelect() + " where tenant_id = ? order by updated_at desc, id desc",
        this::mapKnowledge, tenantId);
  }

  public Optional<KnowledgeRow> findKnowledge(long tenantId, long id) {
    return jdbcTemplate.query(knowledgeSelect() + " where tenant_id = ? and id = ?", this::mapKnowledge, tenantId, id)
        .stream().findFirst();
  }

  public long insertDraft(long tenantId, EmployeeAssistantKnowledgeDraftRequest request, long actorId) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          insert into employee_assistant_knowledge(
            tenant_id, category, title, keywords, standard_answer, status, current_version,
            created_by, updated_by, created_at, updated_at
          ) values (?, ?, ?, ?, ?, 'DRAFT', 0, ?, ?, current_timestamp, current_timestamp)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, tenantId);
      statement.setString(2, request.category());
      statement.setString(3, request.title());
      statement.setString(4, request.keywords());
      statement.setString(5, request.standardAnswer());
      statement.setLong(6, actorId);
      statement.setLong(7, actorId);
      return statement;
    }, keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("知识库草稿未生成编号");
    }
    return key.longValue();
  }

  public int updateDraft(long tenantId, long id, EmployeeAssistantKnowledgeDraftRequest request, long actorId) {
    return jdbcTemplate.update("""
        update employee_assistant_knowledge
        set category = ?, title = ?, keywords = ?, standard_answer = ?, updated_by = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'DRAFT'
        """, request.category(), request.title(), request.keywords(), request.standardAnswer(), actorId, tenantId, id);
  }

  public int publish(long tenantId, long id, int version, long actorId) {
    return jdbcTemplate.update("""
        update employee_assistant_knowledge
        set status = 'PUBLISHED', current_version = ?, updated_by = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'DRAFT'
        """, version, actorId, tenantId, id);
  }

  public int restorePublished(long tenantId, long id, KnowledgeVersionRow snapshot, int nextVersion, long actorId) {
    return jdbcTemplate.update("""
        update employee_assistant_knowledge
        set category = ?, title = ?, keywords = ?, standard_answer = ?, status = 'PUBLISHED', current_version = ?,
            updated_by = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, snapshot.category(), snapshot.title(), snapshot.keywords(), snapshot.standardAnswer(), nextVersion,
        actorId, tenantId, id);
  }

  public void insertVersion(long tenantId, KnowledgeRow row, int version, String action, long actorId) {
    jdbcTemplate.update("""
        insert into employee_assistant_knowledge_version(
          tenant_id, knowledge_id, version_no, category, title, keywords, standard_answer,
          publish_action, published_by, published_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, row.id(), version, row.category(), row.title(), row.keywords(), row.standardAnswer(),
        action, actorId);
  }

  public List<KnowledgeVersionRow> listVersions(long tenantId, long knowledgeId) {
    return jdbcTemplate.query("""
        select id, tenant_id, knowledge_id, version_no, category, title, keywords, standard_answer,
               publish_action, published_by, published_at
        from employee_assistant_knowledge_version
        where tenant_id = ? and knowledge_id = ?
        order by version_no desc
        """, this::mapVersion, tenantId, knowledgeId);
  }

  public Optional<KnowledgeVersionRow> findVersion(long tenantId, long knowledgeId, int version) {
    return jdbcTemplate.query("""
        select id, tenant_id, knowledge_id, version_no, category, title, keywords, standard_answer,
               publish_action, published_by, published_at
        from employee_assistant_knowledge_version
        where tenant_id = ? and knowledge_id = ? and version_no = ?
        """, this::mapVersion, tenantId, knowledgeId, version).stream().findFirst();
  }

  public void insertHandoff(long tenantId, String id, String storeId, String question, String category,
      long requestedBy, LocalDateTime expiresAt) {
    jdbcTemplate.update("""
        insert into employee_assistant_handoff(
          id, tenant_id, store_id, question_redacted, category, status, requested_by,
          created_at, expires_at, updated_at
        ) values (?, ?, ?, ?, ?, 'OPEN', ?, current_timestamp, ?, current_timestamp)
        """, id, tenantId, blankToNull(storeId), question, category, requestedBy, Timestamp.valueOf(expiresAt));
  }

  public List<HandoffRow> listHandoffs(long tenantId) {
    return jdbcTemplate.query(handoffSelect() + " where h.tenant_id = ? order by h.created_at desc, h.id desc",
        this::mapHandoff, tenantId);
  }

  public Optional<HandoffRow> findHandoff(long tenantId, String id) {
    return jdbcTemplate.query(handoffSelect() + " where h.tenant_id = ? and h.id = ?", this::mapHandoff, tenantId, id)
        .stream().findFirst();
  }

  public int claimHandoff(long tenantId, String id, long handlerId) {
    return jdbcTemplate.update("""
        update employee_assistant_handoff
        set status = 'CLAIMED', handled_by = ?, claimed_at = current_timestamp, updated_at = current_timestamp
        where tenant_id = ? and id = ? and status = 'OPEN' and expires_at > current_timestamp
        """, handlerId, tenantId, id);
  }

  public int replyHandoff(long tenantId, String id, long handlerId, String resolution) {
    return jdbcTemplate.update("""
        update employee_assistant_handoff
        set status = 'IN_PROGRESS', resolution = ?, responded_at = current_timestamp, updated_at = current_timestamp
        where tenant_id = ? and id = ? and handled_by = ? and status in ('CLAIMED', 'IN_PROGRESS')
        """, resolution, tenantId, id, handlerId);
  }

  public int closeHandoff(long tenantId, String id, long handlerId, String resolution) {
    return jdbcTemplate.update("""
        update employee_assistant_handoff
        set status = 'CLOSED', resolution = ?, closed_at = current_timestamp, updated_at = current_timestamp
        where tenant_id = ? and id = ? and handled_by = ? and status in ('CLAIMED', 'IN_PROGRESS')
        """, resolution, tenantId, id, handlerId);
  }

  public void insertFeedback(long tenantId, String source, Long knowledgeId, Integer knowledgeVersion,
      boolean helpful, String reasonCode, long actorId) {
    jdbcTemplate.update("""
        insert into employee_assistant_feedback(
          tenant_id, answer_source, knowledge_id, knowledge_version, helpful, reason_code, created_by, created_at
        ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, source, knowledgeId, knowledgeVersion, helpful, blankToNull(reasonCode), actorId);
  }

  private String knowledgeSelect() {
    return """
        select id, tenant_id, category, title, keywords, standard_answer, status, current_version,
               created_by, updated_by, created_at, updated_at
        from employee_assistant_knowledge
        """;
  }

  private String handoffSelect() {
    return """
        select h.id, h.tenant_id, h.store_id, h.question_redacted, h.category, h.status,
               h.requested_by, requester.display_name as requested_by_name,
               h.handled_by, handler.display_name as handled_by_name, h.resolution,
               h.created_at, h.claimed_at, h.responded_at, h.closed_at, h.expires_at
        from employee_assistant_handoff h
        left join auth_user requester on requester.tenant_id = h.tenant_id and requester.id = h.requested_by
        left join auth_user handler on handler.tenant_id = h.tenant_id and handler.id = h.handled_by
        """;
  }

  private KnowledgeRow mapKnowledge(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new KnowledgeRow(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("category"),
        rs.getString("title"), rs.getString("keywords"), rs.getString("standard_answer"), rs.getString("status"),
        rs.getInt("current_version"), rs.getLong("created_by"), rs.getLong("updated_by"),
        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
  }

  private KnowledgeVersionRow mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new KnowledgeVersionRow(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("knowledge_id"),
        rs.getInt("version_no"), rs.getString("category"), rs.getString("title"), rs.getString("keywords"),
        rs.getString("standard_answer"), rs.getString("publish_action"), rs.getLong("published_by"),
        rs.getObject("published_at", LocalDateTime.class));
  }

  private HandoffRow mapHandoff(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    long handlerId = rs.getLong("handled_by");
    Long handledBy = rs.wasNull() ? null : handlerId;
    return new HandoffRow(rs.getString("id"), rs.getLong("tenant_id"), rs.getString("store_id"),
        rs.getString("question_redacted"), rs.getString("category"), rs.getString("status"),
        rs.getLong("requested_by"), rs.getString("requested_by_name"), handledBy, rs.getString("handled_by_name"),
        rs.getString("resolution"), rs.getObject("created_at", LocalDateTime.class),
        rs.getObject("claimed_at", LocalDateTime.class), rs.getObject("responded_at", LocalDateTime.class),
        rs.getObject("closed_at", LocalDateTime.class), rs.getObject("expires_at", LocalDateTime.class));
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  record KnowledgeRow(long id, long tenantId, String category, String title, String keywords, String standardAnswer,
                      String status, int currentVersion, long createdBy, long updatedBy, LocalDateTime createdAt,
                      LocalDateTime updatedAt) {
  }

  record KnowledgeVersionRow(long id, long tenantId, long knowledgeId, int version, String category, String title,
                             String keywords, String standardAnswer, String publishAction, long publishedBy,
                             LocalDateTime publishedAt) {
  }

  record HandoffRow(String id, long tenantId, String storeId, String question, String category, String status,
                    long requestedBy, String requestedByName, Long handledBy, String handledByName, String resolution,
                    LocalDateTime createdAt, LocalDateTime claimedAt, LocalDateTime respondedAt,
                    LocalDateTime closedAt, LocalDateTime expiresAt) {
  }
}
