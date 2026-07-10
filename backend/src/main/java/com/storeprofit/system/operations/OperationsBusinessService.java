package com.storeprofit.system.operations;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAnswerRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAnswerResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsBusinessService {
  private final OperationsBusinessRepository repository;

  public OperationsBusinessService(OperationsBusinessRepository repository) {
    this.repository = repository;
  }

  public List<InventoryCheckResponse> inventoryChecks(AuthUser user) {
    requireInventoryRead(user);
    return repository.inventoryChecks(user.tenantId(), scopedStoreId(user));
  }

  public InventoryCheckResponse inventoryCheck(AuthUser user, long id) {
    requireInventoryRead(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireStoreScope(user, check.storeId(), "店长只能查看本门店盘存单");
    return check;
  }

  @Transactional
  public InventoryCheckResponse saveInventoryCheck(AuthUser user, InventoryCheckRequest request) {
    requireInventorySave(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "请填写盘存单", HttpStatus.BAD_REQUEST);
    }
    String storeId = normalizeStoreForWrite(user, request.storeId());
    String storeName = repository.storeName(user.tenantId(), storeId)
        .orElseThrow(() -> new BusinessException("STORE_NOT_FOUND", "门店不存在", HttpStatus.BAD_REQUEST));
    String checkDate = normalizeDate(request.checkDate());
    List<InventoryCheckLineRequest> lines = request.lines() == null ? List.of() : request.lines();
    if (lines.isEmpty()) {
      throw new BusinessException("LINES_REQUIRED", "请至少填写一条盘存明细", HttpStatus.BAD_REQUEST);
    }
    for (InventoryCheckLineRequest line : lines) {
      if (line.itemName() == null || line.itemName().isBlank()) {
        throw new BusinessException("ITEM_NAME_REQUIRED", "请填写盘存物品名称", HttpStatus.BAD_REQUEST);
      }
      if (amount(line.countedQuantity()).compareTo(BigDecimal.ZERO) < 0) {
        throw new BusinessException("BAD_QUANTITY", "盘存数量不能小于 0", HttpStatus.BAD_REQUEST);
      }
    }
    if (request.id() != null) {
      InventoryCheckResponse existing = requireInventoryCheck(user.tenantId(), request.id());
      requireStoreScope(user, existing.storeId(), "店长只能修改本门店盘存单");
      if (!"DRAFT".equals(existing.status())) {
        throw new BusinessException("BAD_STATUS", "只有草稿盘存单可以修改", HttpStatus.CONFLICT);
      }
    }
    BigDecimal total = lines.stream()
        .map(line -> amount(line.countedQuantity()).multiply(amount(line.unitPrice())))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    long id = repository.saveInventoryCheck(
        user.tenantId(),
        request.id(),
        request.id() == null ? newInventoryCheckNo() : requireInventoryCheck(user.tenantId(), request.id()).checkNo(),
        storeId,
        storeName,
        checkDate,
        request.note(),
        total,
        user.id(),
        lines
    );
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "保存盘存单", "store_inventory_check", String.valueOf(id), storeId, "Vue3 运营中心");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse submitInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireStoreScope(user, check.storeId(), "店长只能提交本门店盘存单");
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "DRAFT", "SUBMITTED", user.id())) {
      throw new BusinessException("BAD_STATUS", "只有草稿盘存单可以提交", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "提交盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "等待运营或财务复核");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse reviewInventoryCheck(AuthUser user, long id) {
    requireInventoryReview(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "SUBMITTED", "REVIEWED", user.id())) {
      throw new BusinessException("BAD_STATUS", "只有已提交盘存单可以复核", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "复核盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "盘存单已复核");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse cancelInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireStoreScope(user, check.storeId(), "店长只能作废本门店盘存单");
    if (!repository.updateInventoryCheckStatus(user.tenantId(), id, "DRAFT", "CANCELLED", user.id())) {
      throw new BusinessException("BAD_STATUS", "已复核盘存单不能作废", HttpStatus.CONFLICT);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "作废盘存单", "store_inventory_check", String.valueOf(id), check.storeId(), "盘存单已作废");
    return requireInventoryCheck(user.tenantId(), id);
  }

  public List<ExamPaperResponse> examPapers(AuthUser user) {
    requireExamAccess(user);
    return repository.examPapers(user.tenantId());
  }

  public ExamPaperResponse examPaper(AuthUser user, long paperId) {
    requireExamAccess(user);
    return repository.examPaper(user.tenantId(), paperId, true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public ExamAttemptResponse submitExamAttempt(AuthUser user, ExamAttemptRequest request) {
    requireExamAccess(user);
    requireLegacyExamSubmit(user);
    if (request == null || request.paperId() == null) {
      throw new BusinessException("PAPER_REQUIRED", "请选择考试试卷", HttpStatus.BAD_REQUEST);
    }
    ExamPaperResponse paper = repository.examPaper(user.tenantId(), request.paperId(), true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
    List<OperationsBusinessRepository.QuestionForGrade> questions = repository.questionsForGrade(user.tenantId(), request.paperId());
    if (questions.isEmpty()) {
      throw new BusinessException("QUESTION_EMPTY", "试卷暂无题目，不能提交考试", HttpStatus.CONFLICT);
    }
    Map<Long, String> answerMap = new HashMap<>();
    for (ExamAnswerRequest answer : request.answers() == null ? List.<ExamAnswerRequest>of() : request.answers()) {
      if (answer.questionId() != null) {
        answerMap.put(answer.questionId(), answer.userAnswer());
      }
    }
    String storeId = normalizeStoreForOptionalWrite(user, request.storeId());
    String storeName = storeId == null ? null : repository.storeName(user.tenantId(), storeId).orElse(null);
    BigDecimal score = BigDecimal.ZERO;
    Map<Long, Boolean> correctMap = new HashMap<>();
    for (OperationsBusinessRepository.QuestionForGrade question : questions) {
      String userAnswer = answerMap.get(question.id());
      boolean correct = isCorrect(question, userAnswer);
      correctMap.put(question.id(), correct);
      if (correct) {
        score = score.add(amount(question.score()));
      }
    }
    score = score.setScale(2, RoundingMode.HALF_UP);
    boolean passed = score.compareTo(amount(paper.passScore())) >= 0;
    long attemptId = repository.insertExamAttempt(
        user.tenantId(),
        paper.id(),
        paper.paperName(),
        blankOr(request.examineeName(), user.displayName()),
        user.role(),
        storeId,
        storeName,
        score,
        passed,
        Boolean.TRUE.equals(request.violated()),
        user.id()
    );
    for (OperationsBusinessRepository.QuestionForGrade question : questions) {
      String userAnswer = answerMap.get(question.id());
      boolean correct = Boolean.TRUE.equals(correctMap.get(question.id()));
      repository.insertExamAnswer(user.tenantId(), attemptId, question.id(), userAnswer, correct, correct ? amount(question.score()) : BigDecimal.ZERO);
    }
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "提交考试", "training_exam_attempt", String.valueOf(attemptId), storeId, passed ? "考试通过" : "考试未通过");
    return repository.examAttempt(user.tenantId(), attemptId)
        .orElseThrow(() -> new BusinessException("ATTEMPT_NOT_FOUND", "考试记录不存在", HttpStatus.NOT_FOUND));
  }

  public List<ExamAttemptResponse> examAttempts(AuthUser user) {
    requireExamAccess(user);
    if ("STORE_MANAGER".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), requiredStore(user), null);
    }
    if ("EMPLOYEE".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), null, user.id());
    }
    return repository.examAttempts(user.tenantId(), null, null);
  }

  public ExamAttemptResponse examAttempt(AuthUser user, long attemptId) {
    requireExamAccess(user);
    ExamAttemptResponse attempt = repository.examAttempt(user.tenantId(), attemptId)
        .orElseThrow(() -> new BusinessException("ATTEMPT_NOT_FOUND", "考试记录不存在", HttpStatus.NOT_FOUND));
    requireStoreScope(user, attempt.storeId(), "店长只能查看本门店考试记录");
    if ("EMPLOYEE".equals(user.role()) && !Long.valueOf(user.id()).equals(attempt.submittedBy())) {
      throw new BusinessException("FORBIDDEN", "员工只能查看自己的考试成绩", HttpStatus.FORBIDDEN);
    }
    return attempt;
  }

  public List<TrainingMaterialResponse> trainingMaterials(AuthUser user) {
    requireTrainingAccess(user);
    return repository.trainingMaterials(user.tenantId(), user.id());
  }

  @Transactional
  public List<TrainingMaterialResponse> markMaterialLearned(AuthUser user, long materialId) {
    requireTrainingAccess(user);
    if (!repository.materialExists(user.tenantId(), materialId)) {
      throw new BusinessException("MATERIAL_NOT_FOUND", "培训资料不存在", HttpStatus.NOT_FOUND);
    }
    repository.markMaterialLearned(user.tenantId(), materialId, user.id(), user.displayName(), user.storeId());
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "标记培训已学习", "training_material", String.valueOf(materialId), user.storeId(), "培训学习记录已落库");
    return trainingMaterials(user);
  }

  public List<TrainingLearningRecordResponse> learningRecords(AuthUser user) {
    requireTrainingRecordRead(user);
    return repository.learningRecords(user.tenantId(), scopedStoreId(user));
  }

  private InventoryCheckResponse requireInventoryCheck(long tenantId, long id) {
    return repository.inventoryCheck(tenantId, id)
        .orElseThrow(() -> new BusinessException("INVENTORY_CHECK_NOT_FOUND", "盘存单不存在", HttpStatus.NOT_FOUND));
  }

  private String normalizeStoreForWrite(AuthUser user, String requestedStoreId) {
    if ("STORE_MANAGER".equals(user.role())) {
      return requiredStore(user);
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      throw new BusinessException("STORE_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    return requestedStoreId.trim();
  }

  private String normalizeStoreForOptionalWrite(AuthUser user, String requestedStoreId) {
    if ("STORE_MANAGER".equals(user.role())) {
      return requiredStore(user);
    }
    return requestedStoreId == null || requestedStoreId.isBlank() ? null : requestedStoreId.trim();
  }

  private String scopedStoreId(AuthUser user) {
    return "STORE_MANAGER".equals(user.role()) ? requiredStore(user) : null;
  }

  private void requireStoreScope(AuthUser user, String storeId, String message) {
    if ("STORE_MANAGER".equals(user.role()) && !requiredStore(user).equals(storeId)) {
      throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }
  }

  private String requiredStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "当前账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return user.storeId();
  }

  private void requireInventoryRead(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "FINANCE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权查看盘存单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireInventorySave(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权保存盘存单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireInventoryReview(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "FINANCE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权复核盘存单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireExamAccess(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "OPS").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权访问考试系统", HttpStatus.FORBIDDEN);
    }
  }

  private void requireLegacyExamSubmit(AuthUser user) {
    if (!List.of("ADMIN", "OPERATIONS", "OPS").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "请从分配给你的考试任务进入答题", HttpStatus.FORBIDDEN);
    }
  }

  private void requireTrainingAccess(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权访问培训资料", HttpStatus.FORBIDDEN);
    }
  }

  private void requireTrainingRecordRead(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "OPERATIONS", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权查看培训学习记录", HttpStatus.FORBIDDEN);
    }
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
      String k = keyword.trim();
      if (!k.isBlank() && normalized.contains(k)) {
        return true;
      }
    }
    return false;
  }

  private String normalizeDate(String value) {
    if (value == null || value.isBlank()) {
      return LocalDate.now().toString();
    }
    try {
      return LocalDate.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_DATE", "日期格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private String newInventoryCheckNo() {
    String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    return "PDC" + LocalDate.now().toString().replace("-", "") + shortId;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String blankOr(String value, String fallback) {
    String normalized = blankToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
