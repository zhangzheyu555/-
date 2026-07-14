package com.storeprofit.system.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAnswerResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamQuestionResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OperationsBusinessRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public OperationsBusinessRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
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

  public List<InventoryCheckResponse> inventoryChecks(long tenantId, String storeId) {
    return inventoryChecks(tenantId, storeId, null);
  }

  public List<InventoryCheckResponse> inventoryChecks(
      long tenantId,
      String storeId,
      Collection<String> allowedStoreIds
  ) {
    StringBuilder sql = new StringBuilder("""
        select id, check_no, store_id, store_name, date_format(check_date, '%Y-%m-%d') as check_date,
               status, total_amount, submitted_by, reviewed_by,
               date_format(reviewed_at, '%Y-%m-%d %H:%i:%s') as reviewed_at,
               note, date_format(created_at, '%Y-%m-%d %H:%i:%s') as created_at,
               date_format(updated_at, '%Y-%m-%d %H:%i:%s') as updated_at
        from store_inventory_check
        where tenant_id = ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and store_id = ?");
      args.add(storeId.trim());
    } else {
      appendAllowedStoreScope(sql, args, "store_id", allowedStoreIds);
    }
    sql.append(" order by check_date desc, id desc limit 200");
    return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new InventoryCheckResponse(
        rs.getLong("id"),
        rs.getString("check_no"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        rs.getString("check_date"),
        rs.getString("status"),
        statusLabel(rs.getString("status")),
        amount(rs.getBigDecimal("total_amount")),
        boxedLong(rs.getLong("submitted_by"), rs.wasNull()),
        boxedLong(rs.getLong("reviewed_by"), rs.wasNull()),
        rs.getString("reviewed_at"),
        rs.getString("note"),
        rs.getString("created_at"),
        rs.getString("updated_at"),
        List.of()
    ), args.toArray());
  }

  public Optional<InventoryCheckResponse> inventoryCheck(long tenantId, long id) {
    try {
      InventoryCheckResponse header = jdbcTemplate.queryForObject("""
          select id, check_no, store_id, store_name, date_format(check_date, '%Y-%m-%d') as check_date,
                 status, total_amount, submitted_by, reviewed_by,
                 date_format(reviewed_at, '%Y-%m-%d %H:%i:%s') as reviewed_at,
                 note, date_format(created_at, '%Y-%m-%d %H:%i:%s') as created_at,
                 date_format(updated_at, '%Y-%m-%d %H:%i:%s') as updated_at
          from store_inventory_check
          where tenant_id = ? and id = ?
          """, (rs, rowNum) -> new InventoryCheckResponse(
          rs.getLong("id"),
          rs.getString("check_no"),
          rs.getString("store_id"),
          rs.getString("store_name"),
          rs.getString("check_date"),
          rs.getString("status"),
          statusLabel(rs.getString("status")),
          amount(rs.getBigDecimal("total_amount")),
          boxedLong(rs.getLong("submitted_by"), rs.wasNull()),
          boxedLong(rs.getLong("reviewed_by"), rs.wasNull()),
          rs.getString("reviewed_at"),
          rs.getString("note"),
          rs.getString("created_at"),
          rs.getString("updated_at"),
          inventoryCheckLines(tenantId, id)
      ), tenantId, id);
      return Optional.ofNullable(header);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<InventoryCheckLineResponse> inventoryCheckLines(long tenantId, long checkId) {
    return jdbcTemplate.query("""
        select id, item_name, item_code, category, spec, unit, package_quantity,
               unit_price, unit_price_each, counted_quantity, amount, note
        from store_inventory_check_line
        where tenant_id = ? and check_id = ?
        order by id
        """, (rs, rowNum) -> new InventoryCheckLineResponse(
        rs.getLong("id"),
        rs.getString("item_name"),
        rs.getString("item_code"),
        rs.getString("category"),
        rs.getString("spec"),
        rs.getString("unit"),
        amount(rs.getBigDecimal("package_quantity")),
        amount(rs.getBigDecimal("unit_price")),
        amount(rs.getBigDecimal("unit_price_each")),
        amount(rs.getBigDecimal("counted_quantity")),
        amount(rs.getBigDecimal("amount")),
        rs.getString("note")
    ), tenantId, checkId);
  }

  public long saveInventoryCheck(
      long tenantId,
      Long id,
      String checkNo,
      String storeId,
      String storeName,
      String checkDate,
      String note,
      BigDecimal totalAmount,
      long userId,
      List<InventoryCheckLineRequest> lines
  ) {
    long checkId = id == null
        ? insertInventoryCheck(tenantId, checkNo, storeId, storeName, checkDate, note, totalAmount, userId)
        : updateInventoryCheck(tenantId, id, storeId, storeName, checkDate, note, totalAmount);
    jdbcTemplate.update("delete from store_inventory_check_line where tenant_id = ? and check_id = ?", tenantId, checkId);
    for (InventoryCheckLineRequest line : lines) {
      insertInventoryCheckLine(tenantId, checkId, line);
    }
    return checkId;
  }

  private long insertInventoryCheck(
      long tenantId,
      String checkNo,
      String storeId,
      String storeName,
      String checkDate,
      String note,
      BigDecimal totalAmount,
      long userId
  ) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into store_inventory_check(
            tenant_id, check_no, store_id, store_name, check_date, status,
            total_amount, note, created_by, created_at, updated_at
          )
          values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"}); // 显式只取 id：H2 会把所有 default 列都当 generated key 返回，getKey() 会炸
      ps.setLong(1, tenantId);
      ps.setString(2, checkNo);
      ps.setString(3, storeId);
      ps.setString(4, storeName);
      ps.setDate(5, Date.valueOf(checkDate));
      ps.setBigDecimal(6, amount(totalAmount));
      ps.setString(7, blankToNull(note));
      ps.setLong(8, userId);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0 : key.longValue();
  }

  private long updateInventoryCheck(
      long tenantId,
      long id,
      String storeId,
      String storeName,
      String checkDate,
      String note,
      BigDecimal totalAmount
  ) {
    jdbcTemplate.update("""
        update store_inventory_check
        set store_id = ?, store_name = ?, check_date = ?, total_amount = ?,
            note = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, storeId, storeName, Date.valueOf(checkDate), amount(totalAmount), blankToNull(note), tenantId, id);
    return id;
  }

  private void insertInventoryCheckLine(long tenantId, long checkId, InventoryCheckLineRequest line) {
    BigDecimal counted = amount(line.countedQuantity());
    BigDecimal price = amount(line.unitPrice());
    BigDecimal lineAmount = counted.multiply(price).setScale(2, RoundingMode.HALF_UP);
    jdbcTemplate.update("""
        insert into store_inventory_check_line(
          tenant_id, check_id, item_name, item_code, category, spec, unit,
          package_quantity, unit_price, unit_price_each, counted_quantity,
          amount, note, created_at, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
        """,
        tenantId,
        checkId,
        required(line.itemName(), "未命名物品"),
        blankToNull(line.itemCode()),
        blankToNull(line.category()),
        blankToNull(line.spec()),
        blankToNull(line.unit()),
        amount(line.packageQuantity()),
        price,
        amount(line.unitPriceEach()),
        counted,
        lineAmount,
        blankToNull(line.note())
    );
  }

  public boolean updateInventoryCheckStatus(long tenantId, long id, String expectedStatus, String nextStatus, Long userId) {
    int affected;
    if ("SUBMITTED".equals(nextStatus)) {
      affected = jdbcTemplate.update("""
          update store_inventory_check
          set status = ?, submitted_by = ?, updated_at = current_timestamp
          where tenant_id = ? and id = ? and status = ?
          """, nextStatus, userId, tenantId, id, expectedStatus);
    } else if ("REVIEWED".equals(nextStatus)) {
      affected = jdbcTemplate.update("""
          update store_inventory_check
          set status = ?, reviewed_by = ?, reviewed_at = current_timestamp, updated_at = current_timestamp
          where tenant_id = ? and id = ? and status = ?
          """, nextStatus, userId, tenantId, id, expectedStatus);
    } else {
      affected = jdbcTemplate.update("""
          update store_inventory_check
          set status = ?, updated_at = current_timestamp
          where tenant_id = ? and id = ? and status <> 'REVIEWED'
          """, nextStatus, tenantId, id);
    }
    return affected > 0;
  }

  public List<ExamPaperResponse> examPapers(long tenantId) {
    return jdbcTemplate.query("""
        select id, paper_code, paper_name, role_scope, pass_score, enabled
        from training_exam_paper
        where tenant_id = ? and enabled = 1
        order by id
        """, (rs, rowNum) -> new ExamPaperResponse(
        rs.getLong("id"),
        rs.getString("paper_code"),
        rs.getString("paper_name"),
        rs.getString("role_scope"),
        amount(rs.getBigDecimal("pass_score")),
        rs.getBoolean("enabled"),
        List.of()
    ), tenantId);
  }

  public Optional<ExamPaperResponse> examPaper(long tenantId, long paperId, boolean includeQuestions) {
    try {
      ExamPaperResponse paper = jdbcTemplate.queryForObject("""
          select id, paper_code, paper_name, role_scope, pass_score, enabled
          from training_exam_paper
          where tenant_id = ? and id = ? and enabled = 1
          """, (rs, rowNum) -> new ExamPaperResponse(
          rs.getLong("id"),
          rs.getString("paper_code"),
          rs.getString("paper_name"),
          rs.getString("role_scope"),
          amount(rs.getBigDecimal("pass_score")),
          rs.getBoolean("enabled"),
          includeQuestions ? examQuestions(tenantId, paperId) : List.of()
      ), tenantId, paperId);
      return Optional.ofNullable(paper);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<ExamQuestionResponse> examQuestions(long tenantId, long paperId) {
    return jdbcTemplate.query("""
        select id, question_type, question_text, options_json, score, sort_order
        from training_exam_question
        where tenant_id = ? and paper_id = ? and enabled = 1
        order by sort_order, id
        """, (rs, rowNum) -> new ExamQuestionResponse(
        rs.getLong("id"),
        rs.getString("question_type"),
        rs.getString("question_text"),
        parseList(rs.getString("options_json")),
        amount(rs.getBigDecimal("score")),
        rs.getInt("sort_order")
    ), tenantId, paperId);
  }

  public List<QuestionForGrade> questionsForGrade(long tenantId, long paperId) {
    return jdbcTemplate.query("""
        select id, question_type, question_text, standard_answer, accept_keywords, score
        from training_exam_question
        where tenant_id = ? and paper_id = ? and enabled = 1
        order by sort_order, id
        """, (rs, rowNum) -> new QuestionForGrade(
        rs.getLong("id"),
        rs.getString("question_type"),
        rs.getString("question_text"),
        rs.getString("standard_answer"),
        rs.getString("accept_keywords"),
        amount(rs.getBigDecimal("score"))
    ), tenantId, paperId);
  }

  public long insertExamAttempt(
      long tenantId,
      long paperId,
      String paperName,
      String examineeName,
      String examineeRole,
      String storeId,
      String storeName,
      BigDecimal score,
      boolean passed,
      boolean violated,
      long submittedBy
  ) {
    return insertExamAttempt(
        tenantId,
        paperId,
        null,
        null,
        paperName,
        examineeName,
        examineeRole,
        storeId,
        storeName,
        score,
        passed,
        violated,
        submittedBy
    );
  }

  public long insertExamAttempt(
      long tenantId,
      long paperId,
      Long campaignId,
      Long assignmentId,
      String paperName,
      String examineeName,
      String examineeRole,
      String storeId,
      String storeName,
      BigDecimal score,
      boolean passed,
      boolean violated,
      long submittedBy
  ) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_exam_attempt(
            tenant_id, paper_id, campaign_id, assignment_id,
            paper_name, examinee_name, examinee_role, store_id, store_name,
            score, passed, violated, submitted_by, submitted_at, created_at
          )
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"}); // 显式只取 id：H2 会把所有 default 列都当 generated key 返回，getKey() 会炸
      ps.setLong(1, tenantId);
      ps.setLong(2, paperId);
      if (campaignId == null) {
        ps.setNull(3, java.sql.Types.BIGINT);
      } else {
        ps.setLong(3, campaignId);
      }
      if (assignmentId == null) {
        ps.setNull(4, java.sql.Types.BIGINT);
      } else {
        ps.setLong(4, assignmentId);
      }
      ps.setString(5, paperName);
      ps.setString(6, examineeName);
      ps.setString(7, examineeRole);
      ps.setString(8, blankToNull(storeId));
      ps.setString(9, blankToNull(storeName));
      ps.setBigDecimal(10, amount(score));
      ps.setBoolean(11, passed);
      ps.setBoolean(12, violated);
      ps.setLong(13, submittedBy);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0 : key.longValue();
  }

  public void insertExamAnswer(long tenantId, long attemptId, long questionId, String answer, boolean correct, BigDecimal score) {
    jdbcTemplate.update("""
        insert into training_exam_answer(
          tenant_id, attempt_id, question_id, user_answer, correct, score, created_at
        )
        values (?, ?, ?, ?, ?, ?, current_timestamp)
        """, tenantId, attemptId, questionId, blankToNull(answer), correct, amount(score));
  }

  public List<ExamAttemptResponse> examAttempts(long tenantId, String storeId, Long userId) {
    return examAttempts(tenantId, storeId, userId, null);
  }

  public List<ExamAttemptResponse> examAttempts(
      long tenantId,
      String storeId,
      Long userId,
      Collection<String> allowedStoreIds
  ) {
    StringBuilder sql = new StringBuilder("""
        select id, paper_id, paper_name, examinee_name, examinee_role, store_id, store_name,
               score, passed, violated, submitted_by,
               date_format(submitted_at, '%Y-%m-%d %H:%i:%s') as submitted_at
        from training_exam_attempt
        where tenant_id = ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and store_id = ?");
      args.add(storeId.trim());
    } else if (userId == null) {
      appendAllowedStoreScope(sql, args, "store_id", allowedStoreIds);
    }
    if (userId != null) {
      sql.append(" and submitted_by = ?");
      args.add(userId);
    }
    sql.append(" order by submitted_at desc, id desc limit 200");
    return jdbcTemplate.query(
        sql.toString(), (rs, rowNum) -> mapAttempt(rsAttempt(rs), List.of()), args.toArray());
  }

  public Optional<ExamAttemptResponse> examAttempt(long tenantId, long attemptId) {
    try {
      ExamAttemptRow row = jdbcTemplate.queryForObject("""
          select id, paper_id, paper_name, examinee_name, examinee_role, store_id, store_name,
                 score, passed, violated, submitted_by,
                 date_format(submitted_at, '%Y-%m-%d %H:%i:%s') as submitted_at
          from training_exam_attempt
          where tenant_id = ? and id = ?
          """, (rs, rowNum) -> rsAttempt(rs), tenantId, attemptId);
      return Optional.of(mapAttempt(row, examAnswers(tenantId, attemptId)));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<ExamAnswerResponse> examAnswers(long tenantId, long attemptId) {
    return jdbcTemplate.query("""
        select a.question_id, q.question_text, a.user_answer, a.correct, a.score
        from training_exam_answer a
        join training_exam_question q on q.tenant_id = a.tenant_id and q.id = a.question_id
        where a.tenant_id = ? and a.attempt_id = ?
        order by q.sort_order, q.id
        """, (rs, rowNum) -> new ExamAnswerResponse(
        rs.getLong("question_id"),
        rs.getString("question_text"),
        rs.getString("user_answer"),
        rs.getBoolean("correct"),
        amount(rs.getBigDecimal("score"))
    ), tenantId, attemptId);
  }

  public List<TrainingMaterialResponse> trainingMaterials(long tenantId, long userId) {
    return jdbcTemplate.query("""
        select m.id, m.material_code, m.title, m.category, m.image_urls, m.content,
               m.enabled, m.sort_order, lr.learned,
               date_format(lr.learned_at, '%Y-%m-%d %H:%i:%s') as learned_at
        from training_material m
        left join training_learning_record lr
          on lr.tenant_id = m.tenant_id and lr.material_id = m.id and lr.user_id = ?
        where m.tenant_id = ? and m.enabled = 1
        order by m.sort_order, m.id
        """, (rs, rowNum) -> new TrainingMaterialResponse(
        rs.getLong("id"),
        rs.getString("material_code"),
        rs.getString("title"),
        rs.getString("category"),
        parseList(rs.getString("image_urls")),
        rs.getString("content"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"),
        rs.getBoolean("learned"),
        rs.getString("learned_at")
    ), userId, tenantId);
  }

  public boolean materialExists(long tenantId, long materialId) {
    Integer count = jdbcTemplate.queryForObject("""
        select count(*)
        from training_material
        where tenant_id = ? and id = ? and enabled = 1
        """, Integer.class, tenantId, materialId);
    return count != null && count > 0;
  }

  public void markMaterialLearned(long tenantId, long materialId, long userId, String userName, String storeId) {
    jdbcTemplate.update("""
        insert into training_learning_record(
          tenant_id, material_id, user_id, user_name, store_id, learned, learned_at, created_at
        )
        values (?, ?, ?, ?, ?, 1, current_timestamp, current_timestamp)
        on duplicate key update
          user_name = values(user_name),
          store_id = values(store_id),
          learned = 1,
          learned_at = current_timestamp
        """, tenantId, materialId, userId, userName, blankToNull(storeId));
  }

  public List<TrainingLearningRecordResponse> learningRecords(long tenantId, String storeId) {
    return learningRecords(tenantId, storeId, null);
  }

  public List<TrainingLearningRecordResponse> learningRecords(
      long tenantId,
      String storeId,
      Collection<String> allowedStoreIds
  ) {
    return learningRecords(tenantId, storeId, allowedStoreIds, null);
  }

  public List<TrainingLearningRecordResponse> learningRecords(
      long tenantId,
      String storeId,
      Collection<String> allowedStoreIds,
      Long userId
  ) {
    StringBuilder sql = new StringBuilder("""
        select lr.id, lr.material_id, m.title as material_title, lr.user_name, lr.store_id,
               lr.learned, date_format(lr.learned_at, '%Y-%m-%d %H:%i:%s') as learned_at
        from training_learning_record lr
        join training_material m on m.tenant_id = lr.tenant_id and m.id = lr.material_id
        where lr.tenant_id = ?
        """);
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (storeId != null && !storeId.isBlank()) {
      sql.append(" and lr.store_id = ?");
      args.add(storeId.trim());
    } else if (userId == null) {
      appendAllowedStoreScope(sql, args, "lr.store_id", allowedStoreIds);
    }
    if (userId != null) {
      sql.append(" and lr.user_id = ?");
      args.add(userId);
    }
    sql.append(" order by lr.learned_at desc, lr.id desc limit 200");
    return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TrainingLearningRecordResponse(
        rs.getLong("id"),
        rs.getLong("material_id"),
        rs.getString("material_title"),
        rs.getString("user_name"),
        rs.getString("store_id"),
        rs.getBoolean("learned"),
        rs.getString("learned_at")
    ), args.toArray());
  }

  public void logAction(long tenantId, long userId, String userName, String action, String targetType, String targetId, String storeId, String reason) {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, reason, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        """,
        tenantId,
        userId,
        userName,
        action,
        targetType,
        targetId,
        blankToNull(storeId),
        blankToNull(reason)
    );
  }

  private ExamAttemptRow rsAttempt(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ExamAttemptRow(
        rs.getLong("id"),
        rs.getLong("paper_id"),
        rs.getString("paper_name"),
        rs.getString("examinee_name"),
        rs.getString("examinee_role"),
        rs.getString("store_id"),
        rs.getString("store_name"),
        amount(rs.getBigDecimal("score")),
        rs.getBoolean("passed"),
        rs.getBoolean("violated"),
        boxedLong(rs.getLong("submitted_by"), rs.wasNull()),
        rs.getString("submitted_at")
    );
  }

  private ExamAttemptResponse mapAttempt(ExamAttemptRow row, List<ExamAnswerResponse> answers) {
    return new ExamAttemptResponse(
        row.id(),
        row.paperId(),
        row.paperName(),
        row.examineeName(),
        row.examineeRole(),
        row.storeId(),
        row.storeName(),
        row.score(),
        row.passed(),
        row.violated(),
        row.submittedBy(),
        row.submittedAt(),
        answers
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

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private Long boxedLong(long value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private String required(String value, String fallback) {
    String normalized = blankToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void appendAllowedStoreScope(
      StringBuilder sql,
      List<Object> args,
      String column,
      Collection<String> allowedStoreIds
  ) {
    if (allowedStoreIds == null) {
      return;
    }
    List<String> normalized = allowedStoreIds.stream()
        .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
        .map(String::trim)
        .distinct()
        .toList();
    if (normalized.isEmpty()) {
      sql.append(" and 1 = 0");
      return;
    }
    sql.append(" and ").append(column).append(" in (")
        .append(String.join(",", Collections.nCopies(normalized.size(), "?")))
        .append(")");
    args.addAll(normalized);
  }

  private String statusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "草稿";
      case "SUBMITTED" -> "已提交";
      case "REVIEWED" -> "已复核";
      case "CANCELLED" -> "已作废";
      default -> status;
    };
  }

  public record QuestionForGrade(
      long id,
      String questionType,
      String questionText,
      String standardAnswer,
      String acceptKeywords,
      BigDecimal score
  ) {
  }

  private record ExamAttemptRow(
      long id,
      long paperId,
      String paperName,
      String examineeName,
      String examineeRole,
      String storeId,
      String storeName,
      BigDecimal score,
      boolean passed,
      boolean violated,
      Long submittedBy,
      String submittedAt
  ) {
  }
}
