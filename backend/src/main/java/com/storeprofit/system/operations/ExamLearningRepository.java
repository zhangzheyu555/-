package com.storeprofit.system.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.operations.ExamLearningModels.CourseResponse;
import com.storeprofit.system.operations.ExamLearningModels.EncodingCheckResponse;
import com.storeprofit.system.operations.ExamLearningModels.ExamResultResponse;
import com.storeprofit.system.operations.ExamLearningModels.MaterialResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewAnswerResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewTaskResponse;
import com.storeprofit.system.operations.ExamLearningModels.WrongQuestionResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Types;
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
public class ExamLearningRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExamLearningRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public List<CourseResponse> courses(long tenantId, boolean includeDisabled) {
    String enabled = includeDisabled ? "" : " and c.enabled = 1";
    return jdbcTemplate.query("""
        select c.id, c.course_code, c.title, c.category, c.description, c.cover_url,
               c.duration_minutes, c.required_role_scope, c.enabled, c.sort_order,
               count(cm.id) as material_count
        from training_course c
        left join training_course_material cm
          on cm.tenant_id = c.tenant_id and cm.course_id = c.id
        where c.tenant_id = ?
        """ + enabled + "\n" + """
        group by c.id, c.course_code, c.title, c.category, c.description, c.cover_url,
                 c.duration_minutes, c.required_role_scope, c.enabled, c.sort_order
        order by c.sort_order, c.id
        """, (rs, rowNum) -> new CourseResponse(
        rs.getLong("id"),
        rs.getString("course_code"),
        rs.getString("title"),
        rs.getString("category"),
        rs.getString("description"),
        rs.getString("cover_url"),
        rs.getInt("duration_minutes"),
        rs.getString("required_role_scope"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"),
        rs.getInt("material_count"),
        courseMaterialIds(tenantId, rs.getLong("id"))
    ), tenantId);
  }

  public Optional<CourseResponse> course(long tenantId, long courseId) {
    return courses(tenantId, true).stream().filter(item -> item.id() == courseId).findFirst();
  }

