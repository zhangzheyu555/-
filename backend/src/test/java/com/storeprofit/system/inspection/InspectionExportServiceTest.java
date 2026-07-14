package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InspectionExportServiceTest {
  @Test
  void createsReadableThreeSheetWorkbookAndDegradesDamagedPhoto() throws Exception {
    InspectionService inspectionService = mock(InspectionService.class);
    StorageService storageService = mock(StorageService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    InspectionRecordResponse record = record();
    when(inspectionService.record(user, "INSP-1")).thenReturn(record);
    when(storageService.inspectionAttachment(user, 9L, "INSP-1")).thenReturn(Optional.of(
        new StorageService.InspectionAttachmentContent(
            9L, "s1", "INSPECTION_RECORD", "INSP-1", "damaged.webp", "image/webp",
            4, new byte[]{1, 2, 3, 4}, 1L, LocalDateTime.of(2026, 7, 13, 10, 30))));
    byte[] validPng = png();
    for (long id = 10; id <= 15; id++) {
      when(storageService.inspectionAttachment(user, id, "INSP-1")).thenReturn(Optional.of(
          attachment(id, "photo-" + id + ".png", validPng)));
    }
    InspectionExportService service = new InspectionExportService(
        inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

    InspectionExportFile file = service.export(user, "INSP-1");

    assertThat(file.filename()).isEqualTo("茹菓-荆州之星店-巡检报告-20260713-INSP-1.xlsx");
    assertThat(file.content()).isNotEmpty();
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
      assertThat(workbook.getSheetName(0)).isEqualTo("巡检报告");
      assertThat(workbook.getSheetName(1)).isEqualTo("照片证据");
      assertThat(workbook.getSheetName(2)).isEqualTo("整改跟踪");
      assertThat(sheetText(workbook, "巡检报告"))
          .contains("原始物料得分", "20.00", "原始卫生得分", "30.00", "原始服务得分", "48.00")
          .contains("最终物料得分", "37.00", "最终卫生得分", "63.00", "最终服务得分", "96.00")
          .contains("分类分口径", "修正版重算", "196.00/200.00")
          .contains("保存快照标准分", "保存快照实际分");
      assertThat(workbook.getSheet("照片证据").getPrintSetup().getLandscape()).isTrue();
      assertThat(sheetText(workbook, "照片证据")).contains("照片读取失败", "M-01", "荆州之星店");
      assertThat(sheetText(workbook, "照片证据")).contains("上传时间", "已整改");
      XSSFSheet evidence = (XSSFSheet) workbook.getSheet("照片证据");
      assertThat(evidence.getDrawingPatriarch().getShapes()).hasSize(1);
      assertPictureAspectRatios(evidence);
      XSSFSheet rectification = (XSSFSheet) workbook.getSheet("整改跟踪");
      assertThat(rectification.getDrawingPatriarch()).isNotNull();
      assertThat(rectification.getDrawingPatriarch().getShapes()).hasSize(5);
      assertThat(sheetText(workbook, "整改跟踪")).contains("已整改");
      assertPictureAspectRatios(rectification);
    }
    verify(storageService).inspectionAttachment(user, 15L, "INSP-1");
    ArgumentCaptor<AuditLogRequest> log = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(), log.capture());
    assertThat(log.getValue().action()).isEqualTo("导出巡检报告");
    assertThat(log.getValue().reason()).doesNotContain("damaged.webp", "mysql://");
  }

  @Test
  void exportsLegacyHundredPointOriginalAndTwoHundredPointReferenceWithoutDoubleConversion()
      throws Exception {
    InspectionService inspectionService = mock(InspectionService.class);
    StorageService storageService = mock(StorageService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);
    InspectionRecordResponse record = legacyHundredPointRecord();
    when(inspectionService.record(user, "LEGACY-98")).thenReturn(record);
    InspectionExportService service = new InspectionExportService(
        inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

    InspectionExportFile file = service.export(user, "LEGACY-98");

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
      assertThat(sheetText(workbook, "巡检报告"))
          .contains("原始成绩", "98.00/100.00")
          .contains("展示成绩", "196.00/200.00")
          .contains("200分制换算参考", "196.00/200.00")
          .contains("分类分口径", "历史100分制换算")
          .contains("分制迁移", "旧100分制已换算（原始值保留）")
          .contains("保存快照标准分", "120.0", "保存快照扣分", "4.0");
      Row clause = findRow(workbook, "巡检报告", 1, "L-01");
      assertThat(clause.getCell(4).getNumericCellValue()).isEqualTo(120.0);
      assertThat(clause.getCell(5).getNumericCellValue()).isEqualTo(116.0);
      assertThat(clause.getCell(6).getNumericCellValue()).isEqualTo(4.0);
    }
  }

  private String sheetText(Workbook workbook, String sheetName) {
    StringBuilder text = new StringBuilder();
    for (Row row : workbook.getSheet(sheetName)) {
      for (Cell cell : row) {
        text.append(cell.toString()).append('|');
      }
    }
    return text.toString();
  }

  private Row findRow(Workbook workbook, String sheetName, int column, String value) {
    for (Row row : workbook.getSheet(sheetName)) {
      Cell cell = row.getCell(column);
      if (cell != null && value.equals(cell.toString())) {
        return row;
      }
    }
    throw new AssertionError("未找到导出行: " + value);
  }

  private InspectionRecordResponse record() {
    InspectionItemResultResponse item = new InspectionItemResultResponse(
        1L, 1L, "物料标准", "M-01", "时效标签", "标签必须真实", "现场核对",
        bd("1"), bd("0"), bd("1"), true, "YELLOW", false, "标签填写不完整",
        List.of(9L, 15L), "店长", java.time.LocalDate.of(2026, 7, 15), "已完成", null,
        List.of(10L, 12L, 13L), List.of(11L, 14L), 1);
    InspectionResultRepairAudit repair = new InspectionResultRepairAudit(
        1L, 38L, "2025.11.06", bd("200"), bd("180"), bd("98"),
        bd("20"), bd("30"), bd("48"), "FAILED", false,
        40L, "2025.11.06-R1", bd("200"), bd("180"), bd("196"),
        bd("37"), bd("63"), bd("96"), "PASSED", true,
        "RECALCULATED", "按修正版标准重算", 105, 105, 1L,
        LocalDateTime.of(2026, 7, 13, 11, 0));
    InspectionResultPresentation presentation = InspectionResultPolicy.present(
        bd("200"), bd("98"), bd("20"), bd("30"), bd("48"), false, "FAILED", "[]",
        "2025.11.06", repair);
    return new InspectionRecordResponse(
        "INSP-1", "s1", "001", "荆州之星店", 1L, "茹菓", "2026-07-13",
        "督导", "茹菓", bd("200"), bd("98"), false, "[]", "[]", "[]", null,
        38L, "2025.11.06", bd("20"), bd("30"), bd("48"), "FAILED", List.of(item),
        presentation);
  }

  private InspectionRecordResponse legacyHundredPointRecord() {
    InspectionItemResultResponse item = new InspectionItemResultResponse(
        2L, null, "旧制", "L-01", "旧制扣分项", "历史快照", "现场核对",
        bd("120"), bd("116"), bd("4"), false, "NORMAL", false, null,
        List.of(), null, null, "无需整改", null, List.of(), List.of(), 1);
    InspectionResultPresentation presentation = InspectionResultPolicy.present(
        bd("100"), bd("98"), bd("30"), bd("30"), bd("38"), true, "PASSED", "[]",
        "legacy-100", null);
    InspectionScoreScaleMigrationAudit scaleAudit = new InspectionScoreScaleMigrationAudit(
        2L, InspectionScoreScaleMigrationAudit.HUNDRED_TO_TWO_HUNDRED,
        bd("100"), null, bd("98"), bd("30"), bd("30"), bd("38"), true, "PASSED",
        bd("200"), bd("180"), bd("196"), bd("60"), bd("60"), bd("76"), true, "PASSED",
        LocalDateTime.of(2026, 7, 13, 16, 0));
    return new InspectionRecordResponse(
        "LEGACY-98", "s1", "001", "荆州之星店", 1L, "茹菓", "2025-10-01",
        "督导", "茹菓", bd("100"), bd("98"), true, "[]", "[]", "[]", null,
        1L, "legacy-100", bd("30"), bd("30"), bd("38"), "PASSED", List.of(item),
        presentation, scaleAudit);
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }

  private StorageService.InspectionAttachmentContent attachment(long id, String name, byte[] content) {
    return new StorageService.InspectionAttachmentContent(
        id, "s1", "INSPECTION_RECORD", "INSP-1", name, "image/png", content.length,
        content, 1L, LocalDateTime.of(2026, 7, 13, 10, 30));
  }

  private byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    graphics.setColor(java.awt.Color.ORANGE);
    graphics.fillRect(0, 0, 4, 3);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private void assertPictureAspectRatios(XSSFSheet sheet) {
    for (XSSFShape shape : sheet.getDrawingPatriarch().getShapes()) {
      if (!(shape instanceof XSSFPicture picture)) {
        continue;
      }
      var anchor = picture.getClientAnchor();
      double widthPixels = 0;
      for (int column = anchor.getCol1(); column < anchor.getCol2(); column++) {
        widthPixels += sheet.getColumnWidthInPixels(column);
      }
      double heightPixels = 0;
      for (int row = anchor.getRow1(); row < anchor.getRow2(); row++) {
        heightPixels += sheet.getRow(row).getHeightInPoints() * 96d / 72d;
      }
      assertThat(widthPixels / heightPixels).isBetween(1.31, 1.35);
    }
  }
}
