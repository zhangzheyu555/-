package com.storeprofit.system.operations;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.ExamCenterModels.ExamAnswerInput;
import com.storeprofit.system.operations.ExamCenterModels.ExamAssignmentResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCampaignDetailResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCampaignResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCandidateResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamCenterOverviewResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperEditorResponse;
import com.storeprofit.system.operations.ExamCenterModels.ExamPaperSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamPublishRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamQuestionSaveRequest;
import com.storeprofit.system.operations.ExamCenterModels.ExamSubmissionRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamCenterService {
  private static final Set<String> COMPANY_ROLES = Set.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "OPS");
  private static final Set<String> MANAGE_ROLES = Set.of("ADMIN", "OPERATIONS", "OPS");
  private static final Set<String> TARGET_ROLES = Set.of(
      "EMPLOYEE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "FINANCE");

  private final ExamCenterRepository repository;
  private final OperationsBusinessRepository operationsRepository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public ExamCenterService(
      ExamCenterRepository repository,
      OperationsBusinessRepository operationsRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.operationsRepository = operationsRepository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  public ExamCenterOverviewResponse overview(AuthUser user) {
    accessControl.requireExamRead(user);
    Scope scope = scope(user);
    boolean canManage = MANAGE_ROLES.contains(normalizeRole(user.role()));
    boolean companyView = COMPANY_ROLES.contains(normalizeRole(user.role()));
    ExamCenterOverviewResponse response = new ExamCenterOverviewResponse(
        scope.mode(),
        canManage,
        companyView,
        companyView ? repository.paperSummaries(user.tenantId(), canManage) : List.of(),
        repository.campaigns(user.tenantId(), scope.storeId(), scope.userId()),
        repository.assignments(user.tenantId(), scope.storeId(), scope.userId()),
        canManage ? repository.candidates(user.tenantId()) : List.of()
    );
    writeAudit(user, "查看考试中心", "training_exam_campaign", null, scope.storeId(), "按当前角色数据范围查询考试");
    return response;
  }

  public ExamPaperEditorResponse paperForEdit(AuthUser user, long paperId) {
    accessControl.requireExamManage(user);
    return repository.paperForEdit(user.tenantId(), paperId)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public ExamPaperEditorResponse savePaper(AuthUser user, ExamPaperSaveRequest request) {
    accessControl.requireExamManage(user);
    ValidatedPaper paper = validatePaper(request);
    long paperId;
    if (request.id() == null) {
      paperId = repository.insertPaper(
          user.tenantId(), paper.code(), paper.name(), paper.roleScope(), paper.passScore(), paper.enabled());
    } else {
      paperId = request.id();
      if (repository.attemptCount(user.tenantId(), paperId) > 0) {
        throw new BusinessException(
            "PAPER_ALREADY_USED",
            "该试卷已有考试成绩，请新建试卷后再发布",
            HttpStatus.CONFLICT
        );
      }
      if (!repository.updatePaper(
          user.tenantId(), paperId, paper.code(), paper.name(), paper.roleScope(), paper.passScore(), paper.enabled())) {
        throw new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND);
      }
    }
    repository.replaceQuestions(user.tenantId(), paperId, paper.questions());
    writeAudit(
        user,
        request.id() == null ? "新建考试试卷" : "修改考试试卷",
        "training_exam_paper",
        Long.toString(paperId),
        null,
        paper.name()
    );
    return repository.paperForEdit(user.tenantId(), paperId)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public ExamCampaignDetailResponse publish(AuthUser user, ExamPublishRequest request) {
    accessControl.requireExamManage(user);
    if (request == null || request.paperId() == null) {
      throw new BusinessException("PAPER_REQUIRED", "请选择试卷", HttpStatus.BAD_REQUEST);
    }
    ExamPaperResponse paper = operationsRepository.examPaper(user.tenantId(), request.paperId(), false)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在或已停用", HttpStatus.BAD_REQUEST));
    LocalDateTime startAt = parseDateTime(request.startAt(), "请填写考试开始时间");
    LocalDateTime dueAt = parseDateTime(request.dueAt(), "请填写考试截止时间");
    if (!dueAt.isAfter(startAt)) {
      throw new BusinessException("BAD_EXAM_TIME", "考试截止时间必须晚于开始时间", HttpStatus.BAD_REQUEST);
    }
    String title = required(request.title(), "请填写考试名称");
    if (title.length() > 160) {
      throw new BusinessException("TITLE_TOO_LONG", "考试名称不能超过 160 个字符", HttpStatus.BAD_REQUEST);
    }

    Set<Long> requestedUsers = normalizeIds(request.userIds());
    Set<String> requestedStores = normalizeStrings(request.storeIds());
    Set<String> requestedRoles = normalizeRoles(request.targetRoles());
    if (requestedUsers.isEmpty() && requestedStores.isEmpty()) {
      throw new BusinessException("TARGET_REQUIRED", "请选择应考门店或应考人员", HttpStatus.BAD_REQUEST);
    }
    if (requestedUsers.isEmpty() && requestedRoles.isEmpty()) {
      requestedRoles = Set.of("EMPLOYEE", "STORE_MANAGER");
    }
    Set<String> targetRoles = requestedRoles;

    List<ExamCandidateResponse> selected = repository.candidates(user.tenantId()).stream()
        .filter(candidate -> requestedUsers.isEmpty() || requestedUsers.contains(candidate.userId()))
        .filter(candidate -> !requestedUsers.isEmpty() || requestedStores.contains(candidate.storeId()))
        .filter(candidate -> !requestedUsers.isEmpty() || targetRoles.contains(candidate.role()))
        .distinct()
        .toList();
    if (selected.isEmpty()) {
      throw new BusinessException("NO_EXAMINEE", "当前范围内没有可分配考试的账号", HttpStatus.CONFLICT);
    }
    if (!requestedUsers.isEmpty() && selected.size() != requestedUsers.size()) {
      throw new BusinessException("EXAMINEE_SCOPE_INVALID", "应考人员中包含无效或无门店归属的账号", HttpStatus.BAD_REQUEST);
    }

    String targetRoleText = selected.stream()
        .map(ExamCandidateResponse::role)
        .distinct()
        .sorted()
        .reduce((left, right) -> left + "," + right)
        .orElse("");
    long campaignId = repository.insertCampaign(
        user.tenantId(), paper.id(), title, startAt, dueAt, targetRoleText, user.id());
    repository.insertAssignments(user.tenantId(), campaignId, dueAt, selected);
    writeAudit(
        user,
        "发布考试",
        "training_exam_campaign",
        Long.toString(campaignId),
        null,
        title + "，应考 " + selected.size() + " 人"
    );
    return campaignDetail(user, campaignId, false);
  }

  public ExamCampaignDetailResponse campaignDetail(AuthUser user, long campaignId) {
    return campaignDetail(user, campaignId, true);
  }

  private ExamCampaignDetailResponse campaignDetail(AuthUser user, long campaignId, boolean audit) {
    accessControl.requireExamRead(user);
    Scope scope = scope(user);
    ExamCampaignResponse companyCampaign = repository.campaign(user.tenantId(), campaignId, null, null)
        .orElseThrow(() -> new BusinessException("EXAM_NOT_FOUND", "考试不存在", HttpStatus.NOT_FOUND));
    ExamCampaignResponse campaign = companyCampaign;
    if (!"COMPANY".equals(scope.mode())) {
      var scopedCampaign = repository.campaign(user.tenantId(), campaignId, scope.storeId(), scope.userId());
      accessControl.requireExamCampaignScope(user, scopedCampaign.isPresent(), campaignId);
      campaign = scopedCampaign.orElseThrow();
    }
    List<ExamAssignmentResponse> assignments = repository.assignmentsForCampaign(
        user.tenantId(), campaignId, scope.storeId(), scope.userId());
    if (audit) {
      writeAudit(user, "查看考试成绩", "training_exam_campaign", Long.toString(campaignId), scope.storeId(), campaign.title());
    }
    return new ExamCampaignDetailResponse(campaign, assignments);
  }

  public ExamPaperResponse assignmentPaper(AuthUser user, long assignmentId) {
    accessControl.requireExamRead(user);
    ExamAssignmentResponse assignment = requireVisibleAssignment(user, assignmentId, false);
    return operationsRepository.examPaper(user.tenantId(), assignment.paperId(), true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public ExamAttemptResponse submit(AuthUser user, long assignmentId, ExamSubmissionRequest request) {
    accessControl.requireExamRead(user);
    ExamAssignmentResponse assignment = repository.assignment(user.tenantId(), assignmentId, true)
        .orElseThrow(() -> new BusinessException("ASSIGNMENT_NOT_FOUND", "考试任务不存在", HttpStatus.NOT_FOUND));
    accessControl.requireOwnExamAssignment(user, assignment.userId(), assignmentId);
    if ("COMPLETED".equals(assignment.status())) {
      throw new BusinessException("EXAM_COMPLETED", "该考试已经提交，不能重复作答", HttpStatus.CONFLICT);
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime startAt = parseStoredDateTime(assignment.startAt());
    LocalDateTime dueAt = parseStoredDateTime(assignment.dueAt());
    if (now.isBefore(startAt)) {
      throw new BusinessException("EXAM_NOT_STARTED", "考试尚未开始", HttpStatus.CONFLICT);
    }
    if (now.isAfter(dueAt)) {
      throw new BusinessException("EXAM_OVERDUE", "考试已超过截止时间", HttpStatus.CONFLICT);
    }

    ExamPaperResponse paper = operationsRepository.examPaper(user.tenantId(), assignment.paperId(), true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
    List<OperationsBusinessRepository.QuestionForGrade> questions =
        operationsRepository.questionsForGrade(user.tenantId(), assignment.paperId());
    if (questions.isEmpty()) {
      throw new BusinessException("QUESTION_EMPTY", "试卷暂无题目，不能提交", HttpStatus.CONFLICT);
    }
    Map<Long, String> answers = answerMap(request == null ? null : request.answers());
    BigDecimal score = BigDecimal.ZERO;
    Map<Long, Boolean> correctMap = new HashMap<>();
    for (OperationsBusinessRepository.QuestionForGrade question : questions) {
      boolean correct = isCorrect(question, answers.get(question.id()));
      correctMap.put(question.id(), correct);
      if (correct) {
        score = score.add(amount(question.score()));
      }
    }
    score = score.setScale(2, RoundingMode.HALF_UP);
    boolean violated = request != null && Boolean.TRUE.equals(request.violated());
    boolean passed = !violated && score.compareTo(amount(paper.passScore())) >= 0;
    long attemptId = operationsRepository.insertExamAttempt(
        user.tenantId(),
        paper.id(),
        assignment.campaignId(),
        assignment.id(),
        paper.paperName(),
        assignment.examineeName(),
        assignment.examineeRole(),
        assignment.storeId(),
        assignment.storeName(),
        score,
        passed,
        violated,
        user.id()
    );
    for (OperationsBusinessRepository.QuestionForGrade question : questions) {
      boolean correct = Boolean.TRUE.equals(correctMap.get(question.id()));
      operationsRepository.insertExamAnswer(
          user.tenantId(),
          attemptId,
          question.id(),
          answers.get(question.id()),
          correct,
          correct ? amount(question.score()) : BigDecimal.ZERO
      );
    }
    if (!repository.completeAssignment(user.tenantId(), assignmentId, attemptId, score, passed)) {
      throw new BusinessException("EXAM_ALREADY_SUBMITTED", "考试已被提交，请刷新后查看成绩", HttpStatus.CONFLICT);
    }
    writeAudit(
        user,
        "提交考试",
        "training_exam_attempt",
        Long.toString(attemptId),
        assignment.storeId(),
        passed ? "考试通过" : violated ? "考试违规，未通过" : "考试未通过"
    );
    return operationsRepository.examAttempt(user.tenantId(), attemptId)
        .orElseThrow(() -> new BusinessException("ATTEMPT_NOT_FOUND", "考试成绩保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  public byte[] exportCampaign(AuthUser user, long campaignId) {
    accessControl.requireExamCompanyRead(user);
    ExamCampaignDetailResponse detail = campaignDetail(user, campaignId, false);
    StringBuilder csv = new StringBuilder("\ufeff考试,试卷,门店,应考人,角色,状态,分数,结果,截止时间,完成时间\r\n");
    for (ExamAssignmentResponse assignment : detail.assignments()) {
      csv.append(csv(detail.campaign().title())).append(',')
          .append(csv(assignment.paperName())).append(',')
          .append(csv(assignment.storeName())).append(',')
          .append(csv(assignment.examineeName())).append(',')
          .append(csv(roleLabel(assignment.examineeRole()))).append(',')
          .append(csv(assignment.statusLabel())).append(',')
          .append(csv(assignment.score() == null ? "" : assignment.score().toPlainString())).append(',')
          .append(csv(assignment.passed() == null ? "" : assignment.passed() ? "通过" : "未通过")).append(',')
          .append(csv(assignment.dueAt())).append(',')
          .append(csv(assignment.completedAt())).append("\r\n");
    }
    writeAudit(user, "导出考试成绩", "training_exam_campaign", Long.toString(campaignId), null, detail.campaign().title());
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  private ExamAssignmentResponse requireVisibleAssignment(AuthUser user, long assignmentId, boolean forUpdate) {
    ExamAssignmentResponse assignment = repository.assignment(user.tenantId(), assignmentId, forUpdate)
        .orElseThrow(() -> new BusinessException("ASSIGNMENT_NOT_FOUND", "考试任务不存在", HttpStatus.NOT_FOUND));
    String role = normalizeRole(user.role());
    if (COMPANY_ROLES.contains(role)) {
      return assignment;
    }
    if ("STORE_MANAGER".equals(role)) {
      accessControl.requireStoreAccess(user, assignment.storeId(), "查看考试任务");
      return assignment;
    }
    if ("EMPLOYEE".equals(role)) {
      accessControl.requireOwnExamAssignment(user, assignment.userId(), assignmentId);
      return assignment;
    }
    throw new BusinessException("FORBIDDEN", "当前账号无权查看该考试任务", HttpStatus.FORBIDDEN);
  }

  private Scope scope(AuthUser user) {
    String role = normalizeRole(user.role());
    if (COMPANY_ROLES.contains(role)) {
      return new Scope("COMPANY", null, null);
    }
    if ("STORE_MANAGER".equals(role)) {
      return new Scope("STORE", requiredStore(user), null);
    }
    return new Scope("SELF", null, user.id());
  }

  private ValidatedPaper validatePaper(ExamPaperSaveRequest request) {
    if (request == null) {
      throw new BusinessException("PAPER_REQUIRED", "请填写试卷", HttpStatus.BAD_REQUEST);
    }
    String code = blankToNull(request.paperCode());
    if (code == null) {
      code = "EXAM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    } else {
      code = code.toUpperCase(Locale.ROOT);
    }
    if (!code.matches("[A-Z0-9_-]{3,80}")) {
      throw new BusinessException("PAPER_CODE_INVALID", "试卷编号只能包含字母、数字、下划线或短横线", HttpStatus.BAD_REQUEST);
    }
    String name = required(request.paperName(), "请填写试卷名称");
    BigDecimal passScore = amount(request.passScore());
    if (passScore.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException("PASS_SCORE_INVALID", "通过分数不能小于 0", HttpStatus.BAD_REQUEST);
    }
    List<ExamQuestionSaveRequest> source = request.questions() == null ? List.of() : request.questions();
    if (source.isEmpty()) {
      throw new BusinessException("QUESTION_REQUIRED", "请至少添加一道题目", HttpStatus.BAD_REQUEST);
    }
    List<ExamQuestionSaveRequest> questions = new ArrayList<>();
    BigDecimal totalScore = BigDecimal.ZERO;
    for (ExamQuestionSaveRequest item : source) {
      if (item == null) {
        throw new BusinessException("QUESTION_REQUIRED", "题目内容不能为空", HttpStatus.BAD_REQUEST);
      }
      String type = normalizeQuestionType(item.questionType());
      String text = required(item.questionText(), "请填写题目内容");
      String answer = required(item.standardAnswer(), "请填写标准答案");
      BigDecimal score = amount(item.score());
      if (score.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("QUESTION_SCORE_INVALID", "每道题分值必须大于 0", HttpStatus.BAD_REQUEST);
      }
      List<String> options = item.options() == null ? List.of() : item.options().stream()
          .map(this::blankToNull)
          .filter(value -> value != null)
          .distinct()
          .toList();
      if ("SINGLE_CHOICE".equals(type) && (options.size() < 2 || !options.contains(answer))) {
        throw new BusinessException("QUESTION_OPTION_INVALID", "单选题至少需要两个选项，且标准答案必须在选项中", HttpStatus.BAD_REQUEST);
      }
      questions.add(new ExamQuestionSaveRequest(type, text, options, answer, blankToNull(item.acceptKeywords()), score));
      totalScore = totalScore.add(score);
    }
    if (passScore.compareTo(totalScore) > 0) {
      throw new BusinessException("PASS_SCORE_TOO_HIGH", "通过分数不能高于试卷总分", HttpStatus.BAD_REQUEST);
    }
    return new ValidatedPaper(
        code,
        name,
        blankToNull(request.roleScope()),
        passScore,
        request.enabled() == null || request.enabled(),
        questions
    );
  }

  private Map<Long, String> answerMap(List<ExamAnswerInput> values) {
    Map<Long, String> result = new HashMap<>();
    for (ExamAnswerInput value : values == null ? List.<ExamAnswerInput>of() : values) {
      if (value != null && value.questionId() != null) {
        result.put(value.questionId(), value.userAnswer());
      }
    }
    return result;
  }

  private boolean isCorrect(OperationsBusinessRepository.QuestionForGrade question, String userAnswer) {
    String normalized = blankToNull(userAnswer);
    if (normalized == null) {
      return false;
    }
    String standard = blankToNull(question.standardAnswer());
    if (standard != null && standard.equalsIgnoreCase(normalized)) {
      return true;
    }
    String keywords = blankToNull(question.acceptKeywords());
    if (keywords == null) {
      return false;
    }
    for (String keyword : keywords.split("[,，\\n]")) {
      String value = keyword.trim();
      if (!value.isBlank() && normalized.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private LocalDateTime parseDateTime(String value, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new BusinessException("EXAM_TIME_REQUIRED", message, HttpStatus.BAD_REQUEST);
    }
    try {
      return LocalDateTime.parse(normalized.replace(' ', 'T'));
    } catch (DateTimeParseException ex) {
      throw new BusinessException("EXAM_TIME_INVALID", "考试时间格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private LocalDateTime parseStoredDateTime(String value) {
    return LocalDateTime.parse(value.replace(' ', 'T'));
  }

  private Set<Long> normalizeIds(List<Long> values) {
    LinkedHashSet<Long> result = new LinkedHashSet<>();
    if (values != null) {
      values.stream().filter(value -> value != null && value > 0).forEach(result::add);
    }
    return result;
  }

  private Set<String> normalizeStrings(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (values != null) {
      values.stream().map(this::blankToNull).filter(value -> value != null).forEach(result::add);
    }
    return result;
  }

  private Set<String> normalizeRoles(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        String role = normalizeRole(value);
        if (!TARGET_ROLES.contains(role)) {
          throw new BusinessException("TARGET_ROLE_INVALID", "应考角色不正确", HttpStatus.BAD_REQUEST);
        }
        result.add(role);
      }
    }
    return result;
  }

  private String normalizeQuestionType(String value) {
    String type = normalizeRole(value);
    if (!Set.of("SINGLE_CHOICE", "TEXT", "NUMBER").contains(type)) {
      throw new BusinessException("QUESTION_TYPE_INVALID", "题型不正确", HttpStatus.BAD_REQUEST);
    }
    return type;
  }

  private String normalizeRole(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private String required(String value, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new BusinessException("FIELD_REQUIRED", message, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String requiredStore(AuthUser user) {
    String storeId = blankToNull(user.storeId());
    if (storeId == null) {
      throw new BusinessException("NO_STORE_SCOPE", "当前账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return storeId;
  }

  private void writeAudit(
      AuthUser user,
      String action,
      String targetType,
      String targetId,
      String storeId,
      String reason
  ) {
    auditRepository.writeLog(user, new AuditLogRequest(
        action, targetType, targetId, storeId, null, reason, null, null));
  }

  private String csv(String value) {
    String text = value == null ? "" : value;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private String roleLabel(String role) {
    return switch (normalizeRole(role)) {
      case "EMPLOYEE" -> "员工";
      case "STORE_MANAGER" -> "店长";
      case "SUPERVISOR" -> "督导";
      case "WAREHOUSE" -> "仓库管理员";
      case "FINANCE" -> "财务";
      default -> role;
    };
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record Scope(String mode, String storeId, Long userId) {
  }

  private record ValidatedPaper(
      String code,
      String name,
      String roleScope,
      BigDecimal passScore,
      boolean enabled,
      List<ExamQuestionSaveRequest> questions
  ) {
  }
}
