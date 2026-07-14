package com.storeprofit.system.operations;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.ExamLearningModels.AttemptReviewRequest;
import com.storeprofit.system.operations.ExamLearningModels.CourseRequest;
import com.storeprofit.system.operations.ExamLearningModels.CourseResponse;
import com.storeprofit.system.operations.ExamLearningModels.EncodingCheckResponse;
import com.storeprofit.system.operations.ExamLearningModels.ExamResultResponse;
import com.storeprofit.system.operations.ExamLearningModels.MaterialRequest;
import com.storeprofit.system.operations.ExamLearningModels.MaterialResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionBankResponse;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryRequest;
import com.storeprofit.system.operations.ExamLearningModels.QuestionCategoryResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewAnswerRequest;
import com.storeprofit.system.operations.ExamLearningModels.ReviewDetailResponse;
import com.storeprofit.system.operations.ExamLearningModels.ReviewTaskResponse;
import com.storeprofit.system.operations.ExamLearningModels.WrongQuestionResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamLearningService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final AccessControlService accessControl;
  private final ExamLearningRepository repository;
  private final AuditRepository auditRepository;

  public ExamLearningService(
      AccessControlService accessControl,
      ExamLearningRepository repository,
      AuditRepository auditRepository
  ) {
    this.accessControl = accessControl;
    this.repository = repository;
    this.auditRepository = auditRepository;
  }

  public List<CourseResponse> courses(AuthUser user) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    return repository.courses(user.tenantId(), canManage(user));
  }

  @Transactional
  public CourseResponse saveCourse(AuthUser user, CourseRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    if (request == null) throw bad("COURSE_REQUIRED", "请填写培训课程");
    String code = normalizedCode(request.courseCode(), "COURSE");
    String title = required(request.title(), "请填写课程名称");
    int duration = Math.max(0, request.durationMinutes() == null ? 0 : request.durationMinutes());
    int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
    boolean enabled = request.enabled() == null || request.enabled();
    List<Long> materialIds = distinctIds(request.materialIds());
    long id;
    if (request.id() == null) {
      id = repository.insertCourse(
          user.tenantId(), code, title, blankToNull(request.category()), blankToNull(request.description()),
          blankToNull(request.coverUrl()), duration, blankToNull(request.requiredRoleScope()), enabled,
          sortOrder, user.id());
    } else {
      id = request.id();
      if (!repository.updateCourse(
          user.tenantId(), id, code, title, blankToNull(request.category()), blankToNull(request.description()),
          blankToNull(request.coverUrl()), duration, blankToNull(request.requiredRoleScope()), enabled, sortOrder)) {
        throw notFound("COURSE_NOT_FOUND", "培训课程不存在");
      }
    }
    for (Long materialId : materialIds) {
      if (repository.material(user.tenantId(), materialId).isEmpty()) {
        throw bad("MATERIAL_NOT_FOUND", "课程中包含不存在的学习资料");
      }
    }
    repository.replaceCourseMaterials(user.tenantId(), id, materialIds);
    audit(user, request.id() == null ? "创建培训课程" : "修改培训课程", "training_course", id, title);
    return repository.course(user.tenantId(), id).orElseThrow(() -> notFound("COURSE_NOT_FOUND", "培训课程不存在"));
  }

  public List<MaterialResponse> materials(AuthUser user) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    return repository.materials(user.tenantId(), canManage(user));
  }

  @Transactional
  public MaterialResponse saveMaterial(AuthUser user, MaterialRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    if (request == null) throw bad("MATERIAL_REQUIRED", "请填写学习资料");
    String code = normalizedCode(request.materialCode(), "MATERIAL");
    String title = required(request.title(), "请填写资料名称");
    String category = required(request.category(), "请选择资料分类");
    List<String> images = (request.imageUrls() == null ? List.<String>of() : request.imageUrls()).stream()
        .map(this::blankToNull).filter(value -> value != null).distinct().limit(20).toList();
    boolean enabled = request.enabled() == null || request.enabled();
    int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
    long id;
    if (request.id() == null) {
      id = repository.insertMaterial(user.tenantId(), code, title, category, images, request.content(), enabled, sortOrder);
    } else {
      id = request.id();
      if (!repository.updateMaterial(user.tenantId(), id, code, title, category, images, request.content(), enabled, sortOrder)) {
        throw notFound("MATERIAL_NOT_FOUND", "学习资料不存在");
      }
    }
    audit(user, request.id() == null ? "创建学习资料" : "修改学习资料", "training_material", id, title);
    return repository.material(user.tenantId(), id).orElseThrow(() -> notFound("MATERIAL_NOT_FOUND", "学习资料不存在"));
  }

  public List<QuestionCategoryResponse> questionCategories(AuthUser user) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    return repository.questionCategories(user.tenantId(), canManage(user));
  }

  @Transactional
  public QuestionCategoryResponse saveQuestionCategory(AuthUser user, QuestionCategoryRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    if (request == null) throw bad("CATEGORY_REQUIRED", "请填写题目分类");
    String code = normalizedCode(request.categoryCode(), "CATEGORY");
    String name = required(request.categoryName(), "请填写分类名称");
    boolean enabled = request.enabled() == null || request.enabled();
    int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
    long id;
    if (request.id() == null) {
      id = repository.insertQuestionCategory(user.tenantId(), code, name, request.description(), enabled, sortOrder);
    } else {
      id = request.id();
      if (!repository.updateQuestionCategory(user.tenantId(), id, code, name, request.description(), enabled, sortOrder)) {
        throw notFound("CATEGORY_NOT_FOUND", "题目分类不存在");
      }
    }
    audit(user, request.id() == null ? "创建题目分类" : "修改题目分类", "training_exam_question_category", id, name);
    return repository.questionCategory(user.tenantId(), id).orElseThrow(() -> notFound("CATEGORY_NOT_FOUND", "题目分类不存在"));
  }

  @Transactional
  public void deleteQuestionCategory(AuthUser user, long categoryId) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    QuestionCategoryResponse category = repository.questionCategory(user.tenantId(), categoryId)
        .orElseThrow(() -> notFound("CATEGORY_NOT_FOUND", "题目分类不存在"));
    if (category.questionCount() > 0) {
      throw new BusinessException("CATEGORY_IN_USE", "该分类下仍有题目，不能删除", HttpStatus.CONFLICT);
    }
    repository.deleteQuestionCategory(user.tenantId(), categoryId);
    audit(user, "删除题目分类", "training_exam_question_category", categoryId, category.categoryName());
  }

  public List<QuestionBankResponse> questions(AuthUser user, Long categoryId, String keyword) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    return repository.questions(
        user.tenantId(), categoryId, blankToNull(keyword), canManage(user));
  }

  @Transactional
  public QuestionBankResponse saveQuestion(AuthUser user, QuestionBankRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    if (request == null) throw bad("QUESTION_REQUIRED", "请填写题目");
    String code = normalizedCode(request.questionCode(), "QUESTION");
    String type = normalizeQuestionType(request.questionType());
    String text = required(request.questionText(), "请填写题干");
    String difficulty = normalizeDifficulty(request.difficulty());
    BigDecimal score = amount(request.defaultScore());
    if (score.compareTo(ZERO) <= 0) throw bad("QUESTION_SCORE_INVALID", "题目分值必须大于 0");
    if (request.categoryId() != null && repository.questionCategory(user.tenantId(), request.categoryId()).isEmpty()) {
      throw bad("CATEGORY_NOT_FOUND", "题目分类不存在");
    }
    List<String> options = (request.options() == null ? List.<String>of() : request.options()).stream()
        .map(this::blankToNull).filter(value -> value != null).distinct().toList();
    String standard = blankToNull(request.standardAnswer());
    if ("SINGLE_CHOICE".equals(type) && (options.size() < 2 || standard == null || !options.contains(standard))) {
      throw bad("QUESTION_OPTION_INVALID", "单选题至少需要两个选项，且标准答案必须在选项中");
    }
    if (!"ESSAY".equals(type) && standard == null) {
      throw bad("STANDARD_ANSWER_REQUIRED", "请填写标准答案");
    }
    boolean enabled = request.enabled() == null || request.enabled();
    long id;
    if (request.id() == null) {
      id = repository.insertQuestion(
          user.tenantId(), code, request.categoryId(), type, text, options, standard,
          request.answerAnalysis(), request.acceptKeywords(), difficulty, score, enabled, user.id());
    } else {
      id = request.id();
      if (!repository.updateQuestion(
          user.tenantId(), id, code, request.categoryId(), type, text, options, standard,
          request.answerAnalysis(), request.acceptKeywords(), difficulty, score, enabled)) {
        throw notFound("QUESTION_NOT_FOUND", "题目不存在");
      }
    }
    audit(user, request.id() == null ? "创建题库题目" : "修改题库题目", "training_exam_question_bank", id, text);
    return repository.question(user.tenantId(), id).orElseThrow(() -> notFound("QUESTION_NOT_FOUND", "题目不存在"));
  }

  public List<ReviewTaskResponse> reviewTasks(AuthUser user) {
    accessControl.requireExamManage(user);
    DataScope scope = requireManageScope(user);
    return scope.allowsAllStores()
        ? repository.reviewTasks(user.tenantId())
        : repository.reviewTasks(user.tenantId(), Set.copyOf(scope.storeIds()));
  }

  public ReviewDetailResponse reviewDetail(AuthUser user, long attemptId) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    ReviewTaskResponse task = repository.reviewTask(user.tenantId(), attemptId)
        .orElseThrow(() -> notFound("REVIEW_NOT_FOUND", "待阅卷记录不存在"));
    requireTaskScope(user, task, "查看考试阅卷");
    return new ReviewDetailResponse(task, repository.reviewAnswers(user.tenantId(), attemptId), repository.reviewNote(user.tenantId(), attemptId));
  }

  @Transactional
  public ReviewDetailResponse review(AuthUser user, long attemptId, AttemptReviewRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    ReviewTaskResponse task = repository.reviewTask(user.tenantId(), attemptId)
        .orElseThrow(() -> notFound("REVIEW_NOT_FOUND", "待阅卷记录不存在"));
    requireTaskScope(user, task, "提交考试阅卷");
    if (!"PENDING".equals(task.reviewStatus())) {
      throw new BusinessException("REVIEW_COMPLETED", "该试卷已经完成阅卷", HttpStatus.CONFLICT);
    }
    List<ReviewAnswerRequest> answers = request == null || request.answers() == null ? List.of() : request.answers();
    if (answers.isEmpty()) throw bad("REVIEW_ANSWER_REQUIRED", "请填写阅卷分数");
    for (ReviewAnswerRequest answer : answers) {
      if (answer == null || answer.answerId() == null) throw bad("REVIEW_ANSWER_INVALID", "阅卷答案不存在");
      if (repository.answerAttemptId(user.tenantId(), answer.answerId()) != attemptId) {
        throw new BusinessException("REVIEW_SCOPE_INVALID", "阅卷答案不属于当前试卷", HttpStatus.FORBIDDEN);
      }
      BigDecimal score = amount(answer.awardedScore());
      BigDecimal max = repository.answerMaxScore(user.tenantId(), answer.answerId());
      if (score.compareTo(ZERO) < 0 || score.compareTo(max) > 0) {
        throw bad("REVIEW_SCORE_INVALID", "阅卷分数必须在 0 到题目分值之间");
      }
      repository.reviewAnswer(user.tenantId(), answer.answerId(), score, answer.comment(), user.id());
    }
    BigDecimal total = repository.attemptScore(user.tenantId(), attemptId);
    boolean passed = total.compareTo(repository.attemptPassScore(user.tenantId(), attemptId)) >= 0;
    repository.completeReview(user.tenantId(), attemptId, total, passed, request == null ? null : request.reviewNote(), user.id());
    ExamResultResponse result = repository.results(
            user.tenantId(), List.of(task.storeId()), null).stream()
        .filter(item -> item.attemptId() == attemptId).findFirst()
        .orElseThrow(() -> notFound("RESULT_NOT_FOUND", "考试结果不存在"));
    if (result.userId() != null) repository.syncWrongQuestions(user.tenantId(), result.userId(), attemptId);
    audit(user, "完成考试阅卷", "training_exam_attempt", attemptId, task.examineeName());
    return reviewDetail(user, attemptId);
  }

  public List<ExamResultResponse> results(AuthUser user) {
    accessControl.requireExamRead(user);
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (scope.allowsAllStores()) {
      return repository.results(user.tenantId(), (java.util.Collection<String>) null, null);
    }
    if (DataScopeModes.SELF.equals(scope.mode())) {
      return repository.results(user.tenantId(), (java.util.Collection<String>) null, user.id());
    }
    return repository.results(user.tenantId(), Set.copyOf(scope.storeIds()), null);
  }

  public List<WrongQuestionResponse> wrongQuestions(AuthUser user) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    return repository.wrongQuestions(user.tenantId(), user.id());
  }

  @Transactional
  public void markWrongQuestion(AuthUser user, long wrongId, boolean mastered) {
    accessControl.requireExamRead(user);
    requireReadScope(user);
    if (repository.markWrongQuestionMastered(user.tenantId(), user.id(), wrongId, mastered) == 0) {
      throw notFound("WRONG_QUESTION_NOT_FOUND", "错题记录不存在");
    }
    audit(user, mastered ? "标记错题已掌握" : "恢复错题练习", "training_exam_wrong_question", wrongId, null);
  }

  public EncodingCheckResponse encodingCheck(AuthUser user) {
    accessControl.requireExamManage(user);
    requireManageScope(user);
    return repository.encodingCheck(user.tenantId());
  }

  private boolean canManage(AuthUser user) {
    return accessControl.hasPermission(user, PermissionCodes.EXAM_MANAGE);
  }

  private DataScope requireReadScope(AuthUser user) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (scope.allowsAllStores()
        || DataScopeModes.SELF.equals(scope.mode())
        || !scope.storeIds().isEmpty()) {
      return scope;
    }
    throw new BusinessException(
        "FORBIDDEN", "当前账号没有培训考试数据范围", HttpStatus.FORBIDDEN);
  }

  private DataScope requireManageScope(AuthUser user) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (scope.allowsAllStores() || !scope.storeIds().isEmpty()) {
      return scope;
    }
    throw new BusinessException(
        "FORBIDDEN", "当前账号没有可管理的培训考试数据范围", HttpStatus.FORBIDDEN);
  }

  private void requireTaskScope(AuthUser user, ReviewTaskResponse task, String action) {
    accessControl.requireStoreAccess(
        user, DataScopeDomains.EXAM, task.storeId(), action);
  }

  private String normalizedCode(String value, String prefix) {
    String code = blankToNull(value);
    if (code == null) {
      return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
    code = code.toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z0-9_-]{3,100}")) {
      throw bad("CODE_INVALID", "编号只能包含字母、数字、下划线或短横线");
    }
    return code;
  }

  private String normalizeQuestionType(String value) {
    String type = normalizeRole(value);
    if (!Set.of("SINGLE_CHOICE", "TEXT", "NUMBER", "ESSAY").contains(type)) {
      throw bad("QUESTION_TYPE_INVALID", "题型不支持");
    }
    return type;
  }

  private String normalizeDifficulty(String value) {
    String difficulty = normalizeRole(value);
    if (difficulty.isBlank()) return "MEDIUM";
    if (!Set.of("EASY", "MEDIUM", "HARD").contains(difficulty)) {
      throw bad("QUESTION_DIFFICULTY_INVALID", "题目难度不支持");
    }
    return difficulty;
  }

  private List<Long> distinctIds(List<Long> values) {
    return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String required(String value, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) throw bad("FIELD_REQUIRED", message);
    return normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String normalizeRole(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private BusinessException bad(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException notFound(String code, String message) {
    return new BusinessException(code, message, HttpStatus.NOT_FOUND);
  }

  private void audit(AuthUser user, String action, String targetType, long targetId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(
        action, targetType, Long.toString(targetId), user.storeId(), null, reason, null, null));
  }
}
