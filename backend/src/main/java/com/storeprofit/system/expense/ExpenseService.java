package com.storeprofit.system.expense;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {
  static final String STATUS_DRAFT = "草稿";
  static final String STATUS_PENDING = "待审核";
  static final String STATUS_SUPPLEMENT = "待补资料";
  static final String STATUS_APPROVED = "已完成";
  static final String STATUS_REJECTED = "已驳回";

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final ExpenseRepository expenseRepository;
  private final ExpenseSupplementRepository supplementRepository;
  private final AccessControlService accessControl;
  private final BusinessTodoService businessTodoService;
  private final DataScopeService dataScopeService;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public ExpenseService(
      ExpenseRepository expenseRepository,
      ExpenseSupplementRepository supplementRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService,
      DataScopeService dataScopeService,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.expenseRepository = expenseRepository;
    this.supplementRepository = supplementRepository;
    this.accessControl = accessControl;
    this.businessTodoService = businessTodoService;
    this.dataScopeService = dataScopeService;
    this.businessScopeResolver = businessScopeResolver;
  }

  public ExpenseService(
      ExpenseRepository expenseRepository,
      ExpenseSupplementRepository supplementRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService,
      DataScopeService dataScopeService
  ) {
    this(expenseRepository, supplementRepository, accessControl, businessTodoService, dataScopeService, null);
  }

  public ExpenseService(
      ExpenseRepository expenseRepository,
      ExpenseSupplementRepository supplementRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService
  ) {
    this(expenseRepository, supplementRepository, accessControl, businessTodoService, null, null);
  }

  public ExpenseService(ExpenseRepository expenseRepository) {
    this(expenseRepository, null, null, null, null, null);
  }

  public List<ExpenseClaimResponse> claims(AuthUser user, String month, Long brandId, String storeId, String status) {
    String targetMonth = normalizeMonth(month);
    DataScope dataScope = expenseScope(user);
    String requestedStoreId = blankToNull(storeId);
    requireReadRole(user, null, requestedStoreId, targetMonth);
    requireRequestedExpenseStoreScope(user, null, requestedStoreId, targetMonth, "查看报销数据");
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看报销数据", targetMonth);
    requireReadRole(user, null, businessScope.storeId(), targetMonth);
    if (businessScope.storeId() != null) {
      requireStoreScope(user, businessScope.storeId(), null, targetMonth);
      return enrichClaims(user.tenantId(), expenseClaims(
          user.tenantId(), targetMonth, businessScope.brandId(), businessScope.storeId(),
          blankToNull(status), dataScope));
    }
    return enrichClaims(user.tenantId(), expenseClaims(
        user.tenantId(), targetMonth, businessScope.brandId(), null, blankToNull(status), dataScope));
  }

  /** Returns one scoped claim for a desktop detail page without exposing another store's record. */
  public ExpenseClaimResponse claim(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireReadRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    return requireClaim(user, targetId);
  }

  @Transactional
  public ExpenseClaimResponse save(AuthUser user, String id, ExpenseClaimRequest request) {
    return save(user, id, request, null);
  }

  /**
   * A retried create must reuse a stable idempotency key.  Updates keep their path id / optimistic
   * workflow boundary and do not use this create-only key.
   */
  @Transactional
  public ExpenseClaimResponse save(
      AuthUser user,
      String id,
      ExpenseClaimRequest request,
      String idempotencyKey
  ) {
    String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
    boolean idempotentCreate = (id == null || id.isBlank()) && normalizedIdempotencyKey != null;
    String targetId = idempotentCreate ? idempotentCreateId(user, normalizedIdempotencyKey) : normalizeId(id);
    requireWriteRole(
        user,
        targetId,
        request == null ? null : request.storeId(),
        request == null ? null : request.month());
    requireRequestedExpenseStoreScope(
        user,
        targetId,
        request == null ? null : blankToNull(request.storeId()),
        request == null ? null : blankToNull(request.month()),
        "保存报销数据"
    );
    BusinessScope businessScope = resolveBusinessScope(
        user,
        request == null ? null : request.storeId(),
        null,
        "保存报销数据",
        request == null ? null : blankToNull(request.month()));
    ExpenseClaimRequest normalized = normalizeRequest(request, businessScope.storeId());
    requireStoreScope(user, normalized.storeId(), targetId, normalized.month());
    if (!expenseRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    ExpenseRepository.ClaimScope existingScope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    if (existingScope != null) {
      requireStoreScope(user, existingScope.storeId(), existingScope.id(), existingScope.month());
      if (idempotentCreate) {
        ExpenseClaimResponse existing = requireClaimForUser(user, targetId);
        if (!sameCreatePayload(existing, normalized)) {
          throw new BusinessException(
              "IDEMPOTENCY_KEY_REUSED",
              "同一请求标识不能用于不同的报销内容",
              HttpStatus.CONFLICT
          );
        }
        return existing;
      }
      if (!existingScope.storeId().equals(normalized.storeId())) {
        throw new BusinessException(
            "EXPENSE_STORE_IMMUTABLE",
            "已创建的报销不能变更门店，请新建报销单",
            HttpStatus.CONFLICT
        );
      }
    }
    scopedClaim(user.tenantId(), targetId, expenseScope(user))
        .ifPresent(this::requireEditableStatus);
    boolean saved = idempotentCreate
        ? expenseRepository.insertIfAbsent(user.tenantId(), targetId, normalized, STATUS_DRAFT, null)
        : expenseRepository.upsert(user.tenantId(), targetId, normalized, STATUS_DRAFT, null);
    if (!saved && idempotentCreate) {
      ExpenseClaimResponse existing = requireClaimForUser(user, targetId);
      if (sameCreatePayload(existing, normalized)) {
        return existing;
      }
      throw new BusinessException(
          "IDEMPOTENCY_KEY_REUSED",
          "同一请求标识不能用于不同的报销内容",
          HttpStatus.CONFLICT
      );
    }
    if (!saved) {
      throw stateChanged();
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_save",
        targetId,
        normalized.storeId(),
        normalized.month(),
        "保存店长报销草稿"
    );
    reconcileTodos(user, normalized.storeId(), normalized.month());
    return requireClaim(user, targetId);
  }

  @Transactional
  public ExpenseClaimResponse submit(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireWriteRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    ExpenseClaimResponse claim = requireClaimForUser(user, targetId);
    if (!isSubmittableStatus(claim.status())) {
      throw new BusinessException(
          "BAD_STATUS", "只有草稿、待补资料或已驳回的报销可以提交", HttpStatus.CONFLICT);
    }
    if (claim.amount() != null
        && claim.amount().compareTo(BigDecimal.ZERO) > 0
        && !expenseRepository.hasControlledImageAttachment(user.tenantId(), claim.id(), claim.storeId())) {
      throw new BusinessException("REIMBURSEMENT_IMAGE_REQUIRED", "有金额报销必须先上传图片凭证", HttpStatus.BAD_REQUEST);
    }
    if (expenseRepository.updateStatus(
        user.tenantId(), claim.id(), claim.status(), STATUS_PENDING, user.id(), null) == 0) {
      throw stateChanged();
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "reimbursement_submit",
        claim.id(),
        claim.storeId(),
        claim.month(),
        "提交店长每日报销"
    );
    reconcileTodos(user, claim.storeId(), claim.month());
    return requireClaim(user, claim.id());
  }

  @Transactional
  public ExpenseClaimResponse requestInfo(AuthUser user, String id, ExpenseReviewRequest request) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireReviewRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    ExpenseClaimResponse claim = requireClaim(user, targetId);
    if (!STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核的报销可以要求补充资料", HttpStatus.CONFLICT);
    }
    String note = reviewNote(request, "请补充票据或业务说明");
    if (expenseRepository.updateStatus(
        user.tenantId(), claim.id(), STATUS_PENDING, STATUS_SUPPLEMENT, null, user.id()) == 0) {
      throw stateChanged();
    }
    expenseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "expense_request_info", claim.id(), claim.storeId(), claim.month(), note);
    reconcileTodos(user, claim.storeId(), claim.month());
    return requireClaim(user, claim.id());
  }

  @Transactional
  public ExpenseClaimResponse approve(AuthUser user, String id, ExpenseReviewRequest request) {
    return review(user, id, STATUS_APPROVED, "reimbursement_approve", reviewNote(request, "报销审核通过"));
  }

  @Transactional
  public ExpenseClaimResponse reject(AuthUser user, String id, ExpenseReviewRequest request) {
    return review(
        user,
        id,
        STATUS_REJECTED,
        "reimbursement_reject",
        requireLength(
            request == null ? null : request.note(),
            "REJECT_REASON_REQUIRED",
            "请填写驳回原因",
            255,
            "驳回原因不能超过255字"
        )
    );
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireWriteRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    ExpenseClaimResponse claim = requireClaimForUser(user, targetId);
    if (!STATUS_DRAFT.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有草稿状态的报销可以删除", HttpStatus.CONFLICT);
    }
    if (supplementRepository != null && supplementRepository.hasSupplements(user.tenantId(), claim.id())) {
      throw new BusinessException(
          "EXPENSE_HAS_SUPPLEMENTS",
          "报销已包含补充资料，不能直接删除",
          HttpStatus.CONFLICT
      );
    }
    int deleted = expenseRepository.deleteDraft(user.tenantId(), claim.id());
    if (deleted == 0) {
      throw stateChanged();
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_delete",
        claim.id(),
        claim.storeId(),
        claim.month(),
        "删除店长报销草稿"
    );
    reconcileTodos(user, claim.storeId(), claim.month());
  }

  /** Deletes only an editable claim's primary receipt; submitted evidence remains immutable. */
  @Transactional
  public void deleteAttachment(AuthUser user, String expenseId, long attachmentId) {
    String targetId = requireText(expenseId, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireWriteRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    if (attachmentId <= 0) {
      throw new BusinessException("BAD_ATTACHMENT_ID", "附件编号不正确", HttpStatus.BAD_REQUEST);
    }
    ExpenseClaimResponse claim = requireClaimForUser(user, targetId);
    requireEditableStatus(claim);
    if (expenseRepository.deleteAttachment(user.tenantId(), claim.id(), claim.storeId(), attachmentId) == 0) {
      throw new BusinessException("ATTACHMENT_NOT_FOUND", "报销凭证不存在", HttpStatus.NOT_FOUND);
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "reimbursement_attachment_delete",
        claim.id(),
        claim.storeId(),
        claim.month(),
        "删除报销初始凭证"
    );
  }

  private ExpenseClaimResponse review(AuthUser user, String id, String status, String action, String reason) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    ExpenseRepository.ClaimScope scope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    requireReviewRole(user, targetId, scope == null ? null : scope.storeId(), scope == null ? null : scope.month());
    ExpenseClaimResponse claim = requireClaim(user, targetId);
    if (!STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核的报销可以审核", HttpStatus.CONFLICT);
    }
    if (expenseRepository.updateStatus(
        user.tenantId(), claim.id(), STATUS_PENDING, status, null, user.id()) == 0) {
      throw stateChanged();
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        action,
        claim.id(),
        claim.storeId(),
        claim.month(),
        reason
    );
    reconcileTodos(user, claim.storeId(), claim.month());
    return requireClaim(user, claim.id());
  }

  private ExpenseClaimRequest normalizeRequest(ExpenseClaimRequest request, String resolvedStoreId) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "请完整填写报销信息", HttpStatus.BAD_REQUEST);
    }
    String month = normalizeMonth(requireText(request.month(), "MONTH_REQUIRED", "报销月份不能为空"));
    String expenseDate = normalizeExpenseDate(request.expenseDate(), month);
    BigDecimal amount = amount(request.amount());
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_AMOUNT", "报销金额必须大于0", HttpStatus.BAD_REQUEST);
    }
    if (amount.compareTo(new BigDecimal("999999999999.99")) > 0) {
      throw new BusinessException("BAD_AMOUNT", "报销金额不能超过999999999999.99", HttpStatus.BAD_REQUEST);
    }
    return new ExpenseClaimRequest(
        requireText(resolvedStoreId, "STORE_REQUIRED", "请选择门店"),
        month,
        expenseDate,
        amount,
        requireLength(request.category(), "CATEGORY_REQUIRED", "报销类别不能为空", 80, "报销类别不能超过80字"),
        requireLength(request.reason(), "REASON_REQUIRED", "报销说明不能为空", 2000, "报销说明不能超过2000字"),
        request.imageUrl()
    );
  }

  private ExpenseClaimResponse requireClaimForUser(AuthUser user, String id) {
    return requireClaim(user, id);
  }

  private ExpenseClaimResponse requireClaim(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "报销单编号不能为空");
    DataScope dataScope = expenseScope(user);
    ExpenseRepository.ClaimScope rawScope = expenseRepository.claimScope(user.tenantId(), targetId).orElse(null);
    ExpenseClaimResponse claim = scopedClaim(user.tenantId(), targetId, dataScope)
        .orElseGet(() -> {
          if (rawScope != null) {
            requireStoreScope(user, rawScope.storeId(), rawScope.id(), rawScope.month());
          }
          throw new BusinessException("NOT_FOUND", "报销记录不存在", HttpStatus.NOT_FOUND);
        });
    if (dataScope == null) {
      requireStoreScope(user, claim.storeId(), claim.id(), claim.month());
    }
    return enrichClaim(user.tenantId(), claim);
  }

  private List<ExpenseClaimResponse> enrichClaims(long tenantId, List<ExpenseClaimResponse> claims) {
    if (supplementRepository == null) {
      return claims;
    }
    return claims.stream().map(claim -> enrichClaim(tenantId, claim)).toList();
  }

  private ExpenseClaimResponse enrichClaim(long tenantId, ExpenseClaimResponse claim) {
    ExpenseClaimResponse enriched = claim.withAttachments(
        expenseRepository.attachments(tenantId, claim.id(), claim.storeId()));
    if (supplementRepository == null) {
      return enriched;
    }
    return enriched.withSupplements(supplementRepository.supplements(tenantId, claim.id()));
  }

  private void requireEditableStatus(ExpenseClaimResponse claim) {
    if (STATUS_APPROVED.equals(claim.status()) || STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有草稿、待补资料或已驳回的报销可以编辑", HttpStatus.CONFLICT);
    }
  }

  private boolean isSubmittableStatus(String status) {
    return STATUS_DRAFT.equals(status) || STATUS_SUPPLEMENT.equals(status) || STATUS_REJECTED.equals(status);
  }

  private void requireReadRole(AuthUser user, String expenseId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireExpenseRead(user, expenseId, storeId, month);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "当前账号无权查看报销数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWriteRole(AuthUser user, String expenseId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireExpenseWrite(user, expenseId, storeId, month);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "当前账号无权编辑报销数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user, String expenseId, String storeId, String month) {
    if (accessControl != null) {
      accessControl.requireExpenseReview(user, expenseId, storeId, month);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "当前账号无权审核报销", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreScope(AuthUser user, String storeId, String expenseId, String month) {
    DataScope dataScope = expenseScope(user);
    if (dataScope != null && !dataScope.allowsStore(storeId)) {
      if (accessControl != null) {
        accessControl.requireExpenseStoreAccess(user, expenseId, storeId, month, "处理报销数据");
      }
      throw new BusinessException("FORBIDDEN", "当前账号只能处理授权门店的报销数据", HttpStatus.FORBIDDEN);
    }
    if (accessControl != null) {
      accessControl.requireExpenseStoreAccess(user, expenseId, storeId, month, "处理报销数据");
      return;
    }
    if (!isStoreManager(user)) {
      return;
    }
    String scopedStoreId = requireManagerStore(user);
    if (!scopedStoreId.equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "店长只能处理自己门店的报销", HttpStatus.FORBIDDEN);
    }
  }

  /**
   * Apply the document-aware finance scope before the generic business-scope resolver.  Otherwise
   * a forged store parameter is rejected as a generic STORE event without the report month in its
   * audit trace, which is insufficient for reimbursement verification.
   */
  private void requireRequestedExpenseStoreScope(
      AuthUser user,
      String expenseId,
      String storeId,
      String month,
      String action
  ) {
    if (accessControl != null && storeId != null && !storeId.isBlank()) {
      accessControl.requireExpenseStoreAccess(user, expenseId, storeId.trim(), month, action);
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return "STORE_MANAGER".equals(user.role());
  }

  private void reconcileTodos(AuthUser user, String storeId, String month) {
    if (businessTodoService != null) {
      // A reimbursement mutation is already role- and store-scope checked above. Reconcile only
      // the affected store's reimbursement-review queue; do not let a store manager's action
      // recalculate profit, salary, or another store's derived workflows.
      businessTodoService.reconcileExpenseReviewForStore(user.tenantId(), storeId, month);
    }
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "店长账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return user.storeId().trim();
  }

  private String normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE).toString();
    }
    try {
      return YearMonth.parse(value.trim()).toString();
    } catch (Exception ex) {
      throw new BusinessException("BAD_MONTH", "月份格式必须为 YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeExpenseDate(String value, String month) {
    String normalized = requireText(value, "EXPENSE_DATE_REQUIRED", "报销日期不能为空");
    try {
      LocalDate date = LocalDate.parse(normalized);
      if (!YearMonth.from(date).toString().equals(month)) {
        throw new BusinessException("EXPENSE_DATE_MONTH_MISMATCH", "报销日期必须属于所选月份", HttpStatus.BAD_REQUEST);
      }
      return date.toString();
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException("BAD_EXPENSE_DATE", "报销日期格式必须为 YYYY-MM-DD", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeId(String id) {
    if (id != null && !id.isBlank()) {
      return id.trim();
    }
    return "EXP" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String normalizeIdempotencyKey(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > 120 || !normalized.matches("[A-Za-z0-9._:-]+")) {
      throw new BusinessException(
          "BAD_IDEMPOTENCY_KEY",
          "请求标识格式不正确",
          HttpStatus.BAD_REQUEST
      );
    }
    return normalized;
  }

  private String idempotentCreateId(AuthUser user, String key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          (user.tenantId() + "|" + user.id() + "|" + key).getBytes(StandardCharsets.UTF_8));
      return "EXP-IDEMP-" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  private boolean sameCreatePayload(ExpenseClaimResponse existing, ExpenseClaimRequest request) {
    return existing != null
        && Objects.equals(existing.storeId(), request.storeId())
        && Objects.equals(existing.month(), request.month())
        && Objects.equals(existing.expenseDate() == null ? null : existing.expenseDate().toString(), request.expenseDate())
        && existing.amount() != null
        && existing.amount().compareTo(request.amount()) == 0
        && Objects.equals(existing.category(), request.category())
        && Objects.equals(existing.reason(), request.reason());
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String requireLength(String value, String code, String requiredMessage, int maxLength, String tooLongMessage) {
    String normalized = requireText(value, code, requiredMessage);
    if (normalized.length() > maxLength) {
      throw new BusinessException(code + "_TOO_LONG", tooLongMessage, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String reviewNote(ExpenseReviewRequest request, String fallback) {
    if (request == null || request.note() == null || request.note().isBlank()) {
      return fallback;
    }
    return requireLength(
        request.note(),
        "REVIEW_NOTE_REQUIRED",
        "审核说明不能为空",
        255,
        "审核说明不能超过255字"
    );
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private BigDecimal amount(BigDecimal value) {
    if (value == null) {
      throw new BusinessException("AMOUNT_REQUIRED", "报销金额不能为空", HttpStatus.BAD_REQUEST);
    }
    if (value.scale() > 2) {
      throw new BusinessException("BAD_AMOUNT", "报销金额最多保留2位小数", HttpStatus.BAD_REQUEST);
    }
    return value.setScale(2, RoundingMode.UNNECESSARY);
  }

  private BusinessException stateChanged() {
    return new BusinessException("EXPENSE_STATE_CHANGED", "报销单状态已变化，请刷新后重试", HttpStatus.CONFLICT);
  }

  private DataScope expenseScope(AuthUser user) {
    if (dataScopeService != null) {
      return dataScopeService.scope(user, DataScopeDomains.FINANCE);
    }
    if (isStoreManager(user) && user.storeId() != null && !user.storeId().isBlank()) {
      return new DataScope(DataScopeModes.OWN_STORE, List.of(user.storeId()));
    }
    return null;
  }

  private BusinessScope resolveBusinessScope(
      AuthUser user,
      String storeId,
      Long brandId,
      String action,
      String auditMonth
  ) {
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.FINANCE, storeId, brandId, action, auditMonth);
    }
    String requestedStoreId = blankToNull(storeId);
    if (requestedStoreId == null && isStoreManager(user)) {
      requestedStoreId = requireManagerStore(user);
    }
    return new BusinessScope(requestedStoreId, null, brandId, null, expenseScope(user));
  }

  private List<ExpenseClaimResponse> expenseClaims(
      long tenantId,
      String month,
      Long brandId,
      String storeId,
      String status,
      DataScope dataScope
  ) {
    return dataScope == null
        ? expenseRepository.claims(tenantId, month, brandId, storeId, status)
        : expenseRepository.claims(tenantId, month, brandId, storeId, status, dataScope);
  }

  private java.util.Optional<ExpenseClaimResponse> scopedClaim(
      long tenantId,
      String id,
      DataScope dataScope
  ) {
    return dataScope == null
        ? expenseRepository.claim(tenantId, id)
        : expenseRepository.claim(tenantId, id, dataScope);
  }
}
