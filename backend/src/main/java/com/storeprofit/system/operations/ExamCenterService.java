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
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
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
  private static final int RETAKE_COOLDOWN_DAYS = 7;
  private static final Set<String> TARGET_ROLES = Set.of(
      "EMPLOYEE", "STORE_MANAGER", "SUPERVISOR", "WAREHOUSE", "FINANCE");

  private final ExamCenterRepository repository;
  private final OperationsBusinessRepository operationsRepository;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;
  private final ExamLearningRepository learningRepository;

  public ExamCenterService(
      ExamCenterRepository repository,
      OperationsBusinessRepository operationsRepository,
      AccessControlService accessControl,
      AuditRepository auditRepository,
      ExamLearningRepository learningRepository
  ) {
    this.repository = repository;
    this.operationsRepository = operationsRepository;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
    this.learningRepository = learningRepository;
  }

  public ExamCenterOverviewResponse overview(AuthUser user) {
    accessControl.requireExamRead(user);
    Scope scope = scope(user);
    boolean canManage = accessControl.hasPermission(user, PermissionCodes.EXAM_MANAGE)
        && (scope.companyWide() || !scope.storeIds().isEmpty());
    boolean companyView = scope.companyWide()
        && accessControl.hasPermission(user, PermissionCodes.EXAM_REPORT);
    ExamCenterOverviewResponse response = new ExamCenterOverviewResponse(
        scope.mode(),
        canManage,
        companyView,
        canManage ? repository.paperSummaries(user.tenantId(), true) : List.of(),
        repository.campaigns(user.tenantId(), scope.repositoryStoreIds(), scope.userId()),
        repository.assignments(user.tenantId(), scope.repositoryStoreIds(), scope.userId()),
        canManage
            ? repository.candidates(user.tenantId(), scope.candidateStoreIds())
            : List.of()
    );
    writeAudit(
        user,
        "查看考试中心",
        "training_exam_campaign",
        null,
        scope.auditStoreId(),
        "按账号考试权限和数据范围查询"
    );
    return response;
  }

  public ExamPaperEditorResponse paperForEdit(AuthUser user, long paperId) {
    accessControl.requireExamManage(user);
    requireManageScope(user, "编辑考试试卷");
    return repository.paperForEdit(user.tenantId(), paperId)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public ExamPaperEditorResponse savePaper(AuthUser user, ExamPaperSaveRequest request) {
    accessControl.requireExamManage(user);
    requireManageScope(user, "保存考试试卷");
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
    Scope scope = requireManageScope(user, "发布考试");
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
    if (!scope.companyWide() && !scope.storeIds().containsAll(requestedStores)) {
      throw new BusinessException(
          "EXAM_TARGET_SCOPE_INVALID", "应考门店超出当前账号的数据范围", HttpStatus.FORBIDDEN);
    }
    Set<String> requestedRoles = normalizeRoles(request.targetRoles());
    if (requestedUsers.isEmpty() && requestedStores.isEmpty()) {
      throw new BusinessException("TARGET_REQUIRED", "请选择应考门店或应考人员", HttpStatus.BAD_REQUEST);
    }
    if (requestedUsers.isEmpty() && requestedRoles.isEmpty()) {
      requestedRoles = Set.of("EMPLOYEE", "STORE_MANAGER");
    }
    Set<String> targetRoles = requestedRoles;

    List<ExamCandidateResponse> selected = repository
        .candidates(user.tenantId(), scope.candidateStoreIds()).stream()
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
    var scopedCampaign = repository.campaign(
        user.tenantId(), campaignId, scope.repositoryStoreIds(), scope.userId());
    if (scopedCampaign.isEmpty()) {
      boolean exists = repository.campaign(
          user.tenantId(), campaignId, (Collection<String>) null, null).isPresent();
      if (!exists) {
        throw new BusinessException("EXAM_NOT_FOUND", "考试不存在", HttpStatus.NOT_FOUND);
      }
      accessControl.requireExamCampaignScope(user, false, campaignId);
    }
    ExamCampaignResponse campaign = scopedCampaign.orElseThrow();
    List<ExamAssignmentResponse> assignments = repository.assignmentsForCampaign(
        user.tenantId(), campaignId, scope.repositoryStoreIds(), scope.userId());
    if (audit) {
      writeAudit(
          user,
          "查看考试成绩",
          "training_exam_campaign",
          Long.toString(campaignId),
          scope.auditStoreId(),
          campaign.title()
      );
    }
    return new ExamCampaignDetailResponse(campaign, assignments);
  }

  public ExamPaperResponse assignmentPaper(AuthUser user, long assignmentId) {
    accessControl.requireExamRead(user);
    ExamAssignmentResponse assignment = requireVisibleAssignment(user, assignmentId, false);
    requireRetakeAvailable(assignment);
    return operationsRepository.examPaper(user.tenantId(), assignment.paperId(), true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  private void requireRetakeAvailable(ExamAssignmentResponse assignment) {
    if (!"RETAKE_PENDING".equals(assignment.status())) return;
    String availableAt = assignment.retakeAvailableAt() == null ? "" : assignment.retakeAvailableAt();
    throw new BusinessException(
        "EXAM_RETAKE_WAIT",
        "本场考试因切屏违规已作废，需等待 7 天后重考"
            + (availableAt.isBlank() ? "" : "（可重考时间：" + availableAt + "）"),
        HttpStatus.CONFLICT);
  }

  @Transactional
  public ExamAttemptResponse submit(AuthUser user, long assignmentId, ExamSubmissionRequest request) {
    accessControl.requireExamRead(user);
    ExamAssignmentResponse assignment = repository.assignment(user.tenantId(), assignmentId, true)
        .orElseThrow(() -> new BusinessException("ASSIGNMENT_NOT_FOUND", "考试任务不存在", HttpStatus.NOT_FOUND));
    accessControl.requireOwnExamAssignment(user, assignment.userId(), assignmentId);
    if (List.of("COMPLETED", "REVIEW_PENDING", "RETAKE_PENDING").contains(assignment.status())
        && assignment.attemptId() != null) {
      // A mobile client may retry after the first response is lost on a weak network. The
      // assignment row is locked above, so returning its immutable latest attempt prevents a
      // duplicate attempt and keeps the original audit entry as the only submission log.
      return operationsRepository.examAttempt(user.tenantId(), assignment.attemptId())
          .orElseThrow(() -> new BusinessException(
              "ATTEMPT_NOT_FOUND", "考试已提交，但成绩记录暂不可用", HttpStatus.CONFLICT));
    }
    if ("COMPLETED".equals(assignment.status())) {
      throw new BusinessException("EXAM_COMPLETED", "该考试已经提交，不能重复作答", HttpStatus.CONFLICT);
    }
    requireRetakeAvailable(assignment);
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
    boolean violatedSubmission = request != null && Boolean.TRUE.equals(request.violated());
    if (!violatedSubmission) {
      for (OperationsBusinessRepository.QuestionForGrade question : questions) {
        String answer = answers.get(question.id());
        if (answer == null || answer.isBlank()) {
          throw new BusinessException(
              "EXAM_ANSWERS_INCOMPLETE", "试卷所有题目均为必答，请完成全部题目后再提交", HttpStatus.BAD_REQUEST);
        }
      }
    }
    BigDecimal score = BigDecimal.ZERO;
    boolean requiresReview = false;
    Map<Long, Boolean> correctMap = new HashMap<>();
    for (OperationsBusinessRepository.QuestionForGrade question : questions) {
      if ("ESSAY".equals(question.questionType())) {
        requiresReview = true;
      }
      boolean correct = isCorrect(question, answers.get(question.id()));
      correctMap.put(question.id(), correct);
      if (correct) {
        score = score.add(amount(question.score()));
      }
    }
    score = score.setScale(2, RoundingMode.HALF_UP);
    boolean violated = violatedSubmission;
    boolean passed = !requiresReview && !violated && score.compareTo(amount(paper.passScore())) >= 0;
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
    learningRepository.createAttemptReview(
        user.tenantId(), attemptId, requiresReview && !violated ? "PENDING" : "AUTO_GRADED");
    boolean assignmentUpdated;
    if (violated) {
      assignmentUpdated = repository.scheduleAssignmentRetake(
          user.tenantId(), assignmentId, attemptId, score, now.plusDays(RETAKE_COOLDOWN_DAYS));
    } else if (requiresReview) {
      assignmentUpdated = repository.submitAssignmentForReview(user.tenantId(), assignmentId, attemptId, score);
    } else {
      assignmentUpdated = repository.completeAssignment(user.tenantId(), assignmentId, attemptId, score, passed);
    }
    if (!assignmentUpdated) {
      throw new BusinessException("EXAM_ALREADY_SUBMITTED", "考试已被提交，请刷新后查看成绩", HttpStatus.CONFLICT);
    }
    if (!requiresReview && !violated) {
      learningRepository.syncWrongQuestions(user.tenantId(), user.id(), attemptId);
    }
    writeAudit(
        user,
        "提交考试",
        "training_exam_attempt",
        Long.toString(attemptId),
        assignment.storeId(),
        violated ? "切屏违规，考试作废，" + RETAKE_COOLDOWN_DAYS + " 天后可重考"
            : requiresReview ? "考试已提交，等待阅卷" : passed ? "考试通过" : "考试未通过"
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
    Scope scope = scope(user);
    if (scope.companyWide()) {
      return assignment;
    }
    if (scope.selfOnly()) {
      accessControl.requireOwnExamAssignment(user, assignment.userId(), assignmentId);
      return assignment;
    }
    if (scope.storeIds().contains(assignment.storeId())) {
      return assignment;
    }
    accessControl.requireStoreAccess(
        user, DataScopeDomains.EXAM, assignment.storeId(), "查看考试任务");
    return assignment;
  }

  private Scope scope(AuthUser user) {
    DataScope dataScope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (dataScope.allowsAllStores()) {
      return new Scope("COMPANY", List.of(), null);
    }
    if (DataScopeModes.SELF.equals(dataScope.mode())) {
      return new Scope("SELF", List.of(), user.id());
    }
    if (!dataScope.storeIds().isEmpty()) {
      return new Scope(
          DataScopeModes.OWN_STORE.equals(dataScope.mode()) ? "STORE" : "STORE_LIST",
          dataScope.storeIds(),
          null
      );
    }
    return new Scope("NONE", List.of(), null);
  }

  private Scope requireManageScope(AuthUser user, String action) {
    Scope scope = scope(user);
    if (scope.companyWide() || !scope.storeIds().isEmpty()) {
      return scope;
    }
    throw new BusinessException(
        "FORBIDDEN", "当前账号没有可用于" + action + "的数据范围", HttpStatus.FORBIDDEN);
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
      questions.add(new ExamQuestionSaveRequest(item.bankQuestionId(), type, text, options, answer, blankToNull(item.acceptKeywords()), score));
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

  static boolean isCorrect(OperationsBusinessRepository.QuestionForGrade question, String userAnswer) {
    if ("ESSAY".equals(question.questionType())) {
      return false;
    }
    String normalized = normalizeForMatch(userAnswer);
    if (normalized == null) {
      return false;
    }
    String standard = normalizeForMatch(question.standardAnswer());
    if (standard != null && standard.equalsIgnoreCase(normalized)) {
      return true;
    }
    String keywords = question.acceptKeywords() == null || question.acceptKeywords().isBlank()
        ? null : question.acceptKeywords();
    if (keywords == null) {
      return false;
    }
    List<String> numericKeywords = new ArrayList<>();
    List<String> textKeywords = new ArrayList<>();
    for (String keyword : keywords.split("[,，\\n]")) {
      String value = normalizeForMatch(keyword);
      if (value == null) continue;
      if (value.chars().anyMatch(Character::isDigit)) numericKeywords.add(value);
      else textKeywords.add(value);
    }
    if (numericKeywords.isEmpty() && textKeywords.isEmpty()) return false;
    for (String keyword : numericKeywords) {
      if (!normalized.contains(keyword)) return false;
    }
    if (textKeywords.isEmpty()) return true;
    for (String keyword : textKeywords) {
      if (normalized.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeForMatch(String value) {
    if (value == null) return null;
    StringBuilder builder = new StringBuilder(value.length());
    for (char ch : value.toCharArray()) {
      if (Character.isWhitespace(ch)) continue;
      char mapped = switch (ch) {
        case '０' -> '0'; case '１' -> '1'; case '２' -> '2'; case '３' -> '3'; case '４' -> '4';
        case '５' -> '5'; case '６' -> '6'; case '７' -> '7'; case '８' -> '8'; case '９' -> '9';
        case '：' -> ':'; case '．' -> '.'; case '％' -> '%'; case '＋' -> '+'; case '－' -> '-';
        default -> ch;
      };
      builder.append(Character.toLowerCase(mapped));
    }
    return builder.isEmpty() ? null : builder.toString();
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
    if (!Set.of("SINGLE_CHOICE", "TEXT", "NUMBER", "ESSAY").contains(type)) {
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

  private record Scope(String mode, List<String> storeIds, Long userId) {
    private Scope {
      storeIds = storeIds == null ? List.of() : storeIds.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .distinct()
          .sorted()
          .toList();
    }

    boolean companyWide() {
      return "COMPANY".equals(mode);
    }

    boolean selfOnly() {
      return "SELF".equals(mode);
    }

    Collection<String> repositoryStoreIds() {
      return companyWide() || selfOnly() ? null : storeIds;
    }

    Collection<String> candidateStoreIds() {
      return companyWide() ? null : storeIds;
    }

    String auditStoreId() {
      return storeIds.size() == 1 ? storeIds.getFirst() : null;
    }
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