  public long insertCourse(
      long tenantId,
      String code,
      String title,
      String category,
      String description,
      String coverUrl,
      int durationMinutes,
      String roleScope,
      boolean enabled,
      int sortOrder,
      long userId
  ) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_course(
            tenant_id, course_code, title, category, description, cover_url, duration_minutes,
            required_role_scope, enabled, sort_order, created_by, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, code);
      ps.setString(3, title);
      ps.setString(4, blankToNull(category));
      ps.setString(5, blankToNull(description));
      ps.setString(6, blankToNull(coverUrl));
      ps.setInt(7, durationMinutes);
      ps.setString(8, blankToNull(roleScope));
      ps.setBoolean(9, enabled);
      ps.setInt(10, sortOrder);
      ps.setLong(11, userId);
      return ps;
    }, keys);
    Number key = keys.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updateCourse(
      long tenantId,
      long courseId,
      String code,
      String title,
      String category,
      String description,
      String coverUrl,
      int durationMinutes,
      String roleScope,
      boolean enabled,
      int sortOrder
  ) {
    return jdbcTemplate.update("""
        update training_course
        set course_code = ?, title = ?, category = ?, description = ?, cover_url = ?,
            duration_minutes = ?, required_role_scope = ?, enabled = ?, sort_order = ?,
            updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, code, title, blankToNull(category), blankToNull(description), blankToNull(coverUrl),
        durationMinutes, blankToNull(roleScope), enabled, sortOrder, tenantId, courseId) > 0;
  }

  public void replaceCourseMaterials(long tenantId, long courseId, List<Long> materialIds) {
    jdbcTemplate.update("delete from training_course_material where tenant_id = ? and course_id = ?", tenantId, courseId);
    int order = 1;
    for (Long materialId : materialIds == null ? List.<Long>of() : materialIds) {
      if (materialId == null) {
        continue;
      }
      jdbcTemplate.update("""
          insert into training_course_material(tenant_id, course_id, material_id, required, sort_order, created_at)
          values (?, ?, ?, 1, ?, current_timestamp)
          """, tenantId, courseId, materialId, order++);
    }
  }

  private List<Long> courseMaterialIds(long tenantId, long courseId) {
    return jdbcTemplate.query("""
        select material_id from training_course_material
        where tenant_id = ? and course_id = ? order by sort_order, id
        """, (rs, rowNum) -> rs.getLong("material_id"), tenantId, courseId);
  }

  public List<MaterialResponse> materials(long tenantId, boolean includeDisabled) {
    String enabled = includeDisabled ? "" : " and m.enabled = 1";
    return jdbcTemplate.query("""
        select m.id, m.material_code, m.title, m.category, m.image_urls, m.content,
               m.enabled, m.sort_order, count(l.id) as learned_count
        from training_material m
        left join training_learning_record l
          on l.tenant_id = m.tenant_id and l.material_id = m.id and l.learned = 1
        where m.tenant_id = ?
        """ + enabled + "\n" + """
        group by m.id, m.material_code, m.title, m.category, m.image_urls, m.content, m.enabled, m.sort_order
        order by m.sort_order, m.id
        """, (rs, rowNum) -> new MaterialResponse(
        rs.getLong("id"),
        rs.getString("material_code"),
        rs.getString("title"),
        rs.getString("category"),
        parseList(rs.getString("image_urls")),
        rs.getString("content"),
        rs.getBoolean("enabled"),
        rs.getInt("sort_order"),
        rs.getInt("learned_count")
    ), tenantId);
  }

  public Optional<MaterialResponse> material(long tenantId, long materialId) {
    return materials(tenantId, true).stream().filter(item -> item.id() == materialId).findFirst();
  }

  public long insertMaterial(
      long tenantId,
      String code,
      String title,
      String category,
      List<String> images,
      String content,
      boolean enabled,
      int sortOrder
  ) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_material(
            tenant_id, material_code, title, category, image_urls, content,
            enabled, sort_order, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, code);
      ps.setString(3, title);
      ps.setString(4, category);
      ps.setString(5, writeList(images));
      ps.setString(6, blankToNull(content));
      ps.setBoolean(7, enabled);
      ps.setInt(8, sortOrder);
      return ps;
    }, keys);
    Number key = keys.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updateMaterial(
      long tenantId,
      long materialId,
      String code,
      String title,
      String category,
      List<String> images,
      String content,
      boolean enabled,
      int sortOrder
  ) {
    return jdbcTemplate.update("""
        update training_material
        set material_code = ?, title = ?, category = ?, image_urls = ?, content = ?,
            enabled = ?, sort_order = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, code, title, category, writeList(images), blankToNull(content), enabled, sortOrder, tenantId, materialId) > 0;
  }

  public List<QuestionCategoryResponse> questionCategories(long tenantId, boolean includeDisabled) {
    String enabled = includeDisabled ? "" : " and c.enabled = 1";
    return jdbcTemplate.query("""
        select c.id, c.category_code, c.category_name, c.description, c.enabled, c.sort_order,
               count(q.id) as question_count
        from training_exam_question_category c
        left join training_exam_question_bank q
          on q.tenant_id = c.tenant_id and q.category_id = c.id
        where c.tenant_id = ?
        """ + enabled + "\n" + """
        group by c.id, c.category_code, c.category_name, c.description, c.enabled, c.sort_order
        order by c.sort_order, c.id
        """, (rs, rowNum) -> new QuestionCategoryResponse(
        rs.getLong("id"), rs.getString("category_code"), rs.getString("category_name"),
        rs.getString("description"), rs.getBoolean("enabled"), rs.getInt("sort_order"),
        rs.getInt("question_count")
    ), tenantId);
  }

  public Optional<QuestionCategoryResponse> questionCategory(long tenantId, long categoryId) {
    return questionCategories(tenantId, true).stream().filter(item -> item.id() == categoryId).findFirst();
  }

  public long insertQuestionCategory(
      long tenantId,
      String code,
      String name,
      String description,
      boolean enabled,
      int sortOrder
  ) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_exam_question_category(
            tenant_id, category_code, category_name, description, enabled, sort_order, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, code);
      ps.setString(3, name);
      ps.setString(4, blankToNull(description));
      ps.setBoolean(5, enabled);
      ps.setInt(6, sortOrder);
      return ps;
    }, keys);
    Number key = keys.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updateQuestionCategory(
      long tenantId,
      long categoryId,
      String code,
      String name,
      String description,
      boolean enabled,
      int sortOrder
  ) {
    return jdbcTemplate.update("""
        update training_exam_question_category
        set category_code = ?, category_name = ?, description = ?, enabled = ?, sort_order = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, code, name, blankToNull(description), enabled, sortOrder, tenantId, categoryId) > 0;
  }

  public int deleteQuestionCategory(long tenantId, long categoryId) {
    return jdbcTemplate.update("delete from training_exam_question_category where tenant_id = ? and id = ?", tenantId, categoryId);
  }

  public List<QuestionBankResponse> questions(
      long tenantId,
      Long categoryId,
      String keyword,
      boolean includeDisabled
  ) {
    StringBuilder where = new StringBuilder(" where q.tenant_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (categoryId != null) {
      where.append(" and q.category_id = ?");
      args.add(categoryId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (q.question_code like ? or q.question_text like ?)");
      String value = "%" + keyword.trim() + "%";
      args.add(value);
      args.add(value);
    }
    if (!includeDisabled) {
      where.append(" and q.enabled = 1");
    }
    return jdbcTemplate.query("""
        select q.id, q.question_code, q.category_id, c.category_name, q.question_type,
               q.question_text, q.options_json, q.standard_answer, q.answer_analysis,
               q.accept_keywords, q.difficulty, q.default_score, q.enabled,
               count(l.id) as used_count
        from training_exam_question_bank q
        left join training_exam_question_category c
          on c.tenant_id = q.tenant_id and c.id = q.category_id
        left join training_exam_paper_question_link l
          on l.tenant_id = q.tenant_id and l.bank_question_id = q.id
        """ + where + "\n" + """
        group by q.id, q.question_code, q.category_id, c.category_name, q.question_type,
                 q.question_text, q.options_json, q.standard_answer, q.answer_analysis,
                 q.accept_keywords, q.difficulty, q.default_score, q.enabled
        order by c.sort_order, q.id desc
        limit 1000
        """, (rs, rowNum) -> new QuestionBankResponse(
        rs.getLong("id"), rs.getString("question_code"), boxedLong(rs.getLong("category_id"), rs.wasNull()),
        rs.getString("category_name"), rs.getString("question_type"), rs.getString("question_text"),
        parseList(rs.getString("options_json")), rs.getString("standard_answer"), rs.getString("answer_analysis"),
        rs.getString("accept_keywords"), rs.getString("difficulty"), amount(rs.getBigDecimal("default_score")),
        rs.getBoolean("enabled"), rs.getInt("used_count")
    ), args.toArray());
  }

  public Optional<QuestionBankResponse> question(long tenantId, long questionId) {
    return questions(tenantId, null, null, true).stream().filter(item -> item.id() == questionId).findFirst();
  }

  public long insertQuestion(
      long tenantId,
      String code,
      Long categoryId,
      String type,
      String text,
      List<String> options,
      String standardAnswer,
      String analysis,
      String keywords,
      String difficulty,
      BigDecimal score,
      boolean enabled,
      long userId
  ) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement("""
          insert into training_exam_question_bank(
            tenant_id, question_code, category_id, question_type, question_text, options_json,
            standard_answer, answer_analysis, accept_keywords, difficulty, default_score,
            enabled, created_by, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, new String[]{"id"});
      ps.setLong(1, tenantId);
      ps.setString(2, code);
      if (categoryId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, categoryId);
      ps.setString(4, type);
      ps.setString(5, text);
      ps.setString(6, writeList(options));
      ps.setString(7, blankToNull(standardAnswer));
      ps.setString(8, blankToNull(analysis));
      ps.setString(9, blankToNull(keywords));
      ps.setString(10, difficulty);
      ps.setBigDecimal(11, amount(score));
      ps.setBoolean(12, enabled);
      ps.setLong(13, userId);
      return ps;
    }, keys);
    Number key = keys.getKey();
    return key == null ? 0L : key.longValue();
  }

  public boolean updateQuestion(
      long tenantId,
      long questionId,
      String code,
      Long categoryId,
      String type,
      String text,
      List<String> options,
      String standardAnswer,
      String analysis,
      String keywords,
      String difficulty,
      BigDecimal score,
      boolean enabled
  ) {
    return jdbcTemplate.update("""
        update training_exam_question_bank
        set question_code = ?, category_id = ?, question_type = ?, question_text = ?, options_json = ?,
            standard_answer = ?, answer_analysis = ?, accept_keywords = ?, difficulty = ?,
            default_score = ?, enabled = ?, updated_at = current_timestamp
        where tenant_id = ? and id = ?
        """, code, categoryId, type, text, writeList(options), blankToNull(standardAnswer),
        blankToNull(analysis), blankToNull(keywords), difficulty, amount(score), enabled, tenantId, questionId) > 0;
  }

  public List<ReviewTaskResponse> reviewTasks(long tenantId) {
    return reviewTasks(tenantId, null);
  }

  public List<ReviewTaskResponse> reviewTasks(long tenantId, Collection<String> allowedStoreIds) {
    StringBuilder sql = new StringBuilder("""
        select a.id as attempt_id, a.assignment_id, c.title as exam_title, a.paper_name,
               a.examinee_name, a.store_id, a.store_name, a.score as auto_score,
               date_format(a.submitted_at, '%Y-%m-%d %H:%i:%s') as submitted_at,
               r.review_status
        from training_exam_attempt_review r
        join training_exam_attempt a on a.tenant_id = r.tenant_id and a.id = r.attempt_id
        left join training_exam_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id
        where r.tenant_id = ? and r.review_status = 'PENDING'
        """);
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    appendAllowedStoreScope(sql, args, "a.store_id", allowedStoreIds);
    sql.append(" order by a.submitted_at, a.id");
    return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapReviewTask(rs), args.toArray());
  }

  public Optional<ReviewTaskResponse> reviewTask(long tenantId, long attemptId) {
    try {
      return Optional.ofNullable(jdbcTemplate.queryForObject("""
          select a.id as attempt_id, a.assignment_id, c.title as exam_title, a.paper_name,
                 a.examinee_name, a.store_id, a.store_name, a.score as auto_score,
                 date_format(a.submitted_at, '%Y-%m-%d %H:%i:%s') as submitted_at,
                 r.review_status
          from training_exam_attempt_review r
          join training_exam_attempt a on a.tenant_id = r.tenant_id and a.id = r.attempt_id
          left join training_exam_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id
          where r.tenant_id = ? and r.attempt_id = ?
          """, (rs, rowNum) -> mapReviewTask(rs), tenantId, attemptId));
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  public List<ReviewAnswerResponse> reviewAnswers(long tenantId, long attemptId) {
    return jdbcTemplate.query("""
        select a.id as answer_id, a.question_id, q.question_type, q.question_text,
               q.standard_answer, a.user_answer, q.score as max_score,
               coalesce(ar.awarded_score, a.score) as awarded_score, a.correct,
               ar.review_comment
        from training_exam_answer a
        join training_exam_question q on q.tenant_id = a.tenant_id and q.id = a.question_id
        left join training_exam_answer_review ar on ar.tenant_id = a.tenant_id and ar.answer_id = a.id
        where a.tenant_id = ? and a.attempt_id = ?
        order by q.sort_order, q.id
        """, (rs, rowNum) -> new ReviewAnswerResponse(
        rs.getLong("answer_id"), rs.getLong("question_id"), rs.getString("question_type"),
        rs.getString("question_text"), rs.getString("standard_answer"), rs.getString("user_answer"),
        amount(rs.getBigDecimal("max_score")), amount(rs.getBigDecimal("awarded_score")),
        rs.getBoolean("correct"), rs.getString("review_comment")
    ), tenantId, attemptId);
  }

  public String reviewNote(long tenantId, long attemptId) {
    try {
      return jdbcTemplate.queryForObject("""
          select review_note from training_exam_attempt_review where tenant_id = ? and attempt_id = ?
          """, String.class, tenantId, attemptId);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  public void createAttemptReview(long tenantId, long attemptId, String status) {
    jdbcTemplate.update("""
        insert into training_exam_attempt_review(
          tenant_id, attempt_id, review_status, created_at, updated_at
        ) values (?, ?, ?, current_timestamp, current_timestamp)
        """, tenantId, attemptId, status);
  }

  public long answerAttemptId(long tenantId, long answerId) {
    Long value = jdbcTemplate.queryForObject("""
        select attempt_id from training_exam_answer where tenant_id = ? and id = ?
        """, Long.class, tenantId, answerId);
    return value == null ? 0L : value;
  }

  public BigDecimal answerMaxScore(long tenantId, long answerId) {
    BigDecimal value = jdbcTemplate.queryForObject("""
        select q.score
        from training_exam_answer a
        join training_exam_question q on q.tenant_id = a.tenant_id and q.id = a.question_id
        where a.tenant_id = ? and a.id = ?
        """, BigDecimal.class, tenantId, answerId);
    return amount(value);
  }

  public void reviewAnswer(long tenantId, long answerId, BigDecimal score, String comment, long reviewerId) {
    BigDecimal normalized = amount(score);
    jdbcTemplate.update("""
        update training_exam_answer
        set score = ?, correct = ?
        where tenant_id = ? and id = ?
        """, normalized, normalized.compareTo(BigDecimal.ZERO) > 0, tenantId, answerId);
    int updated = jdbcTemplate.update("""
        update training_exam_answer_review
        set awarded_score = ?, review_comment = ?, reviewed_by = ?, reviewed_at = current_timestamp
        where tenant_id = ? and answer_id = ?
        """, normalized, blankToNull(comment), reviewerId, tenantId, answerId);
    if (updated == 0) {
      jdbcTemplate.update("""
          insert into training_exam_answer_review(
            tenant_id, answer_id, awarded_score, review_comment, reviewed_by, reviewed_at, created_at
          ) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
          """, tenantId, answerId, normalized, blankToNull(comment), reviewerId);
    }
  }

  public BigDecimal attemptScore(long tenantId, long attemptId) {
    BigDecimal value = jdbcTemplate.queryForObject("""
        select coalesce(sum(score), 0) from training_exam_answer where tenant_id = ? and attempt_id = ?
        """, BigDecimal.class, tenantId, attemptId);
    return amount(value);
  }

  public BigDecimal attemptPassScore(long tenantId, long attemptId) {
    BigDecimal value = jdbcTemplate.queryForObject("""
        select p.pass_score
        from training_exam_attempt a
        join training_exam_paper p on p.tenant_id = a.tenant_id and p.id = a.paper_id
        where a.tenant_id = ? and a.id = ?
        """, BigDecimal.class, tenantId, attemptId);
    return amount(value);
  }

  public void completeReview(long tenantId, long attemptId, BigDecimal score, boolean passed, String note, long reviewerId) {
    jdbcTemplate.update("""
        update training_exam_attempt
        set score = ?, passed = ?
        where tenant_id = ? and id = ?
        """, amount(score), passed, tenantId, attemptId);
    jdbcTemplate.update("""
        update training_exam_attempt_review
        set review_status = 'REVIEWED', review_note = ?, reviewed_by = ?, reviewed_at = current_timestamp,
            updated_at = current_timestamp
        where tenant_id = ? and attempt_id = ?
        """, blankToNull(note), reviewerId, tenantId, attemptId);
    Long assignmentId = jdbcTemplate.queryForObject("""
        select assignment_id from training_exam_attempt where tenant_id = ? and id = ?
        """, Long.class, tenantId, attemptId);
    if (assignmentId != null) {
      jdbcTemplate.update("""
          update training_exam_assignment
          set status = 'COMPLETED', completed_at = current_timestamp, score = ?, passed = ?,
              updated_at = current_timestamp
          where tenant_id = ? and id = ?
          """, amount(score), passed, tenantId, assignmentId);
    }
  }

  public List<ExamResultResponse> results(long tenantId, String storeId, Long userId) {
    return results(
        tenantId,
        storeId == null || storeId.isBlank() ? null : List.of(storeId.trim()),
        userId
    );
  }

  public List<ExamResultResponse> results(
      long tenantId,
      Collection<String> allowedStoreIds,
      Long userId
  ) {
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(tenantId);
    if (userId == null) {
      appendAllowedStoreScope(where, args, "a.store_id", allowedStoreIds);
    }
    if (userId != null) {
      where.append(" and a.submitted_by = ?");
      args.add(userId);
    }
    return jdbcTemplate.query("""
        select a.id as attempt_id, a.assignment_id, a.campaign_id, c.title as exam_title,
               a.paper_name, a.submitted_by as user_id, a.examinee_name, a.examinee_role,
               a.store_id, a.store_name, a.score, a.passed, a.violated,
               coalesce(r.review_status, 'AUTO_GRADED') as review_status,
               date_format(a.submitted_at, '%Y-%m-%d %H:%i:%s') as submitted_at,
               date_format(r.reviewed_at, '%Y-%m-%d %H:%i:%s') as reviewed_at
        from training_exam_attempt a
        left join training_exam_campaign c on c.tenant_id = a.tenant_id and c.id = a.campaign_id
        left join training_exam_attempt_review r on r.tenant_id = a.tenant_id and r.attempt_id = a.id
        """ + where + " order by a.submitted_at desc, a.id desc limit 1000", (rs, rowNum) -> new ExamResultResponse(
        rs.getLong("attempt_id"), boxedLong(rs.getLong("assignment_id"), rs.wasNull()),
        boxedLong(rs.getLong("campaign_id"), rs.wasNull()), rs.getString("exam_title"),
        rs.getString("paper_name"), boxedLong(rs.getLong("user_id"), rs.wasNull()),
        rs.getString("examinee_name"), rs.getString("examinee_role"), rs.getString("store_id"),
        rs.getString("store_name"), amount(rs.getBigDecimal("score")), rs.getBoolean("passed"),
        rs.getBoolean("violated"), rs.getString("review_status"), rs.getString("submitted_at"),
        rs.getString("reviewed_at")
    ), args.toArray());
  }

  public void syncWrongQuestions(long tenantId, long userId, long attemptId) {
    jdbcTemplate.update("""
        insert into training_exam_wrong_question(
          tenant_id, user_id, attempt_id, question_id, mastered, created_at, updated_at
        )
        select a.tenant_id, ?, a.attempt_id, a.question_id, 0, current_timestamp, current_timestamp
        from training_exam_answer a
        where a.tenant_id = ? and a.attempt_id = ? and a.correct = 0
          and not exists (
            select 1 from training_exam_wrong_question w
            where w.tenant_id = a.tenant_id and w.user_id = ?
              and w.attempt_id = a.attempt_id and w.question_id = a.question_id
          )
        """, userId, tenantId, attemptId, userId);
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

  public List<WrongQuestionResponse> wrongQuestions(long tenantId, long userId) {
    return jdbcTemplate.query("""
        select w.id, w.attempt_id, w.question_id, t.paper_name, q.question_type,
               q.question_text, q.standard_answer, a.user_answer,
               b.answer_analysis, w.mastered,
               date_format(w.created_at, '%Y-%m-%d %H:%i:%s') as created_at
        from training_exam_wrong_question w
        join training_exam_attempt t on t.tenant_id = w.tenant_id and t.id = w.attempt_id
        join training_exam_answer a
          on a.tenant_id = w.tenant_id and a.attempt_id = w.attempt_id and a.question_id = w.question_id
        join training_exam_question q on q.tenant_id = w.tenant_id and q.id = w.question_id
        left join training_exam_paper_question_link l
          on l.tenant_id = w.tenant_id and l.paper_question_id = w.question_id
        left join training_exam_question_bank b
          on b.tenant_id = w.tenant_id and b.id = l.bank_question_id
        where w.tenant_id = ? and w.user_id = ?
        order by w.mastered, w.created_at desc, w.id desc
        """, (rs, rowNum) -> new WrongQuestionResponse(
        rs.getLong("id"), rs.getLong("attempt_id"), rs.getLong("question_id"), rs.getString("paper_name"),
        rs.getString("question_type"), rs.getString("question_text"), rs.getString("standard_answer"),
        rs.getString("user_answer"), rs.getString("answer_analysis"), rs.getBoolean("mastered"),
        rs.getString("created_at")
    ), tenantId, userId);
  }

  public int markWrongQuestionMastered(long tenantId, long userId, long wrongId, boolean mastered) {
    return jdbcTemplate.update("""
        update training_exam_wrong_question
        set mastered = ?, mastered_at = case when ? then current_timestamp else null end,
            updated_at = current_timestamp
        where tenant_id = ? and user_id = ? and id = ?
        """, mastered, mastered, tenantId, userId, wrongId);
  }

  public EncodingCheckResponse encodingCheck(long tenantId) {
    String databaseCharset = "UTF-8";
    String connectionCharset = "UTF-8";
    try {
      databaseCharset = jdbcTemplate.queryForObject("select @@character_set_database", String.class);
      connectionCharset = jdbcTemplate.queryForObject("select @@character_set_connection", String.class);
    } catch (RuntimeException ignored) {
      databaseCharset = "UTF-8 (test database)";
      connectionCharset = "UTF-8 (test connection)";
    }
    int paperCount = count("select count(*) from training_exam_paper where tenant_id = ? and locate('?', paper_name) > 0", tenantId);
    int questionCount = count("select count(*) from training_exam_question where tenant_id = ? and locate('?', question_text) > 0", tenantId);
    int materialCount = count("select count(*) from training_material where tenant_id = ? and (locate('?', title) > 0 or locate('?', content) > 0)", tenantId);
    List<String> samples = new ArrayList<>();
    try {
      samples.addAll(jdbcTemplate.query("""
          select concat('paper:', id, ':', hex(paper_name)) as sample
          from training_exam_paper where tenant_id = ? and locate('?', paper_name) > 0 limit 5
          """, (rs, rowNum) -> rs.getString("sample"), tenantId));
      samples.addAll(jdbcTemplate.query("""
          select concat('question:', id, ':', left(hex(question_text), 120)) as sample
          from training_exam_question where tenant_id = ? and locate('?', question_text) > 0 limit 5
          """, (rs, rowNum) -> rs.getString("sample"), tenantId));
    } catch (RuntimeException ignored) {
      samples = List.of();
    }
    return new EncodingCheckResponse(databaseCharset, connectionCharset, paperCount, questionCount, materialCount, samples);
  }

  private ReviewTaskResponse mapReviewTask(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ReviewTaskResponse(
        rs.getLong("attempt_id"), boxedLong(rs.getLong("assignment_id"), rs.wasNull()),
        rs.getString("exam_title"), rs.getString("paper_name"), rs.getString("examinee_name"),
        rs.getString("store_id"), rs.getString("store_name"), amount(rs.getBigDecimal("auto_score")),
        rs.getString("submitted_at"), rs.getString("review_status")
    );
  }

  private int count(String sql, long tenantId) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class, tenantId);
    return value == null ? 0 : value;
  }

  private Long boxedLong(long value, boolean wasNull) {
    return wasNull ? null : value;
  }

  private List<String> parseList(String value) {
    if (value == null || value.isBlank()) return List.of();
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
      throw new IllegalArgumentException("列表格式不正确", ex);
    }
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
