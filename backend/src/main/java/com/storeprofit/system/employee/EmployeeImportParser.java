package com.storeprofit.system.employee;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 解析《门店人员信息.xlsx》：第 2 行表头，列序固定——
 * 门店 | 姓名 | 职位 | 入职时间 | 转正时间 | 训练员转正 | 领班 | 店长转正 | 生日 | 联系方式 |
 * 身份证号码 | 健康证办理日期 | 健康证到期日期 | 合同签署时间。
 * 门店列为竖排合并样式（只有每店第一行有值），逐行下沿。
 * 日期兼容「2022.5.18」「2022.6」（补 01 日）与 Excel 日期单元格；生日保留「月.日」原文。
 */
final class EmployeeImportParser {
  record ImportRow(int rowNum, String storeAlias, EmployeeUpsertRequest request, List<String> problems) {
  }

  private static final DataFormatter FORMATTER = new DataFormatter();

  private EmployeeImportParser() {
  }

  static List<ImportRow> parse(byte[] bytes) throws IOException {
    List<ImportRow> rows = new ArrayList<>();
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      Sheet sheet = workbook.getSheetAt(0);
      String currentStore = null;
      for (int i = 2; i <= sheet.getLastRowNum(); i++) { // 0 基：跳过标题行与表头行
        Row row = sheet.getRow(i);
        if (row == null) {
          continue;
        }
        String store = text(row, 0);
        if (!store.isBlank()) {
          currentStore = store;
        }
        String name = text(row, 1);
        if (name.isBlank()) {
          continue;
        }
        List<String> problems = new ArrayList<>();
        String phone = text(row, 9).replaceAll("\\s", "");
        if (!phone.isBlank() && !phone.matches("1\\d{10}")) {
          problems.add("手机号格式异常:" + phone);
        }
        String idCard = text(row, 10).replaceAll("\\s", "");
        if (!idCard.isBlank() && idCard.length() != 18) {
          problems.add("身份证不完整(" + idCard.length() + "位)");
        }
        String position = text(row, 2);
        EmployeeUpsertRequest request = new EmployeeUpsertRequest(
            null, // storeId 由门店映射阶段补
            name,
            phone,
            position,
            position.contains("兼职") ? "兼职" : "全职",
            "在职",
            date(row, 3, problems, "入职时间"),
            birthday(row, 8),
            idCard,
            date(row, 11, problems, "健康证办理日期"),
            date(row, 12, problems, "健康证到期日期"),
            text(row, 13),
            date(row, 4, problems, "转正时间"),
            date(row, 5, problems, "训练员转正"),
            date(row, 6, problems, "领班"),
            date(row, 7, problems, "店长转正"),
            null
        );
        rows.add(new ImportRow(i + 1, currentStore == null ? "" : currentStore, request, problems));
      }
    }
    return rows;
  }

  private static String text(Row row, int col) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      return "";
    }
    return FORMATTER.formatCellValue(cell).trim();
  }

  /** 「2022.5.18」→2022-05-18；「2022.6」→2022-06-01；日期单元格直接取值；解析不了记入报告、存空。 */
  private static String date(Row row, int col, List<String> problems, String label) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    String raw = FORMATTER.formatCellValue(cell).trim();
    if (raw.isBlank()) {
      return null;
    }
    String[] parts = raw.split("[.\\-/年月日]+");
    try {
      if (parts.length >= 3) {
        return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))
            .format(DateTimeFormatter.ISO_LOCAL_DATE);
      }
      if (parts.length == 2) {
        return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1)
            .format(DateTimeFormatter.ISO_LOCAL_DATE);
      }
    } catch (RuntimeException ignored) {
      // 落到统一的问题记录
    }
    problems.add(label + "无法解析:" + raw);
    return null;
  }

  /** 生日保留「月.日」样式原文；日期单元格转成同样式。 */
  private static String birthday(Row row, int col) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
      LocalDate value = cell.getLocalDateTimeCellValue().toLocalDate();
      return value.getMonthValue() + "." + value.getDayOfMonth();
    }
    String raw = FORMATTER.formatCellValue(cell).trim();
    return raw.isBlank() ? null : raw;
  }
}
