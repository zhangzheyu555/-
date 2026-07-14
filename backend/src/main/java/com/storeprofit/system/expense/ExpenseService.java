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
    requireReadRole(user);
    String targetMonth = normalizeMonth(month);
    DataScope dataScope = expenseScope(user);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, brandId, "查看报销数据");
    if (businessScope.storeId() != null) {
      requireStoreScope(user, businessScope.storeId());
      return enrichClaims(user.tenantId(), expenseClaims(
          user.tenantId(), targetMonth, businessScope.brandId(), businessScope.storeId(),
          blankToNull(status), dataScope));
    }
    return enrichClaims(user.tenantId(), expenseClaims(
        user.tenantId(), targetMonth, businessScope.brandId(), null, blankToNull(status), dataScope));
  }

  @Transactional
  public ExpenseClaimResponse save(AuthUser user, String id, ExpenseClaimRequest request) {
    requireWriteRole(user);
    BusinessScope businessScope = resolveBusinessScope(
        user, request == null ? null : request.storeId(), null, "保存报销数据");
    ExpenseClaimRequest normalized = normalizeRequest(request, businessScope.storeId());
    requireStoreScope(user, normalized.storeId());
    if (!expenseRepository.storeExists(user.tenantId(), normalized.storeId())) {
      throw new BusinessException("STORE_NOT_FOUND", "Store does not exist in current tenant", HttpStatus.BAD_REQUEST);
    }
    String targetId = normalizeId(id);
    expenseRepository.claimStoreId(user.tenantId(), targetId)
        .ifPresent(existingStoreId -> requireStoreScope(user, existingStoreId));
    scopedClaim(user.tenantId(), targetId, expenseScope(user))
        .ifPresent(this::requireEditableStatus);
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
    return requireClaim(user, targetId);
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
    return requireClaim(user, claim.id());
  }

  @Transactional
  public ExpenseClaimResponse requestInfo(AuthUser user, String id, ExpenseReviewRequest request) {
    requireReviewRole(user);
    ExpenseClaimResponse claim = requireClaim(user, id);
    if (!STATUS_PENDING.equals(claim.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核的报销可以要求补充资料", HttpStatus.CONFLICT);
    }
    String note = reviewNote(request, "请补充票据或业务说明");
    expenseRepository.updateStatus(user.tenantId(), claim.id(), STATUS_SUPPLEMENT, null, user.id());
    expenseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "expense_request_info", claim.id(), claim.storeId(), claim.month(), note);
    reconcileTodos(user, claim.month());
    return requireClaim(user, claim.id());
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
    if (supplementRepository != null && supplementRepository.hasSupplements(user.tenantId(), claim.id())) {
      throw new BusinessException(
          "EXPENSE_HAS_SUPPLEMENTS",
          "报销已包含补充资料，不能直接删除",
          HttpStatus.CONFLICT
      );
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
    ExpenseClaimResponse claim = requireClaim(user, id);
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
    return requireClaim(user, claim.id());
  }

  private ExpenseClaimRequest normalizeRequest(ExpenseClaimRequest request, String resolvedStoreId) {
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "Expense payload is required", HttpStatus.BAD_REQUEST);
    }
    BigDecimal amount = amount(request.amount());
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_AMOUNT", "Expense amount must be greater than 0", HttpStatus.BAD_REQUEST);
    }
    return new ExpenseClaimRequest(
        requireText(resolvedStoreId, "STORE_REQUIRED", "请选择门店"),
        normalizeMonth(request.month()),
        amount,
        request.category(),
        request.reason(),
        request.imageUrl()
    );
  }

  private ExpenseClaimResponse requireClaimForUser(AuthUser user, String id) {
    return requireClaim(user, id);
  }

  private ExpenseClaimResponse requireClaim(AuthUser user, String id) {
    String targetId = requireText(id, "ID_REQUIRED", "Expense claim id is required");
    DataScope dataScope = expenseScope(user);
    ExpenseClaimResponse claim = scopedClaim(user.tenantId(), targetId, dataScope)
        .orElseGet(() -> {
          expenseRepository.claimStoreId(user.tenantId(), targetId)
              .ifPresent(storeId -> requireStoreScope(user, storeId));
          throw new BusinessException("NOT_FOUND", "Expense claim not found", HttpStatus.NOT_FOUND);
        });
    if (dataScope == null) {
      requireStoreScope(user, claim.storeId());
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
    if (supplementRepository == null) {
      return claim;
    }
    return claim.withSupplements(supplementRepository.supplements(tenantId, claim.id()));
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
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "No permission to read expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWriteRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExpenseWrite(user);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE", "STORE_MANAGER")) {
      throw new BusinessException("FORBIDDEN", "No permission to edit expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReviewRole(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExpenseReview(user);
      return;
    }
    if (!AccessControlService.hasAnyRole(user, "FINANCE")) {
      throw new BusinessException("FORBIDDEN", "No permission to review expense claims", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreScope(AuthUser user, String storeId) {
    DataScope dataScope = expenseScope(user);
    if (dataScope != null) {
      if (!dataScope.allowsStore(storeId)) {
        throw new BusinessException("FORBIDDEN", "当前账号只能处理授权门店的报销数据", HttpStatus.FORBIDDEN);
      }
      return;
    }
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
      String action
  ) {
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.FINANCE, storeId, brandId, action);
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
