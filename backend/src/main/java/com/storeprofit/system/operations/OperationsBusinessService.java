package com.storeprofit.system.operations;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAnswerResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamAttemptResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.ExamPaperResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckExcelExport;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckLineRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryCheckResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryItemPriceUpdateRequest;
import com.storeprofit.system.operations.OperationsBusinessModels.InventoryItemResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingLearningRecordResponse;
import com.storeprofit.system.operations.OperationsBusinessModels.TrainingMaterialResponse;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsBusinessService {
  private final OperationsBusinessRepository repository;
  private final AccessControlService accessControl;

  @Autowired
  public OperationsBusinessService(
      OperationsBusinessRepository repository,
      AccessControlService accessControl
  ) {
    this.repository = repository;
    this.accessControl = accessControl;
  }

  /** Compatibility constructor retained for isolated service tests. */
  public OperationsBusinessService(OperationsBusinessRepository repository) {
    this(repository, null);
  }

  public List<InventoryItemResponse> inventoryItems(AuthUser user) {
    requireInventoryRead(user);
    requireInventoryManagerBrand(user);
    return repository.inventoryItems(user.tenantId());
  }

  @Transactional
  public InventoryItemResponse updateInventoryItemPrice(
      AuthUser user,
      String itemCode,
      InventoryItemPriceUpdateRequest request
  ) {
    requireInventoryPriceManage(user);
    String normalizedCode = requiredText(itemCode, "物料编码不能为空");
    InventoryItemResponse current = repository.inventoryItem(user.tenantId(), normalizedCode)
        .orElseThrow(() -> new BusinessException(
            "INVENTORY_ITEM_NOT_FOUND", "盘存物料不存在", HttpStatus.NOT_FOUND));
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "请填写盘存物料价格", HttpStatus.BAD_REQUEST);
    }

    BigDecimal packageQuantity = request.packageQuantity() == null
        ? current.packageQuantity()
        : positiveDecimal(request.packageQuantity(), 4, "包装数量必须大于 0");
    if (packageQuantity == null || packageQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_PACKAGE_QUANTITY", "包装数量必须大于 0", HttpStatus.BAD_REQUEST);
    }
    BigDecimal packagePrice = request.packagePrice() == null
        ? current.packagePrice()
        : nonNegativeDecimal(request.packagePrice(), 6, "包装价不能小于 0");
    BigDecimal unitPrice = request.unitPrice() == null
        ? null
        : nonNegativeDecimal(request.unitPrice(), 6, "单位成本不能小于 0");
    if (packagePrice == null && unitPrice == null) {
      throw new BusinessException("PRICE_REQUIRED", "请填写包装价或单位成本", HttpStatus.BAD_REQUEST);
    }
    BigDecimal calculatedUnitPrice = packagePrice == null
        ? null
        : packagePrice.divide(packageQuantity, 6, RoundingMode.HALF_UP);
    if (unitPrice != null && calculatedUnitPrice != null
        && unitPrice.compareTo(calculatedUnitPrice) != 0) {
      throw new BusinessException(
          "PRICE_MISMATCH", "单位成本应等于包装价 ÷ 包装数量", HttpStatus.BAD_REQUEST);
    }
    if (unitPrice == null) {
      unitPrice = calculatedUnitPrice;
    }
    if (packagePrice == null) {
      packagePrice = unitPrice.multiply(packageQuantity).setScale(6, RoundingMode.HALF_UP);
    }

    if (!repository.updateInventoryItemPrice(
        user.tenantId(), normalizedCode, packageQuantity, packagePrice, unitPrice)) {
      throw new BusinessException("INVENTORY_ITEM_NOT_FOUND", "盘存物料不存在", HttpStatus.NOT_FOUND);
    }
    repository.logAction(
        user.tenantId(), user.id(), user.displayName(), "维护盘存物料价格",
        "store_inventory_item", normalizedCode, null,
        current.itemName() + "：包装数量 " + packageQuantity.toPlainString()
            + "，包装价 " + packagePrice.toPlainString()
            + "，单位成本 " + unitPrice.toPlainString());
    return repository.inventoryItem(user.tenantId(), normalizedCode)
        .orElseThrow(() -> new BusinessException(
            "INVENTORY_ITEM_NOT_FOUND", "盘存物料不存在", HttpStatus.NOT_FOUND));
  }

  public List<InventoryCheckResponse> inventoryChecks(AuthUser user) {
    requireInventoryRead(user);
    requireInventoryManagerBrand(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
      return scope.allowsAllStores()
          ? repository.inventoryChecks(user.tenantId(), null)
          : repository.inventoryChecks(user.tenantId(), null, Set.copyOf(scope.storeIds()));
    }
    return repository.inventoryChecks(user.tenantId(), scopedStoreId(user));
  }

  public InventoryCheckResponse inventoryCheck(AuthUser user, long id) {
    requireInventoryRead(user);
    requireInventoryManagerBrand(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "查看盘存单");
    return check;
  }

  @Transactional
  public InventoryCheckResponse saveInventoryCheck(AuthUser user, InventoryCheckRequest request) {
    requireInventorySave(user);
    if (request == null) {
      throw new BusinessException("BAD_REQUEST", "请填写盘存单", HttpStatus.BAD_REQUEST);
    }
    if (request.id() != null) {
      throw new BusinessException(
          "BAD_STATUS", "盘存单保存后即为已提交，不能再次修改，请新建盘存单", HttpStatus.CONFLICT);
    }
    String storeId = normalizeStoreForWrite(user, request.storeId());
    String storeName = repository.inventoryStoreName(user.tenantId(), storeId)
        .orElseThrow(() -> new BusinessException(
            "INVENTORY_STORE_NOT_ALLOWED",
            "店铺盘存仅限茹菓品牌门店",
            HttpStatus.BAD_REQUEST));
    String checkDate = normalizeDate(request.checkDate());
    List<InventoryCheckLineRequest> requestedLines = request.lines() == null ? List.of() : request.lines();
    if (requestedLines.isEmpty()) {
      throw new BusinessException("LINES_REQUIRED", "请至少填写一条盘存明细", HttpStatus.BAD_REQUEST);
    }
    if (requestedLines.size() > 500) {
      throw new BusinessException("TOO_MANY_LINES", "单张盘存单最多 500 条明细", HttpStatus.BAD_REQUEST);
    }
    List<InventoryCheckLineRequest> lines = canonicalInventoryLines(user.tenantId(), requestedLines);
    BigDecimal total = lines.stream()
        .map(line -> inventoryLineAmount(line.countedQuantity(), line.unitPrice()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    long id = repository.saveInventoryCheck(
        user.tenantId(),
        null,
        newInventoryCheckNo(),
        storeId,
        storeName,
        checkDate,
        request.note(),
        total,
        user.id(),
        lines
    );
    repository.logAction(
        user.tenantId(), user.id(), user.displayName(), "提交盘存单",
        "store_inventory_check", String.valueOf(id), storeId, "保存后自动提交，等待复核");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse submitInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "提交盘存单");
    if ("SUBMITTED".equals(check.status())) {
      return check;
    }
    throw new BusinessException("BAD_STATUS", "盘存单已经复核，不能重复提交", HttpStatus.CONFLICT);
  }

  @Transactional
  public InventoryCheckResponse reviewInventoryCheck(AuthUser user, long id) {
    requireInventoryReview(user);
    InventoryCheckResponse check = requireInventoryCheck(user.tenantId(), id);
    requireDomainStoreScope(
        user, DataScopeDomains.WAREHOUSE, check.storeId(), "复核盘存单");
    if ("REVIEWED".equals(check.status())) {
      return check;
    }
    String reviewerRole = AccessControlService.canonicalRole(user.role());
    if (!repository.reviewInventoryCheck(
        user.tenantId(), id, user.id(), user.displayName(), reviewerRole)) {
      InventoryCheckResponse concurrent = requireInventoryCheck(user.tenantId(), id);
      if ("REVIEWED".equals(concurrent.status())) {
        return concurrent;
      }
      throw new BusinessException("BAD_STATUS", "只有已提交盘存单可以复核", HttpStatus.CONFLICT);
    }
    repository.logAction(
        user.tenantId(), user.id(), user.displayName(), "复核盘存单",
        "store_inventory_check", String.valueOf(id), check.storeId(),
        "负责人：" + user.displayName() + "（" + inventoryReviewerRoleLabel(reviewerRole) + "）");
    return requireInventoryCheck(user.tenantId(), id);
  }

  @Transactional
  public InventoryCheckResponse cancelInventoryCheck(AuthUser user, long id) {
    requireInventorySave(user);
    throw new BusinessException(
        "BAD_STATUS", "盘存单保存后即为已提交，不支持作废", HttpStatus.CONFLICT);
  }

  @Transactional
  public InventoryCheckExcelExport exportInventoryCheck(AuthUser user, long id) {
    requireInventoryExport(user);
    InventoryCheckResponse check = inventoryCheck(user, id);
    byte[] content = buildInventoryCheckExcel(check);
    repository.logAction(
        user.tenantId(), user.id(), user.displayName(), "导出店铺盘存Excel",
        "store_inventory_check", String.valueOf(check.id()), check.storeId(),
        "盘存单号：" + check.checkNo() + "，明细行数：" + check.lines().size()
            + "，合计金额：" + check.totalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    String store = safeFileName(check.storeName());
    return new InventoryCheckExcelExport(
        store + "-店铺盘存-" + check.checkDate() + "-" + check.checkNo() + ".xlsx",
        content);
  }

  public List<ExamPaperResponse> examPapers(AuthUser user) {
    requireExamManage(user);
    requireExamScope(user, "管理考试试卷");
    return repository.examPapers(user.tenantId());
  }

  public ExamPaperResponse examPaper(AuthUser user, long paperId) {
    requireExamManage(user);
    requireExamScope(user, "管理考试试卷");
    return repository.examPaper(user.tenantId(), paperId, true)
        .orElseThrow(() -> new BusinessException("PAPER_NOT_FOUND", "试卷不存在", HttpStatus.NOT_FOUND));
  }

  public List<ExamAttemptResponse> examAttempts(AuthUser user) {
    requireExamReport(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
      if (DataScopeModes.SELF.equals(scope.mode())) {
        return repository.examAttempts(user.tenantId(), null, user.id());
      }
      return scope.allowsAllStores()
          ? repository.examAttempts(user.tenantId(), null, null)
          : repository.examAttempts(user.tenantId(), null, null, Set.copyOf(scope.storeIds()));
    }
    if ("STORE_MANAGER".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), requiredStore(user), null);
    }
    if ("EMPLOYEE".equals(user.role())) {
      return repository.examAttempts(user.tenantId(), null, user.id());
    }
    return repository.examAttempts(user.tenantId(), null, null);
  }

  public ExamAttemptResponse examAttempt(AuthUser user, long attemptId) {
    requireExamReport(user);
    ExamAttemptResponse attempt = repository.examAttempt(user.tenantId(), attemptId)
        .orElseThrow(() -> new BusinessException("ATTEMPT_NOT_FOUND", "考试记录不存在", HttpStatus.NOT_FOUND));
    DataScope examScope = accessControl == null
        ? null
        : accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (examScope != null && DataScopeModes.SELF.equals(examScope.mode())) {
      if (!Long.valueOf(user.id()).equals(attempt.submittedBy())) {
        throw new BusinessException("FORBIDDEN", "只能查看自己的考试成绩", HttpStatus.FORBIDDEN);
      }
    } else {
      requireDomainStoreScope(user, DataScopeDomains.EXAM, attempt.storeId(), "查看考试成绩");
    }
    if (accessControl == null && "EMPLOYEE".equals(user.role())
        && !Long.valueOf(user.id()).equals(attempt.submittedBy())) {
      throw new BusinessException("FORBIDDEN", "员工只能查看自己的考试成绩", HttpStatus.FORBIDDEN);
    }
    return attempt;
  }

  public List<TrainingMaterialResponse> trainingMaterials(AuthUser user) {
    requireExamLearn(user);
    requireExamScope(user, "查看培训资料");
    return repository.trainingMaterials(user.tenantId(), user.id());
  }

  @Transactional
  public List<TrainingMaterialResponse> markMaterialLearned(AuthUser user, long materialId) {
    requireExamLearn(user);
    requireExamScope(user, "记录培训学习进度");
    if (!repository.materialExists(user.tenantId(), materialId)) {
      throw new BusinessException("MATERIAL_NOT_FOUND", "培训资料不存在", HttpStatus.NOT_FOUND);
    }
    repository.markMaterialLearned(user.tenantId(), materialId, user.id(), user.displayName(), user.storeId());
    repository.logAction(user.tenantId(), user.id(), user.displayName(), "标记培训已学习", "training_material", String.valueOf(materialId), user.storeId(), "培训学习记录已落库");
    return trainingMaterials(user);
  }

  public List<TrainingLearningRecordResponse> learningRecords(AuthUser user) {
    requireExamReport(user);
    if (accessControl != null) {
      DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
      if (DataScopeModes.SELF.equals(scope.mode())) {
        return repository.learningRecords(user.tenantId(), null, null, user.id());
      }
      return scope.allowsAllStores()
          ? repository.learningRecords(user.tenantId(), null)
          : repository.learningRecords(user.tenantId(), null, Set.copyOf(scope.storeIds()));
    }
    return repository.learningRecords(user.tenantId(), scopedStoreId(user));
  }

  private List<InventoryCheckLineRequest> canonicalInventoryLines(
      long tenantId,
      List<InventoryCheckLineRequest> requestedLines
  ) {
    Map<String, InventoryItemResponse> catalog = new HashMap<>();
    for (InventoryItemResponse item : repository.inventoryItems(tenantId)) {
      catalog.put(item.itemCode(), item);
    }
    if (catalog.isEmpty()) {
      throw new BusinessException(
          "INVENTORY_CATALOG_EMPTY", "盘存物料档案为空，请联系老板维护", HttpStatus.CONFLICT);
    }

    Set<String> usedCodes = new HashSet<>();
    List<InventoryCheckLineRequest> normalized = new ArrayList<>(requestedLines.size());
    for (InventoryCheckLineRequest requested : requestedLines) {
      if (requested == null) {
        throw new BusinessException("BAD_LINE", "盘存明细不能为空", HttpStatus.BAD_REQUEST);
      }
      String itemCode = requiredText(requested.itemCode(), "盘存物料编码不能为空");
      if (!usedCodes.add(itemCode)) {
        throw new BusinessException(
            "DUPLICATE_ITEM", "盘存物料重复：" + itemCode, HttpStatus.BAD_REQUEST);
      }
      InventoryItemResponse item = catalog.get(itemCode);
      if (item == null || !Boolean.TRUE.equals(item.enabled())) {
        throw new BusinessException(
            "INVENTORY_ITEM_NOT_FOUND", "盘存物料不存在或已停用：" + itemCode, HttpStatus.BAD_REQUEST);
      }
      BigDecimal counted = nonNegativeDecimal(
          amount(requested.countedQuantity()), 4, "盘存数量不能小于 0");
      if (counted.compareTo(new BigDecimal("9999999999.9999")) > 0) {
        throw new BusinessException("BAD_QUANTITY", "盘存数量过大", HttpStatus.BAD_REQUEST);
      }
      BigDecimal unitPrice = item.unitPrice();
      if (unitPrice == null && counted.compareTo(BigDecimal.ZERO) > 0) {
        throw new BusinessException(
            "INVENTORY_PRICE_MISSING",
            item.itemName() + " 尚未维护价格，请先由老板补录价格",
            HttpStatus.BAD_REQUEST);
      }
      normalized.add(new InventoryCheckLineRequest(
          item.itemName(),
          item.itemCode(),
          item.category(),
          item.spec(),
          item.unit(),
          item.packageQuantity(),
          unitPrice == null ? BigDecimal.ZERO : unitPrice.setScale(6, RoundingMode.HALF_UP),
          item.packagePrice() == null
              ? BigDecimal.ZERO
              : item.packagePrice().setScale(6, RoundingMode.HALF_UP),
          counted,
          blankToNull(requested.note())
      ));
    }
    return List.copyOf(normalized);
  }

  private BigDecimal inventoryLineAmount(BigDecimal quantity, BigDecimal unitPrice) {
    return amount(quantity)
        .multiply(amount(unitPrice))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private byte[] buildInventoryCheckExcel(InventoryCheckResponse check) {
    try (Workbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("店铺盘存");
      InventoryExcelStyles styles = new InventoryExcelStyles(workbook);

      Row title = sheet.createRow(0);
      Cell titleCell = title.createCell(0);
      titleCell.setCellValue("店铺盘存表（" + check.checkDate() + "截止）");
      titleCell.setCellStyle(styles.title());
      sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 10));
      title.setHeightInPoints(30);

      Row meta = sheet.createRow(1);
      writeExcelText(meta, 0, "门店：" + check.storeName(), styles.meta());
      writeExcelText(meta, 3, "盘存单号：" + check.checkNo(), styles.meta());
      writeExcelText(meta, 7, "状态：" + check.statusLabel(), styles.meta());
      sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
      sheet.addMergedRegion(new CellRangeAddress(1, 1, 3, 6));
      sheet.addMergedRegion(new CellRangeAddress(1, 1, 7, 10));

      Row reviewer = sheet.createRow(2);
      writeExcelText(
          reviewer, 0,
          "负责人：" + (check.reviewedByName() == null ? "待复核" : check.reviewedByName()),
          styles.meta());
      writeExcelText(
          reviewer, 3,
          "负责人职务：" + (check.reviewedByRoleLabel() == null ? "待复核" : check.reviewedByRoleLabel()),
          styles.meta());
      writeExcelText(
          reviewer, 7,
          "复核时间：" + (check.reviewedAt() == null ? "待复核" : check.reviewedAt()),
          styles.meta());
      sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, 2));
      sheet.addMergedRegion(new CellRangeAddress(2, 2, 3, 6));
      sheet.addMergedRegion(new CellRangeAddress(2, 2, 7, 10));

      String[] headers = {
          "分类", "物料编码", "物品名称", "规格", "盘存单位",
          "包装数量", "包装价（元）", "单位成本（元）", "盘存数量", "金额（元）", "备注"
      };
      Row header = sheet.createRow(3);
      for (int column = 0; column < headers.length; column++) {
        writeExcelText(header, column, headers[column], styles.header());
      }

      int rowIndex = 4;
      for (var line : check.lines()) {
        Row row = sheet.createRow(rowIndex++);
        writeExcelText(row, 0, line.category(), styles.text());
        writeExcelText(row, 1, line.itemCode(), styles.text());
        writeExcelText(row, 2, line.itemName(), styles.text());
        writeExcelText(row, 3, line.spec(), styles.text());
        writeExcelText(row, 4, line.unit(), styles.center());
        writeExcelNumber(row, 5, line.packageQuantity(), styles.quantity());
        writeExcelNumber(row, 6, line.unitPriceEach(), styles.price());
        writeExcelNumber(row, 7, line.unitPrice(), styles.price());
        writeExcelNumber(row, 8, line.countedQuantity(), styles.quantity());
        writeExcelNumber(row, 9, line.amount(), styles.amount());
        writeExcelText(row, 10, line.note(), styles.text());
      }

      Row total = sheet.createRow(rowIndex);
      writeExcelText(total, 0, "盘存合计", styles.totalLabel());
      sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 8));
      writeExcelNumber(total, 9, check.totalAmount(), styles.totalAmount());
      writeExcelText(total, 10, "", styles.totalLabel());

      int[] widths = {12, 16, 22, 30, 12, 13, 15, 15, 15, 15, 24};
      for (int column = 0; column < widths.length; column++) {
        sheet.setColumnWidth(column, widths[column] * 256);
      }
      sheet.createFreezePane(0, 4);
      if (!check.lines().isEmpty()) {
        sheet.setAutoFilter(new CellRangeAddress(3, rowIndex - 1, 0, 10));
      }
      sheet.setRepeatingRows(new CellRangeAddress(0, 3, -1, -1));
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException ex) {
      throw new BusinessException(
          "INVENTORY_EXPORT_FAILED", "盘存 Excel 生成失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private void writeExcelText(Row row, int column, String value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private void writeExcelNumber(Row row, int column, BigDecimal value, CellStyle style) {
    Cell cell = row.createCell(column);
    if (value == null) {
      cell.setBlank();
    } else {
      cell.setCellValue(value.doubleValue());
    }
    cell.setCellStyle(style);
  }

  private String safeFileName(String value) {
    String normalized = value == null ? "门店" : value.trim();
    normalized = normalized.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
    return normalized.isBlank() ? "门店" : normalized;
  }

  private record InventoryExcelStyles(
      CellStyle title,
      CellStyle meta,
      CellStyle header,
      CellStyle text,
      CellStyle center,
      CellStyle quantity,
      CellStyle price,
      CellStyle amount,
      CellStyle totalLabel,
      CellStyle totalAmount
  ) {
    private InventoryExcelStyles(Workbook workbook) {
      this(
          titleStyle(workbook),
          metaStyle(workbook),
          headerStyle(workbook),
          borderedStyle(workbook, HorizontalAlignment.LEFT, null),
          borderedStyle(workbook, HorizontalAlignment.CENTER, null),
          borderedStyle(workbook, HorizontalAlignment.RIGHT, "#,##0.####"),
          borderedStyle(workbook, HorizontalAlignment.RIGHT, "¥#,##0.000000"),
          borderedStyle(workbook, HorizontalAlignment.RIGHT, "¥#,##0.00"),
          totalStyle(workbook, HorizontalAlignment.RIGHT, null),
          totalStyle(workbook, HorizontalAlignment.RIGHT, "¥#,##0.00")
      );
    }

    private static CellStyle titleStyle(Workbook workbook) {
      CellStyle style = workbook.createCellStyle();
      Font font = workbook.createFont();
      font.setBold(true);
      font.setFontHeightInPoints((short) 16);
      font.setColor(IndexedColors.WHITE.getIndex());
      style.setFont(font);
      style.setAlignment(HorizontalAlignment.CENTER);
      style.setVerticalAlignment(VerticalAlignment.CENTER);
      style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle metaStyle(Workbook workbook) {
      CellStyle style = workbook.createCellStyle();
      style.setAlignment(HorizontalAlignment.LEFT);
      style.setVerticalAlignment(VerticalAlignment.CENTER);
      style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
      CellStyle style = borderedStyle(workbook, HorizontalAlignment.CENTER, null);
      Font font = workbook.createFont();
      font.setBold(true);
      font.setColor(IndexedColors.WHITE.getIndex());
      style.setFont(font);
      style.setFillForegroundColor(IndexedColors.GREEN.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle totalStyle(
        Workbook workbook,
        HorizontalAlignment alignment,
        String format
    ) {
      CellStyle style = borderedStyle(workbook, alignment, format);
      Font font = workbook.createFont();
      font.setBold(true);
      style.setFont(font);
      style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      return style;
    }

    private static CellStyle borderedStyle(
        Workbook workbook,
        HorizontalAlignment alignment,
        String format
    ) {
      CellStyle style = workbook.createCellStyle();
      style.setAlignment(alignment);
      style.setVerticalAlignment(VerticalAlignment.CENTER);
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
      if (format != null) {
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
      }
      return style;
    }
  }

  private InventoryCheckResponse requireInventoryCheck(long tenantId, long id) {
    InventoryCheckResponse check = repository.inventoryCheck(tenantId, id)
        .orElseThrow(() -> new BusinessException("INVENTORY_CHECK_NOT_FOUND", "盘存单不存在", HttpStatus.NOT_FOUND));
    if (repository.inventoryStoreName(tenantId, check.storeId()).isEmpty()) {
      // Deliberately use the same not-found response so a caller cannot probe another brand's
      // historical inventory records by id.
      throw new BusinessException(
          "INVENTORY_CHECK_NOT_FOUND", "盘存单不存在", HttpStatus.NOT_FOUND);
    }
    return check;
  }

  private String normalizeStoreForWrite(AuthUser user, String requestedStoreId) {
    if (accessControl != null) {
      String storeId = requestedStoreId == null || requestedStoreId.isBlank()
          ? ("STORE_MANAGER".equals(user.role()) ? requiredStore(user) : null)
          : requestedStoreId.trim();
      if (storeId == null) {
        throw new BusinessException("STORE_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
      }
      requireDomainStoreScope(user, DataScopeDomains.WAREHOUSE, storeId, "保存盘存单");
      return storeId;
    }
    if ("STORE_MANAGER".equals(user.role())) {
      return requiredStore(user);
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      throw new BusinessException("STORE_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    return requestedStoreId.trim();
  }

  private String scopedStoreId(AuthUser user) {
    return "STORE_MANAGER".equals(user.role()) ? requiredStore(user) : null;
  }

  private void requireInventoryManagerBrand(AuthUser user) {
    if (!isStoreManager(user)) {
      return;
    }
    String storeId = requiredStore(user);
    if (repository.inventoryStoreName(user.tenantId(), storeId).isEmpty()) {
      throw new BusinessException(
          "INVENTORY_STORE_NOT_ALLOWED",
          "店铺盘存仅限茹菓品牌门店",
          HttpStatus.FORBIDDEN);
    }
  }

  private void requireDomainStoreScope(
      AuthUser user,
      String domain,
      String storeId,
      String action
  ) {
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, domain, storeId, action);
      return;
    }
    if ("STORE_MANAGER".equals(user.role()) && !requiredStore(user).equals(storeId)) {
      throw new BusinessException("FORBIDDEN", "店长只能访问本门店数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireExamScope(AuthUser user, String action) {
    if (accessControl == null) {
      return;
    }
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.EXAM);
    if (scope.allowsAllStores()
        || DataScopeModes.SELF.equals(scope.mode())
        || !scope.storeIds().isEmpty()) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有培训考试数据范围", HttpStatus.FORBIDDEN);
  }

  private String requiredStore(AuthUser user) {
    if (user.storeId() == null || user.storeId().isBlank()) {
      throw new BusinessException("NO_STORE_SCOPE", "当前账号未绑定门店", HttpStatus.FORBIDDEN);
    }
    return user.storeId();
  }

  private void requireInventoryRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInventoryRead(user);
    } else {
      requireLegacyPermission(user, PermissionCodes.INVENTORY_READ, "无权查看盘存单");
    }
    if (!AccessControlService.hasAnyRole(
        user, "STORE_MANAGER", "FINANCE", "SUPERVISOR", "WAREHOUSE")) {
      throw new BusinessException(
          "FORBIDDEN", "当前角色无权查看店铺盘存", HttpStatus.FORBIDDEN);
    }
  }

  private void requireInventorySave(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInventoryManage(user);
    } else {
      requireLegacyPermission(user, PermissionCodes.INVENTORY_MANAGE, "无权保存盘存单");
    }
    if (!isStoreManager(user)) {
      throw new BusinessException(
          "FORBIDDEN", "只有店长可以录入和提交店铺盘存", HttpStatus.FORBIDDEN);
    }
  }

  private void requireInventoryReview(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInventoryReview(user);
    } else {
      requireLegacyPermission(user, PermissionCodes.INVENTORY_REVIEW, "无权复核盘存单");
    }
    String role = user == null ? "" : AccessControlService.canonicalRole(user.role());
    if (!Set.of("BOSS", "FINANCE", "SUPERVISOR", "WAREHOUSE").contains(role)) {
      throw new BusinessException(
          "FORBIDDEN", "只有老板、财务、督导或仓管可以复核店铺盘存", HttpStatus.FORBIDDEN);
    }
  }

  private void requireInventoryPriceManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInventoryPriceManage(user);
    } else {
      requireLegacyPermission(user, PermissionCodes.INVENTORY_MANAGE, "无权维护盘存物料价格");
    }
    requireBoss(user, "只有老板可以维护盘存物料价格");
  }

  private void requireInventoryExport(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireInventoryExport(user);
    } else {
      requireLegacyPermission(user, PermissionCodes.INVENTORY_READ, "无权导出盘存 Excel");
    }
    String role = user == null ? "" : AccessControlService.canonicalRole(user.role());
    if (!Set.of("BOSS", "FINANCE", "SUPERVISOR", "WAREHOUSE").contains(role)) {
      throw new BusinessException(
          "FORBIDDEN", "只有老板、财务、督导或仓库可以导出盘存 Excel", HttpStatus.FORBIDDEN);
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private void requireBoss(AuthUser user, String message) {
    if (AccessControlService.isBoss(user)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }

  private void requireExamManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamManage(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_MANAGE, "无权管理考试系统");
  }

  private void requireExamLearn(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamRead(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_LEARN, "无权访问培训资料");
  }

  private void requireExamReport(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireExamCompanyRead(user);
      return;
    }
    requireLegacyPermission(user, PermissionCodes.EXAM_REPORT, "无权查看培训考试报表");
  }

  private void requireLegacyPermission(AuthUser user, String permissionCode, String message) {
    if (AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(permissionCode)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
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

  private String inventoryReviewerRoleLabel(String role) {
    return switch (AccessControlService.canonicalRole(role)) {
      case "BOSS" -> "老板";
      case "FINANCE" -> "财务";
      case "SUPERVISOR" -> "督导";
      case "WAREHOUSE" -> "仓管";
      default -> role;
    };
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal positiveDecimal(BigDecimal value, int scale, String message) {
    BigDecimal normalized = nonNegativeDecimal(value, scale, message);
    if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_NUMBER", message, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BigDecimal nonNegativeDecimal(BigDecimal value, int scale, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException("BAD_NUMBER", message, HttpStatus.BAD_REQUEST);
    }
    if (value.stripTrailingZeros().scale() > scale) {
      throw new BusinessException(
          "BAD_NUMBER_SCALE", "最多支持 " + scale + " 位小数", HttpStatus.BAD_REQUEST);
    }
    return value.setScale(scale, RoundingMode.UNNECESSARY);
  }

  private String requiredText(String value, String message) {
    String normalized = blankToNull(value);
    if (normalized == null) {
      throw new BusinessException("BAD_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
