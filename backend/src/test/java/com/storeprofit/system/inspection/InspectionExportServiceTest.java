package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InspectionExportServiceTest {
  private static final AuthUser BOSS = new AuthUser(
      1L, 1L, "default", "boss", "", "老板", "BOSS", null, true);

  @Test
  void fillsTheControlledTemplateAndContinuesPhotosAfterTheTwelfthSlot() throws Exception {
    InspectionService inspectionService = mock(InspectionService.class);
    StorageService storageService = mock(StorageService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    InspectionRecordResponse record = reportRecord(List.of(
        1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L));
    when(inspectionService.prepareForExport(BOSS, "INSP-1")).thenReturn(record);
    byte[] image = png();
    for (long id = 1; id <= 13; id++) {
      when(storageService.inspectionAttachment(BOSS, id, "INSP-1"))
          .thenReturn(Optional.of(attachment(id, "现场照片-" + id + ".png", image)));
    }
    InspectionExportService service = new InspectionExportService(
        inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

    InspectionExportFile file = service.export(BOSS, "INSP-1");

    assertThat(file.filename()).isEqualTo("巡检报告_万达二店_20260623_INSP-1.xlsx");
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
      // The source workbook has three sheets; the photo continuation is appended without
      // discarding its retained template sheets.
      assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
      XSSFSheet report = (XSSFSheet) workbook.getSheetAt(0);
      XSSFSheet continuation = (XSSFSheet) workbook.getSheet("现场照片-2");
      assertThat(report.getNumMergedRegions()).isEqualTo(56);
      assertThat(report.getPrintSetup().getPaperSize()).isEqualTo(PrintSetup.A4_PAPERSIZE);
      assertThat(report.getPrintSetup().getLandscape()).isFalse();
      assertThat(cellText(report, 14, 7)).isEqualTo("总分：196 / 200");
      assertThat(cellText(report, 13, 7)).isEqualTo("巡检结果：合格");
      assertThat(cellText(report, 14, 7)).doesNotContain("195");
      assertThat(report.getDrawingPatriarch().getShapes()).hasSize(12);
      assertThat(continuation.getDrawingPatriarch().getShapes()).hasSize(1);
      assertThat(cellText(continuation, 0, 0)).isEqualTo("现场照片续页");
      assertThat(continuation.getMargin(Sheet.LeftMargin)).isEqualTo(report.getMargin(Sheet.LeftMargin));
      assertThat(continuation.getMargin(Sheet.TopMargin)).isEqualTo(report.getMargin(Sheet.TopMargin));
      assertThat(workbook.getPrintArea(workbook.getSheetIndex(continuation)))
          .contains("'现场照片-2'!A1:Q135");
    }
    ArgumentCaptor<AuditLogRequest> log = ArgumentCaptor.forClass(AuditLogRequest.class);
    verify(auditRepository).writeLog(any(), log.capture());
    assertThat(log.getValue().action()).isEqualTo("导出巡检报告");
    assertThat(log.getValue().reason()).isEqualTo("基于受控巡检模板生成Excel；现场照片数：13");
  }

  @Test
  void marksNoPhotosWithoutRebuildingTheTemplate() throws Exception {
    InspectionService inspectionService = mock(InspectionService.class);
    StorageService storageService = mock(StorageService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    when(inspectionService.prepareForExport(BOSS, "INSP-NO-PHOTO"))
        .thenReturn(reportRecord(List.of()));
    InspectionExportService service = new InspectionExportService(
        inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

    InspectionExportFile file = service.export(BOSS, "INSP-NO-PHOTO");

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
      XSSFSheet report = (XSSFSheet) workbook.getSheetAt(0);
      assertThat(report.getNumMergedRegions()).isEqualTo(56);
      assertThat(cellText(report, 15, 0)).isEqualTo("无现场照片");
      assertThat(report.getDrawingPatriarch().getShapes()).isEmpty();
    }
  }

  @Test
  void keepsTheOriginalPhotoPageForOneAndTwelvePhotos() throws Exception {
    for (int photoCount : List.of(1, 12)) {
      InspectionService inspectionService = mock(InspectionService.class);
      StorageService storageService = mock(StorageService.class);
      AuditRepository auditRepository = mock(AuditRepository.class);
      List<Long> attachmentIds = java.util.stream.LongStream.rangeClosed(1, photoCount)
          .boxed().toList();
      when(inspectionService.prepareForExport(BOSS, "INSP-" + photoCount))
          .thenReturn(reportRecord(attachmentIds));
      byte[] image = png();
      for (Long attachmentId : attachmentIds) {
        when(storageService.inspectionAttachment(BOSS, attachmentId, "INSP-1"))
            .thenReturn(Optional.of(attachment(attachmentId, "现场照片-" + attachmentId + ".png", image)));
      }
      InspectionExportService service = new InspectionExportService(
          inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

      InspectionExportFile file = service.export(BOSS, "INSP-" + photoCount);

      try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
        XSSFSheet report = (XSSFSheet) workbook.getSheetAt(0);
        assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
        assertThat(report.getDrawingPatriarch().getShapes()).hasSize(photoCount);
      }
    }
  }

  @Test
  void returnsTheSpecificPhotoErrorInsteadOfCreatingAPartialWorkbook() {
    InspectionService inspectionService = mock(InspectionService.class);
    StorageService storageService = mock(StorageService.class);
    AuditRepository auditRepository = mock(AuditRepository.class);
    when(inspectionService.prepareForExport(BOSS, "INSP-BAD-PHOTO"))
        .thenReturn(reportRecord(List.of(99L)));
    when(storageService.inspectionAttachment(BOSS, 99L, "INSP-1")).thenReturn(Optional.of(
        attachment(99L, "损坏现场照片.webp", new byte[] {1, 2, 3, 4})));
    InspectionExportService service = new InspectionExportService(
        inspectionService, storageService, new InspectionImageNormalizer(), auditRepository);

    assertThatThrownBy(() -> service.export(BOSS, "INSP-BAD-PHOTO"))
        .isInstanceOf(BusinessException.class)
        .satisfies(exception -> {
          BusinessException business = (BusinessException) exception;
          assertThat(business.getCode()).isEqualTo("INSPECTION_EXPORT_PHOTO_INVALID");
          assertThat(business.getMessage()).contains("损坏现场照片.webp");
        });
  }

  private InspectionRecordResponse reportRecord(List<Long> photoIds) {
    InspectionItemResultResponse item = new InspectionItemResultResponse(
        1L, 1L, "卫生", "H-01", "地面卫生", "地面应保持整洁", "现场检查",
        bd("4"), bd("0"), bd("4"), true, "YELLOW", false, "发现纸屑",
        photoIds, "店长", LocalDate.of(2026, 6, 30), "待整改", null,
        List.of(), List.of(), 1);
    InspectionResultPresentation presentation = InspectionResultPolicy.present(
        bd("200"), bd("196"), bd("37"), bd("59"), bd("100"), true, "PASSED", "[]",
        "2025.11.06-R1", null);
    return new InspectionRecordResponse(
        "INSP-1", "store-1", "WD-02", "万达二店", 1L, "茹菓", "2026-06-23",
        "夏督导", "茹菓", bd("200"), bd("196"), true, "[]", "[]", "[]", "现场复核通过",
        41L, "2025.11.06-R1", bd("37"), bd("59"), bd("100"), "PASSED", List.of(item),
        presentation);
  }

  private StorageService.InspectionAttachmentContent attachment(long id, String fileName, byte[] content) {
    return new StorageService.InspectionAttachmentContent(
        id, "store-1", "INSPECTION_RECORD", "INSP-1", fileName, "image/png", content.length,
        content, 1L, LocalDateTime.of(2026, 6, 23, 10, 0));
  }

  private byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(16, 10, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.ORANGE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value).setScale(2);
  }

  private String cellText(XSSFSheet sheet, int rowIndex, int columnIndex) {
    Row row = sheet.getRow(rowIndex);
    return row == null || row.getCell(columnIndex) == null ? "" : row.getCell(columnIndex).getStringCellValue();
  }
}
