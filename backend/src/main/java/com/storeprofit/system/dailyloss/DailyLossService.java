package com.storeprofit.system.dailyloss;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Daily-loss workflow: submit a cost snapshot first, then create exactly one LOSS_OUT movement
 * after an authorized review.  It never writes the profit ledger; profit reporting consumes the
 * same inventory-loss source rather than counting the approval twice.
 */
@Service
public class DailyLossService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

  private final DailyLossRepository repository;
  private final WarehouseRepository warehouseRepository;
  private final StorageService storageService;
  private final AccessControlService accessControl;
  private final AuditRepository auditRepository;

  public DailyLossService(
      DailyLossRepository repository,
      WarehouseRepository warehouseRepository,
      StorageService storageService,
      AccessControlService accessControl,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.warehouseRepository = warehouseRepository;
    this.storageService = storageService;
    this.accessControl = accessControl;
    this.auditRepository = auditRepository;
  }

  @Transactional(readOnly = true)
  public List<DailyLossItemResponse> activeItems(AuthUser user) {
    accessControl.requireDailyLossRead(user);
    return repository.activeItems(user.tenantId());
  }

  @Transactional(readOnly = true)
  public List<DailyLossResponse> list(AuthUser user, String storeId, LocalDate date, String status) {
    accessControl.requireDailyLossRead(user);
    if (storeId != null && !storeId.isBlank()) {
      requireStoreScope(user, storeId.trim(), "查看每日报损");
    }
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
    String expectedStatus = normalizeStatus(status);
    return repository.list(user.tenantId(), date, null, null, storeId, scope).stream()
        .filter(row -> expectedStatus == null || expectedStatus.equals(row.status()))
        .map(row -> response(user.tenantId(), row))
        .toList();
  }

  @Transactional(readOnly = true)
  public DailyLossResponse get(AuthUser user, String id) {
    accessControl.requireDailyLossRead(user);
    DailyLossRepository.DailyLossRow row = requiredRecord(user.tenantId(), id);
    requireStoreScope(user, row.storeId(), "查看每日报损");
    return response(user.tenantId(), row);
  }

  @Transactional
  public DailyLossResponse create(AuthUser user, DailyLossCreateRequest request) {
    accessControl.requireDailyLossCreate(user);
    if (request == null) {
      throw badRequest("DAILY_LOSS_REQUEST_REQUIRED", "请完整填写报损信息");
    }
    String storeId = requiredText(request.storeId(), "请选择门店");
    requireStoreScope(user, storeId, "提交每日报损");
    if (!repository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("DAILY_LOSS_STORE_NOT_FOUND", "报损门店不存在", HttpStatus.NOT_FOUND);
    }
    if (request.lossDate() == null) {
      throw badRequest("DAILY_LOSS_DATE_REQUIRED", "请选择报损日期");
    }
    if (request.itemId() == null || request.itemId() <= 0) {
      throw badRequest("DAILY_LOSS_ITEM_REQUIRED", "请选择在用物料");
    }
    BigDecimal quantity = positiveAmount(request.lossQuantity(), "报损数量必须大于零");
    String reason = requiredText(request.lossReason(), "请填写报损原因");
    if (reason.length() > 500) {
      throw badRequest("DAILY_LOSS_REASON_TOO_LONG", "报损原因不能超过500个字符");
    }
    DailyLossRepository.LossItemRow item = repository.activeItem(user.tenantId(), request.itemId())
        .orElseThrow(() -> new BusinessException("DAILY_LOSS_ITEM_NOT_FOUND", "物料不存在或已停用", HttpStatus.NOT_FOUND));
    BigDecimal unitPrice = item.unitPrice();
    if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
      throw badRequest("DAILY_LOSS_PRICE_NOT_CONFIGURED", "该物料尚未维护损耗成本，请先在物料档案维护单价");
    }
    unitPrice = unitPrice.setScale(4, RoundingMode.HALF_UP);
    BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    String id = "DL-" + UUID.randomUUID();
    repository.insert(user.tenantId(), id, request, item, quantity, unitPrice, amount, user.id());
    audit(user, "daily_loss_submit", id, storeId, "已提交报损单，等待审核后扣减库存");
    return getSubmittedRecord(user.tenantId(), id);
  }

  /** Creation already creates a submitted record. Keep this endpoint idempotent for the attachment-first UI flow. */
  @Transactional(readOnly = true)
  public DailyLossResponse submit(AuthUser user, String id) {
    accessControl.requireDailyLossCreate(user);
    DailyLossRepository.DailyLossRow row = requiredRecord(user.tenantId(), id);
    requireStoreScope(user, row.storeId(), "提交每日报损");
    if (!"SUBMITTED".equals(row.status())) {
      throw new BusinessException("DAILY_LOSS_NOT_SUBMITTED", "该报损单已完成审核，不能重复提交", HttpStatus.CONFLICT);
    }
    return response(user.tenantId(), row);
  }

  @Transactional
  public DailyLossResponse approve(AuthUser user, String id, DailyLossReviewRequest request) {
    accessControl.requireDailyLossReview(user);
    DailyLossRepository.LockedLossRow row = repository.findForUpdate(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("DAILY_LOSS_NOT_FOUND", "报损单不存在", HttpStatus.NOT_FOUND));
    requireStoreScope(user, row.storeId(), "复核每日报损");
    if ("APPROVED".equals(row.status())) {
      if (!repository.inventoryApplicationExists(user.tenantId(), row.id())) {
        throw new BusinessException("DAILY_LOSS_INVENTORY_INCONSISTENT", "报损库存处理状态异常，请联系管理员", HttpStatus.CONFLICT);
      }
      return getSubmittedRecord(user.tenantId(), row.id());
    }
    if (!"SUBMITTED".equals(row.status())) {
      throw new BusinessException("DAILY_LOSS_REVIEW_CONFLICT", "该报损单当前不能审核通过", HttpStatus.CONFLICT);
    }
    String reviewNote = request == null ? null : normalizeOptionalText(request.reviewNote(), 500, "审核备注不能超过500个字符");
    if (!repository.insertInventoryApplication(user.tenantId(), row, user.id())) {
      throw new BusinessException("DAILY_LOSS_DUPLICATE_APPLICATION", "该报损单已在处理，请刷新后重试", HttpStatus.CONFLICT);
    }
    boolean deducted = warehouseRepository.subtractStoreInventoryIfEnough(
        user.tenantId(), row.storeId(), row.itemId(), row.lossQuantity(),
        "LOSS_OUT", "DAILY_LOSS", row.id(), row.lossReason(), user.id());
    if (!deducted) {
      throw new BusinessException("DAILY_LOSS_INSUFFICIENT_STOCK", "门店库存不足，不能审核通过报损单", HttpStatus.CONFLICT);
    }
    repository.markApproved(user.tenantId(), row.id(), user.id(), reviewNote);
    audit(user, "daily_loss_approve", row.id(), row.storeId(), "审核通过并生成一条LOSS_OUT库存流水");
    return getSubmittedRecord(user.tenantId(), row.id());
  }

  @Transactional
  public DailyLossResponse uploadAttachments(AuthUser user, String id, List<MultipartFile> files) {
    accessControl.requireDailyLossCreate(user);
    DailyLossRepository.DailyLossRow row = requiredRecord(user.tenantId(), id);
    requireStoreScope(user, row.storeId(), "上传每日报损附件");
    if (files == null || files.isEmpty()) {
      throw badRequest("DAILY_LOSS_ATTACHMENT_REQUIRED", "请先选择要上传的附件");
    }
    if (files.size() > 6) {
      throw badRequest("DAILY_LOSS_ATTACHMENT_LIMIT", "每次最多上传6个附件");
    }
    for (MultipartFile file : files) {
      storageService.upload(user, file, "DAILY_LOSS", row.id(), row.storeId());
    }
    audit(user, "daily_loss_attachment_upload", row.id(), row.storeId(), "上传每日报损附件");
    return getSubmittedRecord(user.tenantId(), row.id());
  }

  private void requireStoreScope(AuthUser user, String storeId, String action) {
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
    if (scope.warehouseIds() != null && !scope.warehouseIds().isEmpty()) {
      if (!repository.storeInWarehouseScope(user.tenantId(), storeId, scope.warehouseIds())) {
        auditRepository.writePermissionDenied(user, action, "daily_loss_record", null, storeId,
            "门店不属于当前仓库数据范围");
        throw new BusinessException("FORBIDDEN", "当前账号没有访问该业务的权限", HttpStatus.FORBIDDEN);
      }
      return;
    }
    accessControl.requireStoreAccess(user, DataScopeDomains.WAREHOUSE, storeId, action);
  }

  private DailyLossRepository.DailyLossRow requiredRecord(long tenantId, String id) {
    String normalizedId = requiredText(id, "报损单编号不正确");
    return repository.find(tenantId, normalizedId)
        .orElseThrow(() -> new BusinessException("DAILY_LOSS_NOT_FOUND", "报损单不存在", HttpStatus.NOT_FOUND));
  }

  private DailyLossResponse getSubmittedRecord(long tenantId, String id) {
    return response(tenantId, requiredRecord(tenantId, id));
  }

  private DailyLossResponse response(long tenantId, DailyLossRepository.DailyLossRow row) {
    return new DailyLossResponse(
        row.id(), row.storeId(), row.storeCode(), row.storeName(), row.lossDate(), row.itemId(),
        row.itemCode(), row.itemName(), row.stockUnit(), row.lossQuantity(), row.unitPriceSnapshot(),
        row.amountSnapshot(), row.lossReason(), row.status(), row.submittedBy(), row.submittedByName(),
        row.submittedAt(), row.reviewedBy(), row.reviewedByName(), row.reviewedAt(), row.reviewNote(),
        repository.inventoryApplicationExists(tenantId, row.id()), repository.attachments(tenantId, row.id()));
  }

  private BigDecimal positiveAmount(BigDecimal value, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw badRequest("DAILY_LOSS_QUANTITY_INVALID", message);
    }
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private String normalizeStatus(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (!List.of("SUBMITTED", "APPROVED", "REJECTED").contains(normalized)) {
      throw badRequest("DAILY_LOSS_STATUS_INVALID", "报损状态不正确");
    }
    return normalized;
  }

  private String requiredText(String value, String message) {
    if (value == null || value.isBlank()) throw badRequest("DAILY_LOSS_TEXT_REQUIRED", message);
    return value.trim();
  }

  private String normalizeOptionalText(String value, int maximumLength, String message) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximumLength) throw badRequest("DAILY_LOSS_TEXT_TOO_LONG", message);
    return normalized;
  }

  private BusinessException badRequest(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private void audit(AuthUser user, String action, String id, String storeId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "daily_loss_record", id, storeId, null,
        reason, null, null));
  }
}
