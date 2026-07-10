package com.storeprofit.system.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.operations.ExamCenterModels.ExamAggregate;
import com.storeprofit.system.operations.ExamCenterModels.ExamAssignmentResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCampaignResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCandidateResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperEditorResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperSummaryResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamQuestionEditorResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamQuestionSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamStoreAggregate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ExamCenterRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExamCenterRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public List<ExamPaperSummaryResponse> paperSummaries(long tenantId, boolean includeDisabled) {
    String enabledFilter = includeDisabled ? "" : " and p.enabled = 1";
    return jdbcTemplate.query("""
        select p.id, p.paper_code, p.paper_name, p.role_scope, p.pass_score, p.enabled,
               count(q.id) as question_count
        from training_exam_paper p
        left join training_exam_question q
          on q.tenant_id = p.tenant_id and q.paper_id = p.id and q.enabled = 1
        where p.tenant_id = ?
        """ + enabledFilter + "\n" + """
        group by p.id, p.paper_code, p.paper_name, p.role_scope, p.pass_score, p.enabled
        order by p.updated_at desc, p.id desc
        """, (rs, rowNum) -> new ExamPaperSummaryResponse(
        rs.getLong("id"),
        rs.getString("paper_code"),
        rs.getString("paper_name"),
        rs.getString("role_scope"),
        amount(rs.getBigDecimal("pass_score")),
        rs.getBoolean("enabled"),
        rs.getInt("question_count")
    ), tenantId);
  }

  public Optional<ExamPaperEditorResponse> paperForEdit(long tenantId, long paperId) {
    try {
      ExamPaperEditorResponse paper = jdbcTemplate.queryForObject("""
          select id, paper_code, paper_name, role_scope, pass_score, enabled
          from training_exam_paper
          where tenant_id = ? and id = ?
          """, (rs, rowNum) -> new ExamPaperEditorResponse(
          rs.getLong("id"),
          rs.getString("paper_code"),
          rs.getString("paper_name"),
          rs.getString("role_scope"),
          amount(rs.getBigDecimal("pass_score")),
          rs.getBoolean("enabled"),
          questionsForEdit(tenantId, paperId)
      ), tenantId, paperId);
      return Optional.ofNullable(paper);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<ExamQuestionEditorResponse> questionsForEdit(long tenantId, long paperId) {
    return jdbcTemplate.query("""
        select id, question_type, question_text, options_json, standard_answer,
               accept_keywords, score, sort_order
        from training_exam_question
        where tenant_id = ? and paper_id = ? and enabled = 1
        order by sort_order, id
        """, (rs, rowNum) -> new ExamQuestionEditorResponse(
        rs.getLong("id"),
        rs.getString("question_type"),
        rs.getString("question_text"),
        parseList(rs.getString("options_json")),
        rs.getString("standard_answer"),
        rs.getString("accept_keywords"),
        amount(rs.getBigDecimal("score")),
        rs.getInt("sort_order")
    ), tenantId, paperId);
  }

  public long insertPaper(
      long tenantId,
      String code,
      String name,
      String roleScope,
      BigDecimal passScore,
      boolean enabled
  ) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_exam_paper(
            tenant_id, paper_code, paper_name, role_scope, pass_score, enabled, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"}); // 显式只取 id：H2 会把所有 default 列都当 generated key 返回，getKey() 会炸
      ps.setLong(1, tenantId);
      ps.setString(2, code);
      ps.setString(3, name);
      ps.setString(4, blankToNull(roleScope));
      ps.setBigDecimal(5, amount(passScore));
      ps.setBoolean(6, enabled);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updatePaper(
      long tenantId,
      long paperId,
      String code,
      String name,
      String roleScope,
      BigDecimal passScore,
      boolean enabled
  ) {
    return jdbcTemplate.update("""
        update training_exam_paper
        set paper_code = ?, paper_name = ?, role_scope = ?, pass_score = ?, enabled = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, code, name, blankToNull(roleScope), amount(passScore), enabled, tenantId, paperId) > 0;
  }

  public int attemptCount(long tenantId, long paperId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*) from training_exam_attempt where tenant_id = ? and paper_id = ?
        """, Integer.class, tenantId, paperId);
    return count == null ? 0 : count;
  }

  public void replaceQuestions(long tenantId, long paperId, List<ExamQuestionSaveRequest> questions) {
    jdbcTemplate.update(
        "delete from training_exam_question where tenant_id = ? and paper_id = ?",
        tenantId,
        paperId
    );
    int sortOrder = 1;
    for (ExamQuestionSaveRequest question : questions) {
      jdbcTemplate.update("""
          insert into training_exam_question(
            tenant_id, paper_id, question_type, question_text, options_json, standard_answer,
            accept_keywords, score, sort_order, enabled, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, current_timestamp, current_timestamp)
          """,
          tenantId,
          paperId,
          question.questionType(),
          question.questionText(),
          writeList(question.options()),
          question.standardAnswer(),
          blankToNull(question.acceptKeywords()),
          amount(question.score()),
          sortOrder++
      );
    }
  }

  public List<ExamCandidateResponse> candidates(long tenantId) {
    return jdbcTemplate.query("""
        select u.id, u.display_name, u.role,
               coalesce(u.store_id, (
                 select min(scope.store_id)
                 from user_store_scope scope
                 where scope.tenant_id = u.tenant_id and scope.user_id = u.id
               )) as assignment_store_id,
               s.name as store_name
        from auth_user u
        left join store_branch s
          on s.tenant_id = u.tenant_id
         and s.id = coalesce(u.store_id, (
           select min(scope.store_id)
           from user_store_scope scope
           where scope.tenant_id = u.tenant_id and scope.user_id = u.id
         ))
        where u.tenant_id = ?
          and u.enabled = 1
          and u.role in ('EMPLOYEE', 'STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'FINANCE')
          and coalesce(u.store_id, (
            select min(scope.store_id)
            from user_store_scope scope
            where scope.tenant_id = u.tenant_id and scope.user_id = u.id
          )) is not null
        order by s.name, u.display_name, u.id
        """, (rs, rowNum) -> new ExamCandidateResponse(
        rs.getLong("id"),
        rs.getString("display_name"),
        rs.getString("role"),
        roleLabel(rs.getString("role")),
        rs.getString("assignment_store_id"),
        rs.getString("store_name")
    ), tenantId);
  }

  public long insertCampaign(
      long tenantId,
      long paperId,
      String title,
      LocalDateTime startAt,
      LocalDateTime dueAt,
      String targetRoles,
      long userId
  ) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_exam_campaign(
            tenant_id, paper_id, title, status, start_at, due_at, target_roles,
            created_by, published_by, published_at, created_at, updated_at
          ) values (?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp)
          """, new String[]{"id"}); // 显式只取 id：H2 会把所有 default 列都当 generated key 返回，getKey() 会炸
      ps.setLong(1, tenantId);
      ps.setLong(2, paperId);
      ps.setString(3, title);
      ps.setTimestamp(4, Timestamp.valueOf(startAt));
      ps.setTimestamp(5, Timestamp.valueOf(dueAt));
      ps.setString(6, blankToNull(targetRoles));
      ps.setLong(7, userId);
      ps.setLong(8, userId);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  public void insertAssignments(
      long tenantId,
      long campaignId,
      LocalDateTime dueAt,
      List<ExamCandidateResponse> candidates
  ) {
    for (ExamCandidateResponse candidate : candidates) {
      jdbcTemplate.update("""
          insert into training_exam_assignment(
            tenant_id, campaign_id, user_id, examinee_name, examinee_role,
            store_id, store_name, status, assigned_at, due_at, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', current_timestamp, ?, current_timestamp, current_timestamp)
          """,
          tenantId,
          campaignId,
          candidate.userId(),
          candidate.displayName(),
          candidate.role(),
          candidate.storeId(),
          candidate.storeName(),
          Timestamp.valueOf(dueAt)
      );
    }
  }

  public List<ExamCampaignResponse> campaigns(long tenantId, String storeId, Long userId) {
    String assignmentJoin = "";
    String scopeFilter = "";
    List<Object> args = new ArrayList<>();
    if (userId != null) {
      assignmentJoin = " and a.user_id = ?";
      args.add(userId);
      scopeFilter = " and exists (select 1 from training_exam_assignment own where own.tenant_id = c.tenant_id and own.campaign_id = c.id and own.user_id = ?)";
    } else if (storeId != null) {
      assignmentJoin = " and a.store_id = ?";
      args.add(storeId);
      scopeFilter = " and exists (select 1 from training_exam_assignment scoped where scoped.tenant_id = c.tenant_id and scoped.campaign_id = c.id and scoped.store_id = ?)";
    }
    args.add(tenantId);
    if (userId != null) {
      args.add(userId);
    } else if (storeId != null) {
      args.add(storeId);
    }
    String sql = """
        select c.id, c.paper_id, p.paper_name, c.title, c.status,
               date_format(c.start_at, '%Y-%m-%d %H:%i:%s') as start_at,
               date_format(c.due_at, '%Y-%m-%d %H:%i:%s') as due_at,
               c.target_roles,
               count(a.id) as assigned_count,
               coalesce(sum(case when a.completed_at is not null then 1 else 0 end), 0) as completed_count,
               coalesce(sum(case when a.completed_at is not null and a.passed = 1 then 1 else 0 end), 0) as passed_count,
               coalesce(sum(case when a.completed_at is null and c.due_at < current_timestamp then 1 else 0 end), 0) as overdue_count,
               coalesce(avg(case when a.completed_at is not null then a.score end), 0) as average_score,
               date_format(c.published_at, '%Y-%m-%d %H:%i:%s') as published_at
        from training_exam_campaign c
        join training_exam_paper p on p.tenant_id = c.tenant_id and p.id = c.paper_id
        left join training_exam_assignment a
          on a.tenant_id = c.tenant_id and a.campaign_id = c.id
        """ + assignmentJoin + """
        where c.tenant_id = ?
        """ + scopeFilter + """
        group by c.id, c.paper_id, p.paper_name, c.title, c.status, c.start_at, c.due_at,
                 c.target_roles, c.published_at
        order by c.published_at desc, c.id desc
        limit 300
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> {
      int assigned = rs.getInt("assigned_count");
      int completed = rs.getInt("completed_count");
      int passed = rs.getInt("passed_count");
      return new ExamCampaignResponse(
          rs.getLong("id"),
          rs.getLong("paper_id"),
          rs.getString("paper_name"),
          rs.getString("title"),
          rs.getString("status"),
          campaignStatusLabel(rs.getString("status"), rs.getString("start_at"), rs.getString("due_at")),
          rs.getString("start_at"),
          rs.getString("due_at"),
          rs.getString("target_roles"),
          assigned,
          completed,
          rate(completed, assigned),
          passed,
          rate(passed, completed),
          rs.getInt("overdue_count"),
          amount(rs.getBigDecimal("average_score")),
          rs.getString("published_at")
      );
    }, args.toArray());
  }

  public Optional<ExamCampaignResponse> campaign(long tenantId, long campaignId, String storeId, Long userId) {
    return campaigns(tenantId, storeId, userId).stream()
        .filter(campaign -> campaign.id() == campaignId)
        .findFirst();
  }

  public List<ExamAssignmentResponse> assignments(long tenantId, String storeId, Long userId) {
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (storeId != null) {
      where.append(" and a.store_id = ?");
      args.add(storeId);
    }
    if (userId != null) {
      where.append(" and a.user_id = ?");
      args.add(userId);
    }
    String sql = assignmentSelect() + where + " order by c.due_at desc, a.id desc limit 1000";
    return jdbcTemplate.query(sql, this::mapAssignment, args.toArray());
  }

  public List<ExamAssignmentResponse> assignmentsForCampaign(
      long tenantId,
      long campaignId,
      String storeId,
      Long userId
  ) {
    StringBuilder where = new StringBuilder(" where a.tenant_id = ? and a.campaign_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    args.add(campaignId);
    if (storeId != null) {
      where.append(" and a.store_id = ?");
      args.add(storeId);
    }
    if (userId != null) {
      where.append(" and a.user_id = ?");
      args.add(userId);
    }
    return jdbcTemplate.query(assignmentSelect() + where + " order by a.store_name, a.examinee_name, a.id", this::mapAssignment, args.toArray());
  }

  public Optional<ExamAssignmentResponse> assignment(long tenantId, long assignmentId, boolean forUpdate) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject(
          assignmentSelect() + " where a.tenant_id = ? and a.id = ?" + (forUpdate ? " for update" : ""),
          this::mapAssignment,
          tenantId,
          assignmentId
      ));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public boolean completeAssignment(
      long tenantId,
      long assignmentId,
      long attemptId,
      BigDecimal score,
      boolean passed
  ) {
    return jdbcTemplate.update("""
        update training_exam_assignment
        set status = 'COMPLETED', completed_at = current_timestamp, attempt_id = ?, score = ?,
            passed = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ? and completed_at is null
        """, attemptId, amount(score), passed, tenantId, assignmentId) > 0;
  }

  public ExamAggregate aggregate(long tenantId) {
    return jdbcTemplate.queryForObject("""
        select count(distinct case
                 when c.status = 'PUBLISHED' and c.start_at <= current_timestamp and c.due_at >= current_timestamp
                 then c.id end) as active_exam_count,
               count(a.id) as assigned_count,
               coalesce(sum(case when a.completed_at is not null then 1 else 0 end), 0) as completed_count,
               coalesce(sum(case when a.completed_at is not null and a.passed = 1 then 1 else 0 end), 0) as passed_count,
               coalesce(sum(case when a.completed_at is null and c.due_at < current_timestamp then 1 else 0 end), 0) as overdue_count,
               coalesce(avg(case when a.completed_at is not null then a.score end), 0) as average_score
        from training_exam_campaign c
        left join training_exam_assignment a
          on a.tenant_id = c.tenant_id and a.campaign_id = c.id
        where c.tenant_id = ? and c.status = 'PUBLISHED'
        """, (rs, rowNum) -> new ExamAggregate(
        rs.getInt("active_exam_count"),
        rs.getInt("assigned_count"),
        rs.getInt("completed_count"),
        rs.getInt("passed_count"),
        rs.getInt("overdue_count"),
        amount(rs.getBigDecimal("average_score"))
    ), tenantId);
  }

  public List<ExamStoreAggregate> storeAggregates(long tenantId) {
    return jdbcTemplate.query("""
        select a.store_id, a.store_name, count(a.id) as assigned_count,
               coalesce(sum(case when a.completed_at is not null then 1 else 0 end), 0) as completed_count,
               coalesce(sum(case when a.completed_at is not null and a.passed = 1 then 1 else 0 end), 0) as passed_count,
               coalesce(sum(case when a.completed_at is null and c.due_at < current_timestamp then 1 else 0 end), 0) as overdue_count,
               coalesce(avg(case when a.completed_at is not null then a.score end), 0) as average_score
        from training_exam_assignment a
        join training_exam_campaign c
          on c.tenant_id = a.tenant_id and c.id = a.campaign_id
        where a.tenant_id = ? and c.status = 'PUBLISHED'
        group by a.store_id, a.store_name
        order by overdue_count desc, completed_count asc, a.store_name
        """, (rs, rowNum) -> new ExamStoreAggregate(
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getInt("assigned_count"),
        rs.getInt("completed_count"),
        rs.getInt("passed_count"),
        rs.getInt("overdue_count"),
        amount(rs.getBigDecimal("average_score"))
    ), tenantId);
  }

  private String assignmentSelect() {
    return """
        select a.id, a.campaign_id, c.paper_id, c.title as exam_title, p.paper_name,
               a.user_id, a.examinee_name, a.examinee_role, a.store_id, a.store_name,
               case
                 when a.completed_at is not null then 'COMPLETED'
                 when c.due_at < current_timestamp then 'OVERDUE'
                 when c.start_at > current_timestamp then 'NOT_STARTED'
                 else 'ASSIGNED'
               end as resolved_status,
               date_format(c.start_at, '%Y-%m-%d %H:%i:%s') as start_at,
               date_format(c.due_at, '%Y-%m-%d %H:%i:%s') as due_at,
               date_format(a.completed_at, '%Y-%m-%d %H:%i:%s') as completed_at,
               a.attempt_id, a.score, a.passed
        from training_exam_assignment a
        join training_exam_campaign c
          on c.tenant_id = a.tenant_id and c.id = a.campaign_id
        join training_exam_paper p
          on p.tenant_id = c.tenant_id and p.id = c.paper_id
        """;
  }

  private ExamAssignmentResponse mapAssignment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String status = rs.getString("resolved_status");
    long attemptValue = rs.getLong("attempt_id");
    Long attemptId = rs.wasNull() ? null : attemptValue;
    BigDecimal score = rs.getBigDecimal("score");
    boolean passedValue = rs.getBoolean("passed");
    Boolean passed = rs.wasNull() ? null : passedValue;
    return new ExamAssignmentResponse(
        rs.getLong("id"),
        rs.getLong("campaign_id"),
        rs.getLong("paper_id"),
        rs.getString("exam_title"),
        rs.getString("paper_name"),
        rs.getLong("user_id"),
        rs.getString("examinee_name"),
        rs.getString("examinee_role"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        status,
        assignmentStatusLabel(status),
        rs.getString("start_at"),
        rs.getString("due_at"),
        rs.getString("completed_at"),
        attemptId,
        score == null ? null : amount(score),
        passed
    );
  }

  private List<String> parseList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST);
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  private String writeList(List<String> values) {
    try {
      return objectMapper.writeValueAsString(values == null ? List.of() : values);
    } catch (Exception ex) {
      throw new IllegalArgumentException("题目选项格式不正确", ex);
    }
  }

  private String campaignStatusLabel(String status, String startAt, String dueAt) {
    if (!"PUBLISHED".equals(status)) {
      return "CLOSED".equals(status) ? "已结束" : status;
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime start = LocalDateTime.parse(startAt.replace(' ', 'T'));
    LocalDateTime due = LocalDateTime.parse(dueAt.replace(' ', 'T'));
    if (now.isBefore(start)) {
      return "未开始";
    }
    if (now.isAfter(due)) {
      return "已逾期";
    }
    return "进行中";
  }

  private String assignmentStatusLabel(String status) {
    return switch (status) {
      case "ASSIGNED" -> "待参加";
      case "NOT_STARTED" -> "未开始";
      case "COMPLETED" -> "已完成";
      case "OVERDUE" -> "已逾期";
      default -> status;
    };
  }

  private String roleLabel(String role) {
    return switch (role == null ? "" : role) {
      case "EMPLOYEE" -> "员工";
      case "STORE_MANAGER" -> "店长";
      case "SUPERVISOR" -> "督导";
      case "WAREHOUSE" -> "仓库管理员";
      case "FINANCE" -> "财务";
      default -> role;
    };
  }

  private BigDecimal rate(int numerator, int denominator) {
    if (denominator <= 0) {
      return ZERO;
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
