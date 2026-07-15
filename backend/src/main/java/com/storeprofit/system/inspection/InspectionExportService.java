package com.storeprofit.system.inspection;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Produces an inspection report by filling the controlled source workbook instead of rebuilding a
 * generic spreadsheet.  All photo processing happens on an export copy; the stored attachment is
 * never changed.
 */
@Service
public class InspectionExportService {
  static final String TEMPLATE_RESOURCE = "inspection-templates/rugua-inspection-report-v20250623.xlsx";
  static final String TEMPLATE_SHA256 = "5988625011E43FED7812B36B2BBB2C4F2FF3468B0142945113D5D6DF1AC8F637";
  private static final int PHOTO_PAGE_FIRST_ROW = 15;
  private static final int PHOTO_PAGE_LAST_ROW = 134;
  private static final int PHOTO_PAGE_LAST_COLUMN = 16;
  private static final int PHOTO_SLOT_COUNT = 12;
  private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private static final List<PhotoSlot> PHOTO_SLOTS = List.of(
      new PhotoSlot(0, 15, 3, 35, 635, 109220, 464185, 115570),
      new PhotoSlot(3, 15, 6, 35, 504825, 122555, 1682750, 114300),
      new PhotoSlot(6, 15, 9, 35, 1896110, 149225, 299085, 151765),
      new PhotoSlot(6, 36, 9, 75, 314960, 161925, 383540, 85725),
      new PhotoSlot(0, 36, 6, 75, 635, 51435, 160020, 40005),
      new PhotoSlot(9, 15, 13, 36, 316230, 149225, 255270, 36195),
      new PhotoSlot(9, 36, 16, 75, 471170, 147955, 60960, 107315),
      new PhotoSlot(0, 75, 6, 97, 635, 111760, 173355, 95885),
      new PhotoSlot(6, 75, 9, 97, 292100, 147955, 487680, 138430),
      new PhotoSlot(9, 75, 16, 97, 559435, 147320, 231775, 167005),
      new PhotoSlot(0, 97, 5, 134, 33655, 147955, 610870, 107315),
      new PhotoSlot(5, 98, 8, 133, 673735, 28575, 439420, 147955)
  );

  private static final List<Integer> MATERIAL_ROWS = List.of(2, 3, 4);
  private static final List<Integer> HYGIENE_ROWS = List.of(5, 6, 7, 8, 9, 10);
  private static final List<Integer> SERVICE_ROWS = List.of(11, 12);

  private final InspectionService inspectionService;
  private final StorageService storageService;
  private final InspectionImageNormalizer imageNormalizer;
  private final AuditRepository auditRepository;

  public InspectionExportService(
      InspectionService inspectionService,
      StorageService storageService,
      InspectionImageNormalizer imageNormalizer,
      AuditRepository auditRepository
  ) {
    this.inspectionService = inspectionService;
    this.storageService = storageService;
    this.imageNormalizer = imageNormalizer;
    this.auditRepository = auditRepository;
  }

  public InspectionExportFile export(AuthUser user, String id) {
    InspectionRecordResponse record = inspectionService.prepareForExport(user, id);
    requireExportScore(record);
    try {
      byte[] template = readVerifiedTemplate();
      try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
           ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        XSSFSheet report = workbook.getSheetAt(0);
        clearTemplatePictures(report);
        clearRemoteSample(report);
        configurePrint(report, workbook.getSheetIndex(report), false);
        fillReport(report, record, false);

        List<Long> attachmentIds = attachmentIds(record);
        int pageCount = Math.max(1, (attachmentIds.size() + PHOTO_SLOT_COUNT - 1) / PHOTO_SLOT_COUNT);
        List<XSSFSheet> photoPages = new ArrayList<>();
        photoPages.add(report);
        for (int pageNumber = 2; pageNumber <= pageCount; pageNumber++) {
          XSSFSheet page = workbook.cloneSheet(workbook.getSheetIndex(report));
          workbook.setSheetName(workbook.getSheetIndex(page), "现场照片-" + pageNumber);
          clearTemplatePictures(page);
          clearRemoteSample(page);
          copyPageLayout(report, page, workbook.getSheetIndex(page));
          fillReport(page, record, true);
          photoPages.add(page);
        }

        insertPhotos(workbook, photoPages, attachmentIds, user, record);
        workbook.write(output);
        byte[] content = output.toByteArray();
        auditRepository.writeLog(user, new AuditLogRequest(
            "导出巡检报告", "inspection_record", record.id(), record.storeId(),
            inspectionMonth(record.inspectionDate()),
            "基于受控巡检模板生成Excel；现场照片数：" + attachmentIds.size(),
            null, null));
        return new InspectionExportFile(fileName(record), content);
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BusinessException(
          "INSPECTION_EXPORT_FAILED",
          "巡检报告生成失败，请检查巡检评分和现场照片后重试。",
          HttpStatus.INTERNAL_SERVER_ERROR
      );
    }
  }

