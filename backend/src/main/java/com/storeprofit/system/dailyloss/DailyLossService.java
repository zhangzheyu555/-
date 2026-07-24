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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
  public DailyLossMonthlyArchiveResponse monthlyArchive(AuthUser user, String month) {
    // Historical workbooks contain totals across every store, so store-scoped
    // accounts must not receive this aggregate financial view.
    accessControl.requireDailyLossReview(user);
    return repository.monthlyArchive(user.tenantId(), normalizeMonth(month))
        .map(row -> new DailyLossMonthlyArchiveResponse(
            row.month(), row.sourceSheet(), row.sourceTitle(),
            row.declaredTotalLossAmount(), row.detailTotalLossAmount(),
            row.supplierCompensationAmount(), row.declaredStoreBorneAmount(),
            row.calculatedStoreBorneAmount(), row.declaredBorneDifference(),
            row.detailLossDifference(), row.storeCount(), row.itemCount(),
            row.reconciliationStatus(), row.sourceNote()))
        .orElse(null);
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
    BigDecimal totalAmount = ZERO;
    for (DailyLossReportLineRequest detail : details) {
      long configId = detail == null || detail.itemConfigId() == null ? 0L : detail.itemConfigId();
      DailyLossRepository.LossItemConfigRow item = repository.activeItemConfig(user.tenantId(), configId)
          .orElseThrow(() -> new BusinessException(
              "DAILY_LOSS_ITEM_NOT_FOUND", "报损品类不存在或已停用", HttpStatus.NOT_FOUND));
      BigDecimal quantity = positiveAmount(detail.lossQuantity(), "报损数量必须大于零");
      BigDecimal quantityPerPricingUnit = item.quantityPerPricingUnit();
      if (quantityPerPricingUnit == null || quantityPerPricingUnit.compareTo(BigDecimal.ZERO) <= 0) {
        throw badRequest("DAILY_LOSS_PRICING_UNIT_INVALID", "该物料的计价折算配置不正确");
      }
      BigDecimal pricedQuantity = quantity.divide(quantityPerPricingUnit, 4, RoundingMode.HALF_UP);
      BigDecimal unitPrice = item.unitPrice().setScale(4, RoundingMode.HALF_UP);
      BigDecimal amount = pricedQuantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
      totalAmount = totalAmount.add(amount);
      String reason = normalizeOptionalText(detail.lossReason(), 500, "报损原因不能超过500个字符");
      repository.insertReportDetail(
          user.tenantId(),
          "DL-" + UUID.randomUUID(),
          reportId,
          storeId,
          lossDate,
          item,
          quantity,
          pricedQuantity,
          unitPrice,
          amount,
          reason == null ? "日常报损" : reason,
          user.id());
    }
    BigDecimal supplierCompensation = nonNegativeAmount(
        request.supplierCompensationAmount(), "厂商赔付金额不能小于零");
    if (supplierCompensation.compareTo(totalAmount) > 0) {
      throw badRequest("DAILY_LOSS_COMPENSATION_EXCEEDS_TOTAL", "厂商赔付金额不能超过报损总金额");
    }
    repository.updateSupplierCompensation(user.tenantId(), reportId, supplierCompensation);
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
  public DailyLossMonthlyExcelExport exportMonthlyExcel(AuthUser user, String storeId, String monthValue) {
    accessControl.requireDailyLossExport(user);
    YearMonth month = normalizeMonth(monthValue);
    String targetStoreId = resolveOptionalStoreForRead(user, storeId);
    if (targetStoreId != null) {
      requireStoreScope(user, targetStoreId, "导出每日报损");
    }
    DataScope scope = dailyLossScope(user);
    List<DailyLossRepository.ReportStoreRow> stores = repository.reportStores(
        user.tenantId(), targetStoreId, scope);
    List<DailyLossRepository.DailyLossReportRow> reports = repository.reports(
        user.tenantId(), month, targetStoreId, scope);
    List<DailyLossRepository.MonthlyExportDetailRow> details = repository.monthlyExportDetails(
        user.tenantId(), month, targetStoreId, scope);
    List<DailyLossItemResponse> configuredItems = repository.activeItems(user.tenantId());
    Map<String, DailyLossRepository.DailyLossReportRow> reportsByStoreDay = new LinkedHashMap<>();
    for (DailyLossRepository.DailyLossReportRow report : reports) {
      reportsByStoreDay.put(report.storeId() + "|" + report.lossDate(), report);
    }
    Map<String, List<DailyLossRepository.MonthlyExportDetailRow>> detailsByReport = new LinkedHashMap<>();
    for (DailyLossRepository.MonthlyExportDetailRow detail : details) {
      detailsByReport.computeIfAbsent(detail.reportId(), ignored -> new java.util.ArrayList<>()).add(detail);
    }

    byte[] content = buildMonthlyExcel(
        month, stores, reportsByStoreDay, details, detailsByReport, configuredItems);
    String fileName = monthlyExcelFileName(month, stores, targetStoreId);
    String scopeLabel = targetStoreId == null ? "全部门店" : stores.stream()
        .filter(store -> targetStoreId.equals(store.id()))
        .map(DailyLossRepository.ReportStoreRow::name)
        .findFirst()
        .orElse(targetStoreId);
    auditRepository.writeLog(user, new AuditLogRequest(
        "daily_loss_export", "daily_loss_export", month.toString(), targetStoreId, month.toString(),
        "导出每日报损Excel，月份：" + month + "，门店范围：" + safeAuditText(scopeLabel)
            + "，汇总行数：" + stores.size() * month.lengthOfMonth() + "，明细行数：" + details.size(),
        null, null));
    return new DailyLossMonthlyExcelExport(content, fileName, stores.size() * month.lengthOfMonth(), details.size());
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
    if (unitPrice == null) {
      throw badRequest("DAILY_LOSS_PRICE_NOT_CONFIGURED", "该物料尚未维护损耗成本，请先在物料档案维护单价");
    }
    if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
      throw badRequest("DAILY_LOSS_PRICE_INVALID", "该物料损耗成本不能小于零");
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
    // `hasAnyRole` intentionally treats BOSS as a global role.  This branch
    // needs the exact role instead: a boss may read all authorized stores and
    // must never be forced through the store-manager single-store binding.
    if ("STORE_MANAGER".equals(AccessControlService.canonicalRole(user == null ? null : user.role()))) {
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
    BigDecimal supplierCompensation = row.supplierCompensationAmount() == null
        ? ZERO
        : row.supplierCompensationAmount().setScale(2, RoundingMode.HALF_UP);
    BigDecimal storeBorne = total.subtract(supplierCompensation).max(ZERO).setScale(2, RoundingMode.HALF_UP);
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
        supplierCompensation,
        storeBorne,
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
        ZERO,
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

  private byte[] buildMonthlyExcel(
      YearMonth month,
      List<DailyLossRepository.ReportStoreRow> stores,
      Map<String, DailyLossRepository.DailyLossReportRow> reportsByStoreDay,
      List<DailyLossRepository.MonthlyExportDetailRow> details,
      Map<String, List<DailyLossRepository.MonthlyExportDetailRow>> detailsByReport,
      List<DailyLossItemResponse> configuredItems
  ) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (Workbook workbook = new XSSFWorkbook()) {
        ExcelStyles styles = new ExcelStyles(workbook);
        buildMonthlyMatrixSheet(workbook, month, stores, reportsByStoreDay, details, configuredItems, styles);
        Sheet summary = workbook.createSheet("每日汇总");
        Sheet detail = workbook.createSheet("报损明细");
        String[] summaryHeaders = {"日期", "门店编码", "门店名称", "报损品类数", "报损总数量", "报损总金额",
            "厂商赔付金额", "店铺承担", "上报状态", "提交人", "提交时间", "复核人", "复核时间", "复核意见"};
        String[] detailHeaders = {"日期", "门店编码", "门店名称", "物料编码", "物料名称", "品类",
            "报损数量", "录入单位", "折算量", "计价单位", "计价数量", "计价单价", "报损金额",
            "报损原因", "上报状态", "提交人", "提交时间", "复核人", "复核时间", "复核意见"};
        writeHeader(summary, summaryHeaders, styles.header());
        writeHeader(detail, detailHeaders, styles.header());

        int summaryRowIndex = 1;
        for (DailyLossRepository.ReportStoreRow store : stores) {
          for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
            DailyLossRepository.DailyLossReportRow report = reportsByStoreDay.get(store.id() + "|" + day);
            List<DailyLossRepository.MonthlyExportDetailRow> reportDetails = report == null
                ? List.of()
                : detailsByReport.getOrDefault(report.id(), List.of());
            BigDecimal quantity = reportDetails.stream().map(DailyLossRepository.MonthlyExportDetailRow::lossQuantity)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal amount = reportDetails.stream().map(DailyLossRepository.MonthlyExportDetailRow::amount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal compensation = report == null || report.supplierCompensationAmount() == null
                ? ZERO : report.supplierCompensationAmount();
            Row row = summary.createRow(summaryRowIndex++);
            writeDate(row, 0, day, styles.date());
            writeText(row, 1, store.code(), styles.text());
            writeText(row, 2, store.name(), styles.text());
            writeNumber(row, 3, BigDecimal.valueOf(reportDetails.size()), styles.integer());
            writeNumber(row, 4, quantity, styles.quantity());
            writeNumber(row, 5, amount, styles.amount());
            writeNumber(row, 6, compensation, styles.amount());
            writeNumber(row, 7, amount.subtract(compensation).max(ZERO), styles.amount());
            writeText(row, 8, report == null ? "未报" : reportStatusLabel(normalizeStatus(report.status())), styles.text());
            writeText(row, 9, report == null ? null : report.submittedByName(), styles.text());
            writeDateTime(row, 10, report == null ? null : report.submittedAt(), styles.dateTime());
            writeText(row, 11, report == null ? null : report.reviewedByName(), styles.text());
            writeDateTime(row, 12, report == null ? null : report.reviewedAt(), styles.dateTime());
            writeText(row, 13, report == null ? null : report.reviewNote(), styles.text());
          }
        }

        int detailRowIndex = 1;
        for (DailyLossRepository.MonthlyExportDetailRow item : details) {
          Row row = detail.createRow(detailRowIndex++);
          writeDate(row, 0, item.lossDate(), styles.date());
          writeText(row, 1, item.storeCode(), styles.text());
          writeText(row, 2, item.storeName(), styles.text());
          writeText(row, 3, item.itemCode(), styles.text());
          writeText(row, 4, item.itemName(), styles.text());
          writeText(row, 5, item.category(), styles.text());
          writeNumber(row, 6, item.lossQuantity(), styles.quantity());
          writeText(row, 7, item.unit(), styles.text());
          writeNumber(row, 8, item.quantityPerPricingUnit(), styles.quantity());
          writeText(row, 9, item.pricingUnit(), styles.text());
          writeNumber(row, 10, item.pricedQuantity(), styles.quantity());
          writeNumber(row, 11, item.unitPrice(), styles.unitPrice());
          writeNumber(row, 12, item.amount(), styles.amount());
          writeText(row, 13, item.lossReason(), styles.text());
          writeText(row, 14, reportStatusLabel(normalizeStatus(item.status())), styles.text());
          writeText(row, 15, item.submittedByName(), styles.text());
          writeDateTime(row, 16, item.submittedAt(), styles.dateTime());
          writeText(row, 17, item.reviewedByName(), styles.text());
          writeDateTime(row, 18, item.reviewedAt(), styles.dateTime());
          writeText(row, 19, item.reviewNote(), styles.text());
        }
        configureSheet(summary, summaryRowIndex - 1,
            new int[]{13, 14, 22, 13, 15, 15, 15, 15, 13, 16, 20, 16, 20, 32});
        configureSheet(detail, detailRowIndex - 1,
            new int[]{13, 14, 22, 18, 24, 18, 15, 10, 12, 10, 14, 14, 15, 30, 13, 16, 20, 16, 20, 32});
        workbook.write(output);
      }
      return output.toByteArray();
    } catch (IOException ex) {
      throw new BusinessException("DAILY_LOSS_EXCEL_EXPORT_FAILED", "本月报损 Excel 生成失败，请稍后重试",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private void buildMonthlyMatrixSheet(
      Workbook workbook,
      YearMonth month,
      List<DailyLossRepository.ReportStoreRow> stores,
      Map<String, DailyLossRepository.DailyLossReportRow> reportsByStoreDay,
      List<DailyLossRepository.MonthlyExportDetailRow> details,
      List<DailyLossItemResponse> configuredItems,
      ExcelStyles styles
  ) {
    Sheet sheet = workbook.createSheet(month.getMonthValue() + "月份");
    LinkedHashMap<String, MatrixItem> items = new LinkedHashMap<>();
    for (DailyLossItemResponse item : configuredItems) {
      items.put(item.itemCode(), new MatrixItem(
          item.itemCode(), item.itemName(), item.unit(), item.pricingUnit(),
          item.quantityPerPricingUnit(), item.unitPrice()));
    }
    for (DailyLossRepository.MonthlyExportDetailRow item : details) {
      items.putIfAbsent(item.itemCode(), new MatrixItem(
          item.itemCode(), item.itemName(), item.unit(), item.pricingUnit(),
          item.quantityPerPricingUnit(), item.unitPrice()));
    }
    List<MatrixItem> columns = List.copyOf(items.values());
    int lastColumn = Math.max(1, columns.size() + 1);
    Row title = sheet.createRow(0);
    writeText(title, 0, month.getMonthValue() + "月份店铺损耗表", styles.title());
    for (int column = 1; column <= lastColumn; column++) {
      title.createCell(column).setCellStyle(styles.title());
    }
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));

    Row header = sheet.createRow(1);
    writeText(header, 0, "店铺", styles.matrixHeader());
    writeText(header, 1, "合计", styles.matrixHeader());
    for (int index = 0; index < columns.size(); index++) {
      MatrixItem item = columns.get(index);
      writeText(header, index + 2, item.name() + "\n单位：" + item.inputUnit(), styles.matrixHeader());
    }
    header.setHeightInPoints(38);

    Map<String, Map<String, BigDecimal>> rawByStore = new LinkedHashMap<>();
    Map<String, BigDecimal> amountByStore = new LinkedHashMap<>();
    Map<String, BigDecimal> rawByItem = new LinkedHashMap<>();
    Map<String, BigDecimal> pricedByItem = new LinkedHashMap<>();
    Map<String, BigDecimal> amountByItem = new LinkedHashMap<>();
    for (DailyLossRepository.MonthlyExportDetailRow detail : details) {
      rawByStore.computeIfAbsent(detail.storeId(), ignored -> new LinkedHashMap<>())
          .merge(detail.itemCode(), nullSafe(detail.lossQuantity()), BigDecimal::add);
      amountByStore.merge(detail.storeId(), nullSafe(detail.amount()), BigDecimal::add);
      rawByItem.merge(detail.itemCode(), nullSafe(detail.lossQuantity()), BigDecimal::add);
      pricedByItem.merge(detail.itemCode(), nullSafe(detail.pricedQuantity()), BigDecimal::add);
      amountByItem.merge(detail.itemCode(), nullSafe(detail.amount()), BigDecimal::add);
    }

    int rowIndex = 2;
    for (DailyLossRepository.ReportStoreRow store : stores) {
      Row row = sheet.createRow(rowIndex++);
      writeText(row, 0, store.name(), styles.matrixText());
      writeNumber(row, 1, amountByStore.get(store.id()), styles.matrixAmount());
      Map<String, BigDecimal> storeItems = rawByStore.getOrDefault(store.id(), Map.of());
      for (int index = 0; index < columns.size(); index++) {
        writeNumber(row, index + 2, storeItems.get(columns.get(index).code()), styles.matrixQuantity());
      }
    }

    BigDecimal totalAmount = amountByItem.values().stream().reduce(ZERO, BigDecimal::add);
    BigDecimal compensation = reportsByStoreDay.values().stream()
        .map(DailyLossRepository.DailyLossReportRow::supplierCompensationAmount)
        .filter(java.util.Objects::nonNull)
        .reduce(ZERO, BigDecimal::add);
    String[] labels = {"合计（总量）", "总量", "价格", "总计损耗金额", "厂商赔付金额", "店铺承担"};
    for (int offset = 0; offset < labels.length; offset++) {
      Row row = sheet.createRow(rowIndex++);
      writeText(row, 0, labels[offset], styles.matrixSummary());
      BigDecimal overall = switch (offset) {
        case 3 -> totalAmount;
        case 4 -> compensation;
        case 5 -> totalAmount.subtract(compensation).max(ZERO);
        default -> BigDecimal.ZERO;
      };
      if (offset >= 3) writeNumber(row, 1, overall, styles.matrixSummaryAmount());
      else writeText(row, 1, "", styles.matrixSummary());
      for (int index = 0; index < columns.size(); index++) {
        MatrixItem item = columns.get(index);
        BigDecimal value = switch (offset) {
          case 0 -> rawByItem.get(item.code());
          case 1 -> pricedByItem.get(item.code());
          case 2 -> item.unitPrice();
          case 3 -> amountByItem.get(item.code());
          default -> null;
        };
        writeNumber(row, index + 2, value, offset >= 3 ? styles.matrixSummaryAmount() : styles.matrixSummaryNumber());
      }
    }
    sheet.createFreezePane(2, 2);
    sheet.setColumnWidth(0, 18 * 256);
    sheet.setColumnWidth(1, 15 * 256);
    for (int column = 2; column <= lastColumn; column++) sheet.setColumnWidth(column, 16 * 256);
    sheet.setDefaultRowHeightInPoints(21);
  }

  private BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private void writeHeader(Sheet sheet, String[] headers, CellStyle headerStyle) {
    Row row = sheet.createRow(0);
    for (int index = 0; index < headers.length; index++) {
      Cell cell = row.createCell(index);
      cell.setCellValue(headers[index]);
      cell.setCellStyle(headerStyle);
    }
  }

  private void configureSheet(Sheet sheet, int lastRow, int[] widths) {
    sheet.createFreezePane(0, 1);
    sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, lastRow), 0, widths.length - 1));
    sheet.setDefaultRowHeightInPoints(19);
    for (int index = 0; index < widths.length; index++) {
      sheet.setColumnWidth(index, widths[index] * 256);
    }
  }

  private void writeText(Row row, int index, String value, CellStyle style) {
    Cell cell = row.createCell(index);
    cell.setCellValue(safeExcelText(value));
    cell.setCellStyle(style);
  }

  private void writeNumber(Row row, int index, BigDecimal value, CellStyle style) {
    Cell cell = row.createCell(index);
    cell.setCellValue((value == null ? BigDecimal.ZERO : value).doubleValue());
    cell.setCellStyle(style);
  }

  private void writeDate(Row row, int index, LocalDate value, CellStyle style) {
    Cell cell = row.createCell(index);
    if (value != null) {
      cell.setCellValue(java.util.Date.from(value.atStartOfDay(BUSINESS_ZONE).toInstant()));
    }
    cell.setCellStyle(style);
  }

  private void writeDateTime(Row row, int index, java.time.LocalDateTime value, CellStyle style) {
    Cell cell = row.createCell(index);
    if (value != null) {
      cell.setCellValue(java.util.Date.from(value.atZone(BUSINESS_ZONE).toInstant()));
    }
    cell.setCellStyle(style);
  }

  private String safeExcelText(String value) {
    if (value == null) return "";
    return value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")
        ? "'" + value
        : value;
  }

  private String monthlyExcelFileName(
      YearMonth month,
      List<DailyLossRepository.ReportStoreRow> stores,
      String targetStoreId
  ) {
    String scopeName = targetStoreId == null ? "全部门店" : stores.stream()
        .filter(store -> targetStoreId.equals(store.id()))
        .map(DailyLossRepository.ReportStoreRow::name)
        .findFirst()
        .orElse(targetStoreId);
    return safeFileName(scopeName) + "-" + month.getYear() + "年"
        + String.format(Locale.ROOT, "%02d", month.getMonthValue()) + "月-每日报损.xlsx";
  }

  private String safeFileName(String value) {
    String normalized = value == null || value.isBlank() ? "全部门店" : value.trim();
    return normalized.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "-");
  }

  private String safeAuditText(String value) {
    return (value == null ? "全部门店" : value).replaceAll("[\\r\\n]+", " ");
  }

  private record ExcelStyles(CellStyle header, CellStyle text, CellStyle integer, CellStyle quantity,
                             CellStyle unitPrice, CellStyle amount, CellStyle date, CellStyle dateTime,
                             CellStyle title, CellStyle matrixHeader, CellStyle matrixText,
                             CellStyle matrixQuantity, CellStyle matrixAmount, CellStyle matrixSummary,
                             CellStyle matrixSummaryNumber, CellStyle matrixSummaryAmount) {
    private ExcelStyles(Workbook workbook) {
      this(headerStyle(workbook), workbook.createCellStyle(), numericStyle(workbook, "0"),
          numericStyle(workbook, "#,##0.00####"), numericStyle(workbook, "#,##0.0000"),
          numericStyle(workbook, "#,##0.00"), dateStyle(workbook, "yyyy-mm-dd"),
          dateStyle(workbook, "yyyy-mm-dd hh:mm:ss"), titleStyle(workbook), matrixHeaderStyle(workbook),
          matrixStyle(workbook, null), matrixStyle(workbook, "#,##0.00####"),
          matrixStyle(workbook, "#,##0.00"), matrixSummaryStyle(workbook, null),
          matrixSummaryStyle(workbook, "#,##0.00####"), matrixSummaryStyle(workbook, "#,##0.00"));
    }

    private static CellStyle headerStyle(Workbook workbook) {
      CellStyle style = workbook.createCellStyle();
      Font font = workbook.createFont();
      font.setBold(true);
      font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
      style.setFont(font);
      style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
      style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle numericStyle(Workbook workbook, String format) {
      CellStyle style = workbook.createCellStyle();
      DataFormat dataFormat = workbook.createDataFormat();
      style.setDataFormat(dataFormat.getFormat(format));
      return style;
    }

    private static CellStyle dateStyle(Workbook workbook, String format) {
      return numericStyle(workbook, format);
    }

    private static CellStyle titleStyle(Workbook workbook) {
      CellStyle style = matrixStyle(workbook, null);
      Font font = workbook.createFont();
      font.setBold(true);
      font.setFontHeightInPoints((short) 15);
      style.setFont(font);
      style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
      style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_TURQUOISE.getIndex());
      style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle matrixHeaderStyle(Workbook workbook) {
      CellStyle style = matrixStyle(workbook, null);
      Font font = workbook.createFont();
      font.setBold(true);
      style.setFont(font);
      style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
      style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
      style.setWrapText(true);
      return style;
    }

    private static CellStyle matrixSummaryStyle(Workbook workbook, String format) {
      CellStyle style = matrixStyle(workbook, format);
      Font font = workbook.createFont();
      font.setBold(true);
      style.setFont(font);
      style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
      style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle matrixStyle(Workbook workbook, String format) {
      CellStyle style = workbook.createCellStyle();
      if (format != null) style.setDataFormat(workbook.createDataFormat().getFormat(format));
      style.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      style.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      style.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
      return style;
    }
  }

  private record MatrixItem(String code, String name, String inputUnit, String pricingUnit,
                            BigDecimal quantityPerPricingUnit, BigDecimal unitPrice) {}

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

  private BigDecimal nonNegativeAmount(BigDecimal value, String message) {
    if (value == null) return ZERO;
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      throw badRequest("DAILY_LOSS_AMOUNT_INVALID", message);
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
