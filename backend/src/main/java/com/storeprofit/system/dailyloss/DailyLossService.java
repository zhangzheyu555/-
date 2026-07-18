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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

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
    DataScope scope = dailyLossScope(user);
    String expectedStatus = normalizeStatus(status);
    return repository.list(user.tenantId(), date, null, null, storeId, scope).stream()
        .filter(row -> expectedStatus == null || expectedStatus.equals(row.status()))
        .map(row -> response(user.tenantId(), row))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<DailyLossReportResponse> reports(AuthUser user, String storeId, String month) {
    accessControl.requireDailyLossRead(user);
    YearMonth targetMonth = normalizeMonth(month);
    String targetStoreId = resolveOptionalStoreForRead(user, storeId);
    if (targetStoreId != null) {
      requireStoreScope(user, targetStoreId, "查看每日报损");
    }
    DataScope scope = dailyLossScope(user);
    List<DailyLossRepository.ReportStoreRow> stores = repository.reportStores(
        user.tenantId(), targetStoreId, scope);
    Map<String, DailyLossRepository.DailyLossReportRow> existing = new LinkedHashMap<>();
    for (DailyLossRepository.DailyLossReportRow row : repository.reports(
        user.tenantId(), targetMonth, targetStoreId, scope)) {
      existing.put(row.storeId() + "|" + row.lossDate(), row);
    }
    return stores.stream()
        .flatMap(store -> targetMonth.atDay(1).datesUntil(targetMonth.plusMonths(1).atDay(1))
            .map(day -> {
              DailyLossRepository.DailyLossReportRow row = existing.get(store.id() + "|" + day);
              return row == null ? missingReport(store, day) : reportResponse(user.tenantId(), row);
            }))
        .sorted((left, right) -> {
          int date = right.lossDate().compareTo(left.lossDate());
          return date != 0 ? date : String.valueOf(left.storeCode()).compareTo(String.valueOf(right.storeCode()));
        })
        .toList();
  }

  @Transactional(readOnly = true)
  public DailyLossReportResponse today(AuthUser user) {
    accessControl.requireDailyLossRead(user);
    String storeId = resolveRequiredStoreForCurrentUser(user, "查看今日报损");
    requireStoreScope(user, storeId, "查看今日报损");
    LocalDate today = LocalDate.now(BUSINESS_ZONE);
    return repository.findReportByStoreAndDate(user.tenantId(), storeId, today)
        .map(row -> reportResponse(user.tenantId(), row))
        .orElseGet(() -> {
          DailyLossRepository.ReportStoreRow store = repository.reportStores(
              user.tenantId(), storeId, dailyLossScope(user)).stream()
              .findFirst()
              .orElse(new DailyLossRepository.ReportStoreRow(storeId, storeId, storeId));
          return missingReport(store, today);
        });
  }

  @Transactional
  public DailyLossReportResponse saveReport(AuthUser user, DailyLossReportSaveRequest request) {
    return saveReport(user, request, List.of());
  }

  @Transactional
  public DailyLossReportResponse saveReport(
      AuthUser user,
      DailyLossReportSaveRequest request,
      List<MultipartFile> files
  ) {
    accessControl.requireDailyLossCreate(user);
    if (request == null) {
      throw badRequest("DAILY_LOSS_REQUEST_REQUIRED", "请完整填写每日报损信息");
    }
    String storeId = requiredText(request.storeId(), "请选择门店");
    requireStoreScope(user, storeId, "提交每日报损");
    if (!repository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("DAILY_LOSS_STORE_NOT_FOUND", "报损门店不存在", HttpStatus.NOT_FOUND);
    }
    LocalDate lossDate = request.lossDate();
    if (lossDate == null) {
      throw badRequest("DAILY_LOSS_DATE_REQUIRED", "请选择报损日期");
    }
    List<DailyLossReportLineRequest> details = request.details() == null ? List.of() : request.details();
    if (details.isEmpty()) {
      throw badRequest("DAILY_LOSS_DETAILS_REQUIRED", "请至少填写一项报损品类");
    }
    if (details.size() > 120) {
      throw badRequest("DAILY_LOSS_DETAILS_LIMIT", "单日报损明细不能超过120项");
    }
    DailyLossRepository.DailyLossReportRow report = repository.findReportByStoreAndDate(
        user.tenantId(), storeId, lossDate).orElse(null);
    if (report != null && List.of("SUBMITTED", "REVIEWED").contains(normalizeStatus(report.status()))) {
      throw new BusinessException("DAILY_LOSS_REPORT_LOCKED", "该日报损已提交或已复核，不能直接修改", HttpStatus.CONFLICT);
    }
    String reportId = report == null ? "DLR-" + UUID.randomUUID() : report.id();
    if (report == null) {
      repository.insertReport(user.tenantId(), reportId, storeId, lossDate, user.id());
    } else {
      repository.touchReportDraft(user.tenantId(), reportId);
      repository.resetReportDetails(user.tenantId(), reportId);
    }
    for (DailyLossReportLineRequest detail : details) {
      long configId = detail == null || detail.itemConfigId() == null ? 0L : detail.itemConfigId();
      DailyLossRepository.LossItemConfigRow item = repository.activeItemConfig(user.tenantId(), configId)
          .orElseThrow(() -> new BusinessException(
              "DAILY_LOSS_ITEM_NOT_FOUND", "报损品类不存在或已停用", HttpStatus.NOT_FOUND));
      BigDecimal quantity = positiveAmount(detail.lossQuantity(), "报损数量必须大于零");
      BigDecimal unitPrice = item.unitPrice().setScale(4, RoundingMode.HALF_UP);
      BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
      String reason = normalizeOptionalText(detail.lossReason(), 500, "报损原因不能超过500个字符");
      repository.insertReportDetail(
          user.tenantId(),
          "DL-" + UUID.randomUUID(),
          reportId,
          storeId,
          lossDate,
          item,
          quantity,
          unitPrice,
          amount,
          reason == null ? "日常报损" : reason,
          user.id());
    }
    if (files != null && !files.isEmpty()) {
      uploadReportFiles(user, reportId, storeId, files);
    }
    return reportResponse(user.tenantId(), requiredReport(user.tenantId(), reportId));
  }

  @Transactional
  public DailyLossReportResponse submitReport(AuthUser user, String id) {
    accessControl.requireDailyLossCreate(user);
    DailyLossRepository.DailyLossReportRow report = requiredReport(user.tenantId(), id);
    requireStoreScope(user, report.storeId(), "提交每日报损");
    if ("REVIEWED".equals(normalizeStatus(report.status()))) {
      throw new BusinessException("DAILY_LOSS_REPORT_REVIEWED", "该日报损已复核，不能重复提交", HttpStatus.CONFLICT);
    }
    if (repository.reportDetails(user.tenantId(), report.id()).isEmpty()) {
      throw badRequest("DAILY_LOSS_DETAILS_REQUIRED", "请先填写报损明细再提交");
    }
    if (repository.reportAttachmentCount(user.tenantId(), report.id()) <= 0) {
      throw badRequest("DAILY_LOSS_ATTACHMENT_REQUIRED", "请至少上传一张报损照片后再提交");
    }
    repository.markReportSubmitted(user.tenantId(), report.id(), user.id());
    audit(user, "daily_loss_submit", report.id(), report.storeId(), "提交每日报损，等待督导复核");
    return reportResponse(user.tenantId(), requiredReport(user.tenantId(), report.id()));
  }

  @Transactional
  public DailyLossReportResponse reviewReport(AuthUser user, String id, DailyLossReviewRequest request) {
    accessControl.requireDailyLossReview(user);
    DailyLossRepository.DailyLossReportRow report = repository.findReportForUpdate(user.tenantId(), requiredText(id, "报损单编号不正确"))
        .orElseThrow(() -> new BusinessException("DAILY_LOSS_NOT_FOUND", "报损日报不存在", HttpStatus.NOT_FOUND));
    requireStoreScope(user, report.storeId(), "复核每日报损");
    String status = normalizeStatus(report.status());
    if ("REVIEWED".equals(status)) {
      return reportResponse(user.tenantId(), report);
    }
    if (!"SUBMITTED".equals(status)) {
      throw new BusinessException("DAILY_LOSS_REVIEW_CONFLICT", "只有已提交的报损日报才能复核", HttpStatus.CONFLICT);
    }
    String reviewNote = request == null ? null : normalizeOptionalText(request.reviewNote(), 500, "复核备注不能超过500个字符");
    repository.markReportReviewed(user.tenantId(), report.id(), user.id(), reviewNote);
    audit(user, "daily_loss_review", report.id(), report.storeId(), "督导复核每日报损");
    return reportResponse(user.tenantId(), requiredReport(user.tenantId(), report.id()));
  }

  @Transactional
  public DailyLossReportResponse uploadReportAttachments(AuthUser user, String id, List<MultipartFile> files) {
    accessControl.requireDailyLossCreate(user);
    DailyLossRepository.DailyLossReportRow report = requiredReport(user.tenantId(), id);
    requireStoreScope(user, report.storeId(), "上传每日报损附件");
    uploadReportFiles(user, report.id(), report.storeId(), files);
    return reportResponse(user.tenantId(), report);
  }

  @Transactional
  public byte[] exportMonthlyPhotos(AuthUser user, String storeId, String yyyyMM) {
    accessControl.requireDailyLossReview(user);
    String targetStoreId = requiredText(storeId, "请选择门店");
    requireStoreScope(user, targetStoreId, "导出每日报损照片");
    YearMonth month = normalizeMonth(yyyyMM);
    List<DailyLossPhotoExportFile> photos = repository.monthlyPhotoFiles(user.tenantId(), targetStoreId, month);
    byte[] zip = zipPhotos(photos, month);
    audit(user, "daily_loss_photo_export", targetStoreId + "-" + month, targetStoreId,
        "导出月度报损照片包，图片数量：" + photos.size());
    return zip;
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
    audit(user, "daily_loss_review", row.id(), row.storeId(), "复核通过并生成一条LOSS_OUT库存流水");
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
    audit(user, "attachment_upload", row.id(), row.storeId(), "上传每日报损附件");
    return getSubmittedRecord(user.tenantId(), row.id());
  }

  private void requireStoreScope(AuthUser user, String storeId, String action) {
    accessControl.requireStoreAccess(user, dailyLossDataScopeDomain(user), storeId, action);
  }

  private DataScope dailyLossScope(AuthUser user) {
    return accessControl.dataScope(user, dailyLossDataScopeDomain(user));
  }

  private String dailyLossDataScopeDomain(AuthUser user) {
    return AccessControlService.hasAnyRole(user, "FINANCE") ? DataScopeDomains.FINANCE : DataScopeDomains.STORE;
  }

  private String resolveOptionalStoreForRead(AuthUser user, String storeId) {
    String requested = blankToNull(storeId);
    if (requested != null) {
      return requested;
    }
    if (AccessControlService.hasAnyRole(user, "STORE_MANAGER")) {
      return resolveRequiredStoreForCurrentUser(user, "查看每日报损");
    }
    return null;
  }

  private String resolveRequiredStoreForCurrentUser(AuthUser user, String action) {
    if (user != null && user.storeId() != null && !user.storeId().isBlank()) {
      return user.storeId().trim();
    }
    auditRepository.writePermissionDenied(user, action, "daily_loss_report", null, null, "当前账号未绑定门店");
    throw new BusinessException("DAILY_LOSS_STORE_REQUIRED", "请先选择门店", HttpStatus.BAD_REQUEST);
  }

  private DailyLossRepository.DailyLossReportRow requiredReport(long tenantId, String id) {
    String normalizedId = requiredText(id, "报损日报编号不正确");
    return repository.findReport(tenantId, normalizedId)
        .orElseThrow(() -> new BusinessException("DAILY_LOSS_NOT_FOUND", "报损日报不存在", HttpStatus.NOT_FOUND));
  }

  private DailyLossReportResponse reportResponse(long tenantId, DailyLossRepository.DailyLossReportRow row) {
    List<DailyLossReportDetailResponse> details = repository.reportDetails(tenantId, row.id());
    List<DailyLossAttachmentResponse> attachments = repository.attachments(tenantId, row.id());
    BigDecimal total = details.stream()
        .map(DailyLossReportDetailResponse::amountSnapshot)
        .filter(java.util.Objects::nonNull)
        .reduce(ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    String status = normalizeStatus(row.status());
    return new DailyLossReportResponse(
        row.id(),
        row.storeId(),
        row.storeCode(),
        row.storeName(),
        row.lossDate(),
        YearMonth.from(row.lossDate()).toString(),
        status,
        reportStatusLabel(status),
        true,
        total,
        details.size(),
        attachments.size(),
        row.submittedBy(),
        row.submittedByName(),
        row.submittedAt(),
        row.reviewedBy(),
        row.reviewedByName(),
        row.reviewedAt(),
        row.reviewNote(),
        details,
        attachments);
  }

  private DailyLossReportResponse missingReport(DailyLossRepository.ReportStoreRow store, LocalDate day) {
    return new DailyLossReportResponse(
        null,
        store.id(),
        store.code(),
        store.name(),
        day,
        YearMonth.from(day).toString(),
        "NOT_REPORTED",
        "未报",
        false,
        ZERO,
        0,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  private String reportStatusLabel(String status) {
    return switch (status) {
      case "DRAFT" -> "已报";
      case "SUBMITTED" -> "待复核";
      case "REVIEWED", "APPROVED" -> "已复核";
      case "REJECTED" -> "已驳回";
      case "NOT_REPORTED" -> "未报";
      default -> "处理中";
    };
  }

  private void uploadReportFiles(AuthUser user, String reportId, String storeId, List<MultipartFile> files) {
    if (files == null || files.isEmpty()) {
      throw badRequest("DAILY_LOSS_ATTACHMENT_REQUIRED", "请先选择要上传的附件");
    }
    if (files.size() > 12) {
      throw badRequest("DAILY_LOSS_ATTACHMENT_LIMIT", "每次最多上传12张照片");
    }
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) {
        throw badRequest("DAILY_LOSS_ATTACHMENT_EMPTY", "报损照片不能为空");
      }
      if (!isImageFile(file)) {
        throw badRequest("DAILY_LOSS_ATTACHMENT_IMAGE_REQUIRED", "报损附件只能上传图片");
      }
      storageService.upload(user, file, "DAILY_LOSS", reportId, storeId);
    }
    audit(user, "attachment_upload", reportId, storeId, "上传每日报损照片");
  }

  private boolean isImageFile(MultipartFile file) {
    String contentType = file.getContentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
      return true;
    }
    String filename = file.getOriginalFilename();
    return filename != null && filename.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif)$");
  }

  private byte[] zipPhotos(List<DailyLossPhotoExportFile> photos, YearMonth month) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(output)) {
        Map<String, Integer> dayCounters = new LinkedHashMap<>();
        for (DailyLossPhotoExportFile photo : photos) {
          String day = photo.lossDate() == null || photo.lossDate().length() < 10
              ? "unknown"
              : photo.lossDate().substring(8, 10);
          int index = dayCounters.merge(day, 1, Integer::sum);
          String path = safePathSegment(photo.storeCode()) + "/" + month + "/" + day + "/"
              + String.format(Locale.ROOT, "%03d%s", index, extension(photo.fileName(), photo.contentType()));
          ZipEntry entry = new ZipEntry(path);
          zip.putNextEntry(entry);
          zip.write(photo.content());
          zip.closeEntry();
        }
      }
      return output.toByteArray();
    } catch (IOException ex) {
      throw new BusinessException("DAILY_LOSS_PHOTO_EXPORT_FAILED", "报损照片打包失败，请稍后重试",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private String extension(String fileName, String contentType) {
    String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if ("image/jpeg".equals(normalizedType) || "image/jpg".equals(normalizedType)) return ".jpg";
    if ("image/png".equals(normalizedType)) return ".png";
    if ("image/webp".equals(normalizedType)) return ".webp";
    if ("image/gif".equals(normalizedType)) return ".gif";
    String name = fileName == null ? "" : fileName.trim();
    int dot = name.lastIndexOf('.');
    if (dot >= 0 && dot < name.length() - 1) {
      String ext = name.substring(dot).toLowerCase(Locale.ROOT);
      if (List.of(".jpg", ".jpeg", ".png", ".webp", ".gif").contains(ext)) {
        return ".jpeg".equals(ext) ? ".jpg" : ext;
      }
    }
    return ".jpg";
  }

  private String safePathSegment(String value) {
    String normalized = value == null || value.isBlank() ? "store" : value.trim();
    normalized = normalized.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    return normalized.isBlank() ? "store" : normalized;
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
    if (!List.of("DRAFT", "SUBMITTED", "APPROVED", "REVIEWED", "REJECTED", "NOT_REPORTED").contains(normalized)) {
      throw badRequest("DAILY_LOSS_STATUS_INVALID", "报损状态不正确");
    }
    return normalized;
  }

  private YearMonth normalizeMonth(String value) {
    if (value == null || value.isBlank()) {
      return YearMonth.now(BUSINESS_ZONE);
    }
    try {
      return YearMonth.parse(value.trim(), MONTH_FORMATTER);
    } catch (DateTimeParseException ex) {
      throw badRequest("DAILY_LOSS_MONTH_INVALID", "月份格式必须为YYYY-MM");
    }
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

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private BusinessException badRequest(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private void audit(AuthUser user, String action, String id, String storeId, String reason) {
    auditRepository.writeLog(user, new AuditLogRequest(action, "daily_loss_record", id, storeId, null,
        reason, null, null));
  }
}
