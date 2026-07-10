package com.storeprofit.system.expense;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.todo.BusinessTodoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
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
  private final AccessControlService accessControl;
  private final BusinessTodoService businessTodoService;

  @Autowired
  public ExpenseService(
      ExpenseRepository expenseRepository,
      AccessControlService accessControl,
      BusinessTodoService businessTodoService
  ) {
    this.expenseRepository = expenseRepository;
    this.accessControl = accessControl;
    this.businessTodoService = businessTodoService;
  }

  public ExpenseService(ExpenseRepository expenseRepository) {
    this(expenseRepository, null, null);
  }

  public List<ExpenseClaimResponse> claims(AuthUser user, String month, Long brandId, String storeId, String status) {
    requireReadRole(user);
    String targetMonth = normalizeMonth(month);
    if (storeId != null && !storeId.isBlank()) {
      requireStoreScope(user, storeId.trim());
      return expenseRepository.claims(user.tenantId(), targetMonth, brandId, storeId.trim(), blankToNull(status));
    }
    return expenseRepository.claims(user.tenantId(), targetMonth, brandId, null, blankToNull(status)).stream()
        .filter(claim -> canAccessStore(user, claim.storeId()))
        .toList();
  }

  @Transactional
  public ExpenseClaimResponse save(AuthUser user, String id, ExpenseClaimRequest request) {
    requireWriteRole(user);
    ExpenseClaimRequest normalized = normalizeRequest(request);
    requireStoreScope(user, normalized.storeId());
    if (!expenseRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "Store does not exist in current tenant", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    expenseRepository.claim(user.tenantId(), targetId).ifPresent(existing -> {
      requireStoreScope(user, existing.storeId());
      requireEditableStatus(existing);
    });
    expenseRepository.upsert(user.tenantId(), targetId, normalized, STATUS_DRAFT, null);
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_save",
        targetId,
        normalized.storeId(),
        normalized.month(),
        "expense claim saved"
    );
    reconcileTodos(user, normalized.month());
    return requireClaim(user.tenantId(), targetId);
  }

  @Transactional
  public ExpenseClaimResponse submit(AuthUser user, String id) {
    requireWriteRole(user);
    ExpenseClaimResponse claim = requireClaimForUser(user, id);
    if (STATUS_APPROVED.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "Approved expense claim cannot be submitted again", HttpStatus.CONFLICT);
    }
    expenseRepository.updateStatus(user.tenantId(), claim.id(), STATUS_PENDING, user.id(), null);
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_submit",
        claim.id(),
        claim.storeId(),
        claim.month(),
        "expense claim submitted"
    );
    reconcileTodos(user, claim.month());
    return requireClaim(user.tenantId(), claim.id());
  }

  @Transactional
  public ExpenseClaimResponse requestInfo(AuthUser user, String id, ExpenseReviewRequest request) {
    requireReviewRole(user);
    ExpenseClaimResponse claim = requireClaim(user.tenantId(), id);
    if (!STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核的报销可以要求补充资料", HttpStatus.CONFLICT);
    }
    String note = reviewNote(request, "请补充票据或业务说明");
    expenseRepository.updateStatus(user.tenantId(), claim.id(), STATUS_SUPPLEMENT, null, user.id());
    expenseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "expense_request_info", claim.id(), claim.storeId(), claim.month(), note);
    reconcileTodos(user, claim.month());
    return requireClaim(user.tenantId(), claim.id());
  }

  @Transactional
  public ExpenseClaimResponse approve(AuthUser user, String id, ExpenseReviewRequest request) {
    requireReviewRole(user);
    return review(user, id, STATUS_APPROVED, "expense_approve", reviewNote(request, "expense claim approved"));
  }

  @Transactional
  public ExpenseClaimResponse reject(AuthUser user, String id, ExpenseReviewRequest request) {
    requireReviewRole(user);
    return review(user, id, STATUS_REJECTED, "expense_reject", reviewNote(request, "expense claim rejected"));
  }

  @Transactional
  public void delete(AuthUser user, String id) {
    requireWriteRole(user);
    ExpenseClaimResponse claim = requireClaimForUser(user, id);
    if (STATUS_APPROVED.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "Approved expense claim cannot be deleted", HttpStatus.CONFLICT);
    }
    int deleted = expenseRepository.delete(user.tenantId(), claim.id());
    if (deleted == 0) {
      throw new BusinessException("NOT_FOUND", "Expense claim not found", HttpStatus.NOT_FOUND);
    }
    expenseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "expense_delete",
        claim.id(),
        claim.storeId(),
        claim.month(),
        "expense claim deleted"
    );
    reconcileTodos(user, claim.month());
  }

  private ExpenseClaimResponse review(AuthUser user, String id, String status, String action, String reason) {
    ExpenseClaimResponse claim = requireClaim(user.tenantId(), id);
    if (!STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "Only pending expense claims can be reviewed", HttpStatus.CONFLICT);
    }
    expenseRepository.updateStatus(user.tenantId(), claim.id(), status, null, user.id());
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
    reconcileTodos(user, claim.month());
    return requireClaim(user.tenantId(), claim.id());
  }

  private ExpenseClaimRequest normalizeRequest(ExpenseClaimRequest request) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Expense payload is required", HttpStatus.BAD_REQUEST);
    }
    BigDecimal amount = amount(request.amount());
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_AMOUNT", "Expense amount must be greater than 0", HttpStatus.BAD_REQUEST);
    }
    return new ExpenseClaimRequest(
        requireText(request.storeId(), "STORE_REQUIRED", "Store is required"),
        normalizeMonth(request.month()),
        amount,
        request.category(),
        request.reason(),
        request.imageUrl()
    );
  }

  private ExpenseClaimResponse requireClaimForUser(AuthUser user, String id) {
    ExpenseClaimResponse claim = requireClaim(user.tenantId(), id);
    requireStoreScope(user, claim.storeId());
    return claim;
  }

  private ExpenseClaimResponse requireClaim(long tenantId, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "Expense claim id is required");
    return expenseRepository.claim(tenantId, targetId)
        .orElseThrow(() -> new BusinessException("NOT_FOUND", "Expense claim not found", HttpStatus.NOT_FOUND));
  }

  private void requireEditableStatus(ExpenseClaimResponse claim) {
    if (STATUS_APPROVED.equals(claim.status()) || STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "Only draft or rejected expense claims can be edited", HttpStatus.CONFLICT);
    }
  }

  private void requireReadRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExpenseRead(user);
      return;
    }
    if (!List.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to read expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWriteRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExpenseWrite(user);
      return;
    }
    if (!List.of("ADMIN", "BOSS", "FINANCE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to edit expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExpenseReview(user);
      return;
    }
    if (!List.of("ADMIN", "BOSS", "FINANCE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "No permission to review expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreScope(AuthUser user, String storeId) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, storeId, "处理报销数据");
      return;
    }
    if (!isStoreManager(user)) {
      return;
    }
    String scopedStoreId = requireManagerStore(user);
    if (!scopedStoreId.equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "Store manager can only access own store expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return "STORE_MANAGER".equals(user.role());
  }

  private boolean canAccessStore(AuthUser user, String storeId) {
    if (accessControl != null) {
      return accessControl.canAccessStore(user, storeId);
    }
    return !isStoreManager(user) || requireManagerStore(user).equals(storeId);
  }

  private void reconcileTodos(AuthUser user, String month) {
    if (businessTodoService != null) {
      businessTodoService.reconcileAfterFinanceMutation(user, month);
    }
  }

  private String requireManagerStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "Store manager is not bound to a store", HttpStatus.FORBIDDEN);
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
      throw new BusinessException("BAD_MONTH", "Month must use YYYY-MM", HttpStatus.BAD_REQUEST);
    }
  }

  private String normalizeId(String id) {
    if (id != null && !id.isBlank()) {
      return id.trim();
    }
    return "EXP" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String requireText(String value, String code, String message) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
    return value.trim();
  }

  private String reviewNote(ExpenseReviewRequest request, String fallback) {
    if (request == null || request.note() == null || request.note().isBlank()) {
      return fallback;
    }
    return request.note().trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }
}