  private byte[] readVerifiedTemplate() throws IOException {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (input == null) {
        throw new BusinessException(
            "INSPECTION_EXPORT_TEMPLATE_UNAVAILABLE", "巡检报告模板不可用，请联系系统管理员。", HttpStatus.CONFLICT);
      }
      byte[] content = input.readAllBytes();
      if (!TEMPLATE_SHA256.equalsIgnoreCase(sha256(content))) {
        throw new BusinessException(
            "INSPECTION_EXPORT_TEMPLATE_INVALID", "巡检报告模板校验失败，请联系系统管理员。", HttpStatus.CONFLICT);
      }
      return content;
    }
  }

  private String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).toUpperCase(Locale.ROOT);
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private void requireExportScore(InspectionRecordResponse record) {
    List<String> missing = new ArrayList<>();
    if (record.displayFullScore() == null || record.displayFullScore().signum() <= 0) {
      missing.add("满分");
    }
    if (record.displayPassScore() == null || record.displayPassScore().signum() <= 0) {
      missing.add("合格线");
    }
    if (record.displayScore() == null || record.displayScore().signum() < 0) {
      missing.add("最终得分");
    }
    if (record.standardVersion() == null || record.standardVersion().isBlank()) {
      missing.add("标准版本");
    }
    if (!missing.isEmpty()) {
      throw new InspectionScoreRepairRequiredException(missing);
    }
  }

  private void fillReport(XSSFSheet sheet, InspectionRecordResponse record, boolean photoContinuation) {
    if (photoContinuation) {
      setText(sheet, 0, 0, "现场照片续页");
    } else {
      setText(sheet, 0, 0, safeText(record.storeName()) + "巡检报告");
    }
    setText(sheet, 1, 1, "门店：" + safeText(record.storeName()) + "（" + safeText(record.brandName()) + "）");
    setText(sheet, 1, 5, "巡检日期：" + safeText(record.inspectionDate()));
    setText(sheet, 1, 7, "督导：" + safeText(record.inspector()));

    clearIssueSlots(sheet);
    if (!photoContinuation) {
      fillIssues(sheet, record.itemResults());
    }
    String note = safeText(record.note());
    if (photoContinuation) {
      note = "本页为现场照片续页";
    }
    setText(sheet, 13, 1, note.isBlank() ? "无补充说明" : note);
    setText(sheet, 14, 1, "标准版本：" + safeText(record.standardVersion())
        + "；满分：" + number(record.displayFullScore()) + "分");
    setText(sheet, 14, 5, "合格线：" + number(record.displayPassScore())
        + "分；督导：" + safeText(record.inspector()));
    setText(sheet, 13, 7, "巡检结果：" + resultText(record));
    setText(sheet, 14, 7, "总分：" + number(record.displayScore()) + " / "
        + number(record.displayFullScore()));
  }

  private void clearIssueSlots(XSSFSheet sheet) {
    for (Integer row : concat(MATERIAL_ROWS, HYGIENE_ROWS, SERVICE_ROWS)) {
      clearCell(sheet, row, 1);
      clearCell(sheet, row, 5);
      clearCell(sheet, row, 7);
    }
  }

  private List<Integer> concat(List<Integer> first, List<Integer> second, List<Integer> third) {
    List<Integer> rows = new ArrayList<>(first.size() + second.size() + third.size());
    rows.addAll(first);
    rows.addAll(second);
    rows.addAll(third);
    return rows;
  }

  private void fillIssues(XSSFSheet sheet, List<InspectionItemResultResponse> items) {
    List<InspectionItemResultResponse> material = new ArrayList<>();
    List<InspectionItemResultResponse> hygiene = new ArrayList<>();
    List<InspectionItemResultResponse> service = new ArrayList<>();
    for (InspectionItemResultResponse item : items == null ? List.<InspectionItemResultResponse>of() : items) {
      if (item == null || (!item.issueFound() && amount(item.deductionScore()).signum() <= 0)) {
        continue;
      }
      String category = inspectionCategory(item.dimension());
      if ("MATERIAL".equals(category)) {
        material.add(item);
      } else if ("HYGIENE".equals(category)) {
        hygiene.add(item);
      } else if ("SERVICE".equals(category)) {
        service.add(item);
      }
    }
    int overflow = fillIssueGroup(sheet, material, MATERIAL_ROWS)
        + fillIssueGroup(sheet, hygiene, HYGIENE_ROWS)
        + fillIssueGroup(sheet, service, SERVICE_ROWS);
    if (overflow > 0) {
      setText(sheet, 13, 1, "另有" + overflow + "条问题明细请在系统巡检记录中查看。");
    }
  }

  private int fillIssueGroup(
      XSSFSheet sheet,
      List<InspectionItemResultResponse> items,
      List<Integer> rows
  ) {
    int count = Math.min(items.size(), rows.size());
    for (int index = 0; index < count; index++) {
      InspectionItemResultResponse item = items.get(index);
      int row = rows.get(index);
      setText(sheet, row, 1, safeText(item.code()) + " · " + safeText(item.title()));
      String detail = safeText(item.deductionReason());
      if (detail.isBlank()) {
        detail = safeText(item.description());
      }
      setText(sheet, row, 5, detail.isBlank() ? "已记录问题" : detail);
      setText(sheet, row, 7, "-" + number(amount(item.deductionScore())) + "分");
    }
    return Math.max(0, items.size() - rows.size());
  }

  private void insertPhotos(
      XSSFWorkbook workbook,
      List<XSSFSheet> pages,
      List<Long> attachmentIds,
      AuthUser user,
      InspectionRecordResponse record
  ) {
    if (attachmentIds.isEmpty()) {
      setText(pages.getFirst(), PHOTO_PAGE_FIRST_ROW, 0, "无现场照片");
      return;
    }
    for (int index = 0; index < attachmentIds.size(); index++) {
      long attachmentId = attachmentIds.get(index);
      XSSFSheet page = pages.get(index / PHOTO_SLOT_COUNT);
      PhotoSlot slot = PHOTO_SLOTS.get(index % PHOTO_SLOT_COUNT);
      StorageService.InspectionAttachmentContent attachment = storageService
          .inspectionAttachment(user, attachmentId, record.id())
          .orElseThrow(() -> new BusinessException(
              "INSPECTION_EXPORT_ATTACHMENT_UNAVAILABLE",
              "巡检照片附件#" + attachmentId + "不可访问或不存在。",
              HttpStatus.CONFLICT));
      byte[] normalized;
      try {
        normalized = imageNormalizer.normalize(
            attachment.content(), usableWidth(page, slot), usableHeight(page, slot));
      } catch (Exception exception) {
        throw new BusinessException(
            "INSPECTION_EXPORT_PHOTO_INVALID",
            "照片“" + safeFileComponent(attachment.fileName()) + "”无法读取、格式不支持或已损坏。",
            HttpStatus.CONFLICT);
      }
      int pictureId = workbook.addPicture(normalized, XSSFWorkbook.PICTURE_TYPE_JPEG);
      XSSFDrawing drawing = page.createDrawingPatriarch();
      drawing.createPicture(anchor(slot), pictureId);
    }
  }

  private List<Long> attachmentIds(InspectionRecordResponse record) {
    Set<Long> ids = new LinkedHashSet<>();
    for (InspectionItemResultResponse item : record.itemResults()) {
      if (item == null) {
        continue;
      }
      addIds(ids, item.photoAttachmentIds());
      addIds(ids, item.beforePhotoAttachmentIds());
      addIds(ids, item.afterPhotoAttachmentIds());
    }
    return List.copyOf(ids);
  }

  private void addIds(Set<Long> destination, List<Long> candidates) {
    if (candidates == null) {
      return;
    }
    for (Long candidate : candidates) {
      if (candidate != null && candidate > 0) {
        destination.add(candidate);
      }
    }
  }

  private XSSFClientAnchor anchor(PhotoSlot slot) {
    XSSFClientAnchor anchor = new XSSFClientAnchor();
    anchor.setDx1(slot.dx1());
    anchor.setDy1(slot.dy1());
    anchor.setDx2(slot.dx2());
    anchor.setDy2(slot.dy2());
    anchor.setCol1(slot.col1());
    anchor.setRow1(slot.row1());
    anchor.setCol2(slot.col2());
    anchor.setRow2(slot.row2());
    anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
    return anchor;
  }

  private int usableWidth(XSSFSheet sheet, PhotoSlot slot) {
    double width = 0;
    for (int column = slot.col1(); column <= slot.col2(); column++) {
      width += sheet.getColumnWidthInPixels(column);
    }
    return Math.max(1, (int) Math.round(width));
  }

  private int usableHeight(XSSFSheet sheet, PhotoSlot slot) {
    double height = 0;
    for (int row = slot.row1(); row <= slot.row2(); row++) {
      Row source = sheet.getRow(row);
      height += (source == null ? sheet.getDefaultRowHeightInPoints() : source.getHeightInPoints()) * 96d / 72d;
    }
    return Math.max(1, (int) Math.round(height));
  }

  private void clearTemplatePictures(XSSFSheet sheet) {
    XSSFDrawing drawing = sheet.getDrawingPatriarch();
    if (drawing == null) {
      return;
    }
    drawing.getCTDrawing().getTwoCellAnchorList().clear();
    drawing.getCTDrawing().getOneCellAnchorList().clear();
    drawing.getCTDrawing().getAbsoluteAnchorList().clear();
  }

  private void clearRemoteSample(XSSFSheet sheet) {
    for (int row = 0; row <= 21; row++) {
      for (int column = 239; column <= 253; column++) {
        clearCell(sheet, row, column);
      }
    }
  }

  private void configurePrint(XSSFSheet sheet, int index, boolean photoOnly) {
    sheet.setAutobreaks(true);
    sheet.setFitToPage(true);
    sheet.setPrintGridlines(false);
    PrintSetup print = sheet.getPrintSetup();
    print.setPaperSize(PrintSetup.A4_PAPERSIZE);
    print.setLandscape(false);
    print.setFitWidth((short) 1);
    print.setFitHeight((short) 0);
    // Keep the continuation heading and report identity on every A4 page as well as the photo
    // slots; a photo-only print area would silently omit the page's context.
    sheet.getWorkbook().setPrintArea(index, "A1:Q135");
  }

  private void copyPageLayout(XSSFSheet source, XSSFSheet target, int targetIndex) {
    configurePrint(target, targetIndex, true);
    for (short margin : List.of(
        Sheet.LeftMargin, Sheet.RightMargin, Sheet.TopMargin, Sheet.BottomMargin,
        Sheet.HeaderMargin, Sheet.FooterMargin)) {
      target.setMargin(margin, source.getMargin(margin));
    }
    target.setHorizontallyCenter(source.getHorizontallyCenter());
    target.setVerticallyCenter(source.getVerticallyCenter());
    target.getHeader().setLeft(source.getHeader().getLeft());
    target.getHeader().setCenter(source.getHeader().getCenter());
    target.getHeader().setRight(source.getHeader().getRight());
    target.getFooter().setLeft(source.getFooter().getLeft());
    target.getFooter().setCenter(source.getFooter().getCenter());
    target.getFooter().setRight(source.getFooter().getRight());
  }

  private void setText(XSSFSheet sheet, int rowIndex, int columnIndex, String value) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      row = sheet.createRow(rowIndex);
    }
    Cell cell = row.getCell(columnIndex);
    if (cell == null) {
      cell = row.createCell(columnIndex);
    }
    cell.setCellValue(value == null ? "" : value);
  }

  private void clearCell(XSSFSheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      return;
    }
    Cell cell = row.getCell(columnIndex);
    if (cell != null) {
      cell.setBlank();
    }
  }

  private String inspectionCategory(String dimension) {
    String value = safeText(dimension);
    if (value.contains("物料") || "MATERIAL".equalsIgnoreCase(value)) {
      return "MATERIAL";
    }
    if (value.contains("卫生") || "HYGIENE".equalsIgnoreCase(value)) {
      return "HYGIENE";
    }
    if (value.contains("服务") || "SERVICE".equalsIgnoreCase(value)) {
      return "SERVICE";
    }
    return null;
  }

  private String resultText(InspectionRecordResponse record) {
    if ("RED_LINE_FAILED".equals(record.displayResultCode())) {
      return "不合格（触发红线）";
    }
    return record.displayPassed() ? "合格" : "不合格";
  }

  private BigDecimal amount(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO.setScale(2) : amount.setScale(2, RoundingMode.HALF_UP);
  }

  private String number(BigDecimal amount) {
    return this.amount(amount).stripTrailingZeros().toPlainString();
  }

  private String inspectionMonth(String inspectionDate) {
    return inspectionDate != null && inspectionDate.length() >= 7 ? inspectionDate.substring(0, 7) : null;
  }

  private String fileName(InspectionRecordResponse record) {
    String date = record.inspectionDate();
    try {
      date = LocalDate.parse(record.inspectionDate()).format(FILE_DATE);
    } catch (Exception ignored) {
      date = safeFileComponent(date).replace("-", "");
    }
    return "巡检报告_" + safeFileComponent(record.storeName()) + "_" + date + "_"
        + safeFileComponent(record.id()) + ".xlsx";
  }

  private String safeText(String value) {
    return value == null ? "" : value.trim();
  }

  private String safeFileComponent(String value) {
    String normalized = safeText(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
    return normalized.isBlank() ? "未命名" : normalized;
  }

  private record PhotoSlot(
      int col1,
      int row1,
      int col2,
      int row2,
      int dx1,
      int dy1,
      int dx2,
      int dy2
  ) {
  }
}
