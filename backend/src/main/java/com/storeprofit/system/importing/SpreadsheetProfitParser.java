package com.storeprofit.system.importing;

import com.storeprofit.system.organization.StoreResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class SpreadsheetProfitParser {
  private static final List<String> FIELD_ORDER = List.of(
      "sales", "refund", "discount", "material", "packaging", "loss", "costOther",
      "rent", "labor", "utility", "property", "commission", "promo", "repair", "equip", "expOther"
  );
  private static final Map<String, List<String>> FIELD_ALIASES = Map.ofEntries(
      Map.entry("sales", List.of("营业总收入", "营业收入", "营业额", "收入合计", "总收入", "流水")),
      Map.entry("refund", List.of("退款金额", "退款", "售后退款", "退款合计")),
      Map.entry("discount", List.of("优惠金额", "优惠", "折扣", "活动优惠")),
      Map.entry("material", List.of("原材料成本", "原材料", "原料", "物料", "采购", "订货")),
      Map.entry("packaging", List.of("包材成本", "包材", "包装材料")),
      Map.entry("loss", List.of("损耗成本", "损耗", "报损")),
      Map.entry("costOther", List.of("其他成本", "成本其他")),
      Map.entry("rent", List.of("房租", "租金")),
      Map.entry("labor", List.of("人工工资", "员工工资", "人工", "工资", "社保")),
      Map.entry("utility", List.of("水电费", "水电", "电费", "水费")),
      Map.entry("property", List.of("物业费", "物业")),
      Map.entry("commission", List.of("平台佣金", "平台手续费", "手续费", "佣金", "平台费")),
      Map.entry("promo", List.of("推广费", "推广", "营销", "广告")),
      Map.entry("repair", List.of("维修费", "维修")),
      Map.entry("equip", List.of("设备费", "设备")),
      Map.entry("expOther", List.of("其他费用", "费用其他", "报销", "杂费"))
  );
  private static final Pattern MONTH_PATTERN = Pattern.compile("(20\\d{2})\\s*[-/.年]?\\s*(1[0-2]|0?[1-9])\\s*(?:月)?(?!\\d)");
  private static final Pattern MONEY_PATTERN = Pattern.compile("-?\\(?\\s*(?:￥|¥|RMB)?\\s*\\d[\\d,，]*(?:\\.\\d+)?\\s*(?:万)?\\s*\\)?");

  public List<ProfitImportRow> parse(MultipartFile file, ProfitImportSourceType sourceType, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) throws IOException {
    if (sourceType == ProfitImportSourceType.CSV || isCsv(file.getOriginalFilename())) {
      return parseCsvText(readText(file), stores, defaultStoreId, defaultMonth);
    }
    try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
      return parseWorkbook(workbook, stores, defaultStoreId, defaultMonth);
    }
  }

  List<ProfitImportRow> parseCsvText(String csv, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) {
    List<List<String>> rows = parseCsv(csv);
    return parseGrids(List.of(new SheetGrid("CSV", rows)), stores, defaultStoreId, defaultMonth);
  }

  private List<ProfitImportRow> parseWorkbook(Workbook workbook, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) {
    DataFormatter formatter = new DataFormatter(Locale.CHINA);
    List<SheetGrid> grids = new ArrayList<>();
    for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
      Sheet sheet = workbook.getSheetAt(s);
      List<List<String>> rows = new ArrayList<>();
      int last = Math.min(sheet.getLastRowNum(), 1000);
      for (int r = 0; r <= last; r++) {
        Row row = sheet.getRow(r);
        if (row == null) {
          rows.add(List.of());
          continue;
        }
        int lastCell = Math.min(Math.max(row.getLastCellNum(), 0), 120);
        List<String> cells = new ArrayList<>();
        for (int c = 0; c < lastCell; c++) {
          Cell cell = row.getCell(c);
          cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        rows.add(cells);
      }
      grids.add(new SheetGrid(sheet.getSheetName(), rows));
    }
    return parseGrids(grids, stores, defaultStoreId, defaultMonth);
  }

  private List<ProfitImportRow> parseGrids(List<SheetGrid> grids, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) {
    List<ProfitImportRow> out = new ArrayList<>();
    for (SheetGrid grid : grids) {
      out.addAll(parseWideTables(grid, stores, defaultStoreId, defaultMonth));
    }
    if (out.isEmpty()) {
      for (SheetGrid grid : grids) {
        parseVertical(grid, stores, defaultStoreId, defaultMonth).ifPresent(out::add);
      }
    }
    return out;
  }

  private List<ProfitImportRow> parseWideTables(SheetGrid grid, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) {
    List<ProfitImportRow> rows = new ArrayList<>();
    for (int r = 0; r < grid.rows.size(); r++) {
      Header header = detectHeader(grid.rows.get(r));
      if (header.fields.size() < 2) {
        continue;
      }
      for (int i = r + 1; i < grid.rows.size(); i++) {
        List<String> cells = grid.rows.get(i);
        if (isBlankRow(cells)) {
          if (!rows.isEmpty()) {
            break;
          }
          continue;
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : header.fields.entrySet()) {
          String raw = cell(cells, e.getKey());
          parseMoney(raw).ifPresent(v -> values.put(e.getValue(), normalizeAmount(e.getValue(), v)));
        }
        if (values.isEmpty()) {
          continue;
        }
        String text = String.join(" ", cells);
        StoreResponse store = matchStore(header.storeColumn >= 0 ? cell(cells, header.storeColumn) : text, stores, defaultStoreId).orElse(null);
        String month = normalizeMonth(header.monthColumn >= 0 ? cell(cells, header.monthColumn) : text, defaultMonth);
        rows.add(row("row_" + grid.name + "_" + (i + 1), store, month, values, grid.name + " 第" + (i + 1) + "行"));
      }
    }
    return rows;
  }

  private Optional<ProfitImportRow> parseVertical(SheetGrid grid, List<StoreResponse> stores, String defaultStoreId, String defaultMonth) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    StringBuilder allText = new StringBuilder();
    for (int r = 0; r < grid.rows.size(); r++) {
      List<String> row = grid.rows.get(r);
      allText.append(' ').append(String.join(" ", row));
      for (int c = 0; c < row.size(); c++) {
        String field = matchField(row.get(c));
        if (field == null || values.containsKey(field)) {
          continue;
        }
        Optional<BigDecimal> money = moneyNear(row, c);
        money.ifPresent(v -> values.put(field, normalizeAmount(field, v)));
      }
    }
    if (values.isEmpty()) {
      return Optional.empty();
    }
    StoreResponse store = matchStore(allText.toString(), stores, defaultStoreId).orElse(null);
    String month = normalizeMonth(allText.toString(), defaultMonth);
    return Optional.of(row("row_" + grid.name + "_summary", store, month, values, grid.name + " 汇总"));
  }

  private ProfitImportRow row(String rowId, StoreResponse store, String month, Map<String, BigDecimal> values, String source) {
    List<String> warnings = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    if (store == null) {
      errors.add("未匹配到门店，请在预览中选择门店");
    }
    if (month == null || month.isBlank()) {
      errors.add("未识别到月份，请在预览中选择月份");
    }
    for (String field : values.keySet()) {
      if (!FIELD_ORDER.contains(field)) {
        warnings.add("忽略未知字段：" + field);
      }
    }
    if (!values.containsKey("sales")) {
      warnings.add("未识别到营业总收入");
    }
    BigDecimal confidence = errors.isEmpty() ? (warnings.isEmpty() ? new BigDecimal("0.92") : new BigDecimal("0.78")) : new BigDecimal("0.45");
    return new ProfitImportRow(
        rowId.replaceAll("[^A-Za-z0-9_\\-]", "_"),
        store == null ? "" : store.id(),
        store == null ? "" : store.name(),
        month == null ? "" : month,
        confidence,
        ordered(values),
        warnings,
        errors,
        false,
        errors.isEmpty() ? "READY" : "ERROR"
    );
  }

  private Header detectHeader(List<String> cells) {
    Header header = new Header();
    for (int i = 0; i < cells.size(); i++) {
      String raw = cells.get(i);
      String normalized = normalize(raw);
      if (normalized.contains("门店") || normalized.contains("店铺") || normalized.contains("店名")) {
        header.storeColumn = i;
        continue;
      }
      if (normalized.contains("月份") || normalized.contains("年月") || normalized.contains("日期")) {
        header.monthColumn = i;
        continue;
      }
      String field = matchField(raw);
      if (field != null) {
        header.fields.put(i, field);
      }
    }
    return header;
  }

  private String matchField(String raw) {
    String n = normalize(raw);
    if (n.isBlank() || n.contains("成本合计") || n.contains("费用合计") || n.contains("净利润") || n.contains("毛利润") || n.contains("实收收入")) {
      return null;
    }
    for (String field : FIELD_ORDER) {
      for (String alias : FIELD_ALIASES.getOrDefault(field, List.of())) {
        if (n.contains(normalize(alias))) {
          return field;
        }
      }
    }
    return null;
  }

  private Optional<StoreResponse> matchStore(String text, List<StoreResponse> stores, String defaultStoreId) {
    if (defaultStoreId != null && !defaultStoreId.isBlank()) {
      return stores.stream().filter(s -> defaultStoreId.equals(s.id())).findFirst();
    }
    String haystack = normalize(text);
    return stores.stream()
        .filter(store -> haystack.contains(normalize(store.id()))
            || haystack.contains(normalize(store.code()))
            || haystack.contains(normalize(store.name())))
        .findFirst();
  }

  private String normalizeMonth(String text, String defaultMonth) {
    Matcher matcher = MONTH_PATTERN.matcher(text == null ? "" : text);
    if (matcher.find()) {
      int year = Integer.parseInt(matcher.group(1));
      int month = Integer.parseInt(matcher.group(2));
      return YearMonth.of(year, month).toString();
    }
    if (defaultMonth != null && !defaultMonth.isBlank()) {
      try {
        return YearMonth.parse(defaultMonth.trim()).toString();
      } catch (Exception ignored) {
        // Continue to parse source text.
      }
    }
    return "";
  }

  private Optional<BigDecimal> moneyNear(List<String> row, int index) {
    Optional<BigDecimal> same = parseMoney(row.get(index));
    if (same.isPresent()) {
      return same;
    }
    for (int i = index + 1; i < row.size(); i++) {
      Optional<BigDecimal> value = parseMoney(row.get(i));
      if (value.isPresent()) {
        return value;
      }
    }
    for (int i = index - 1; i >= 0; i--) {
      Optional<BigDecimal> value = parseMoney(row.get(i));
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private Optional<BigDecimal> parseMoney(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = MONEY_PATTERN.matcher(raw);
    if (!matcher.find()) {
      return Optional.empty();
    }
    String token = matcher.group();
    boolean negative = token.contains("-") || (token.contains("(") && token.contains(")"));
    boolean wan = token.contains("万");
    String cleaned = token.replaceAll("[￥¥RMB,，\\s()（）万]", "");
    cleaned = cleaned.replace("-", "");
    if (cleaned.isBlank()) {
      return Optional.empty();
    }
    BigDecimal value = new BigDecimal(cleaned);
    if (wan) {
      value = value.multiply(new BigDecimal("10000"));
    }
    if (negative) {
      value = value.negate();
    }
    return Optional.of(value.setScale(2, RoundingMode.HALF_UP));
  }

  private BigDecimal normalizeAmount(String field, BigDecimal value) {
    if (value.compareTo(BigDecimal.ZERO) < 0 && List.of("refund", "discount").contains(field)) {
      return value.abs();
    }
    return value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
  }

  private Map<String, BigDecimal> ordered(Map<String, BigDecimal> values) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (String field : FIELD_ORDER) {
      if (values.containsKey(field)) {
        out.put(field, values.get(field));
      }
    }
    return out;
  }

  private String readText(MultipartFile file) throws IOException {
    byte[] bytes = file.getBytes();
    String utf8 = new String(bytes, StandardCharsets.UTF_8);
    if (utf8.indexOf('\uFFFD') >= 0) {
      return new String(bytes, Charset.forName("GBK"));
    }
    return utf8;
  }

  private boolean isCsv(String name) {
    return name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv");
  }

  private List<List<String>> parseCsv(String csv) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quote = false;
    for (int i = 0; i < csv.length(); i++) {
      char ch = csv.charAt(i);
      if (ch == '"') {
        if (quote && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quote = !quote;
        }
      } else if (ch == ',' && !quote) {
        row.add(cell.toString().trim());
        cell.setLength(0);
      } else if ((ch == '\n' || ch == '\r') && !quote) {
        if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
          i++;
        }
        row.add(cell.toString().trim());
        rows.add(row);
        row = new ArrayList<>();
        cell.setLength(0);
      } else {
        cell.append(ch);
      }
    }
    row.add(cell.toString().trim());
    rows.add(row);
    return rows;
  }

  private boolean isBlankRow(List<String> row) {
    return row.stream().allMatch(cell -> cell == null || cell.isBlank());
  }

  private String cell(List<String> row, int index) {
    return index >= 0 && index < row.size() ? row.get(index) : "";
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT)
        .replaceAll("[\\s　:：;；,，.。()（）\\[\\]【】_\\-—/\\\\]", "");
  }

  private record SheetGrid(String name, List<List<String>> rows) {
  }

  private static class Header {
    private int storeColumn = -1;
    private int monthColumn = -1;
    private final Map<Integer, String> fields = new LinkedHashMap<>();
  }
}
