package com.storeprofit.system.knowledgebase;

import com.storeprofit.system.common.BusinessException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Parses only the locally uploaded source file. No source content leaves the application process. */
@Component
public class KnowledgeDocumentParser {
  private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
  private static final int MAX_PARSED_CHARS = 500_000;
  private static final int MAX_SHEETS = 100;
  private static final int MAX_ROWS_PER_SHEET = 20_000;
  private static final int MAX_COLUMNS_PER_ROW = 100;

  public ParsedDocument parse(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_FILE_REQUIRED", "请选择 Word、Excel、CSV 或文本资料");
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_FILE_TOO_LARGE", "单个知识库资料不能超过20MB");
    }
    String fileName = safeFileName(file.getOriginalFilename());
    String extension = extension(fileName);
    byte[] source;
    try {
      source = file.getBytes();
    } catch (IOException ex) {
      throw new BusinessException("KNOWLEDGE_BASE_FILE_READ_FAILED", "资料读取失败，请重新上传", HttpStatus.BAD_REQUEST);
    }
    if (source.length == 0) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_FILE_REQUIRED", "上传资料不能为空");
    }
    List<ExtractedSection> sections = switch (extension) {
      case "docx" -> parseDocx(source);
      case "xlsx", "xls" -> parseWorkbook(source);
      case "csv" -> parseDelimited(source, "CSV");
      case "txt" -> parseText(source);
      default -> throw KnowledgeBaseErrors.badRequest(
          "KNOWLEDGE_BASE_FILE_TYPE_UNSUPPORTED", "仅支持 .docx、.xlsx、.xls、.csv 和 .txt 格式的资料");
    };
    validateExtracted(sections);
    String contentType = file.getContentType() == null || file.getContentType().isBlank()
        ? contentTypeFor(extension) : file.getContentType().trim();
    return new ParsedDocument(fileName, contentType, source, List.copyOf(sections));
  }

  private List<ExtractedSection> parseDocx(byte[] source) {
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
      ArrayList<ExtractedSection> sections = new ArrayList<>();
      StringBuilder paragraphs = new StringBuilder();
      for (XWPFParagraph paragraph : document.getParagraphs()) appendLine(paragraphs, paragraph.getText());
      addSection(sections, "正文", paragraphs);
      int tableNumber = 0;
      for (XWPFTable table : document.getTables()) {
        tableNumber++;
        StringBuilder tableText = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
          ArrayList<String> cells = new ArrayList<>();
          for (XWPFTableCell cell : row.getTableCells()) cells.add(normalizeCell(cell.getText()));
          appendLine(tableText, String.join(" | ", cells));
        }
        addSection(sections, "表格 " + tableNumber, tableText);
      }
      return sections;
    } catch (IOException | RuntimeException ex) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_DOCUMENT_PARSE_FAILED", "Word 文档无法解析，请确认文件未损坏并另存为 .docx 后重试");
    }
  }

  private List<ExtractedSection> parseWorkbook(byte[] source) {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(source))) {
      if (workbook.getNumberOfSheets() > MAX_SHEETS) {
        throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TOO_MANY_SHEETS", "Excel 工作表数量不能超过100个");
      }
      DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      ArrayList<ExtractedSection> sections = new ArrayList<>();
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        if (sheet.getLastRowNum() + 1 > MAX_ROWS_PER_SHEET) {
          throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TOO_MANY_ROWS", "单个工作表不能超过20000行，请拆分后上传");
        }
        StringBuilder text = new StringBuilder();
        List<String> headers = List.of();
        for (Row row : sheet) {
          List<String> values = rowValues(row, formatter, evaluator);
          if (values.stream().allMatch(String::isBlank)) continue;
          if (headers.isEmpty()) {
            headers = values;
            appendLine(text, "表头：" + String.join(" | ", headers));
            continue;
          }
          ArrayList<String> cells = new ArrayList<>();
          for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value.isBlank()) continue;
            String header = index < headers.size() && !headers.get(index).isBlank() ? headers.get(index) : "第" + (index + 1) + "列";
            cells.add(header + "：" + value);
          }
          if (!cells.isEmpty()) appendLine(text, String.join("；", cells));
        }
        addSection(sections, "工作表：" + safeSheetName(sheet.getSheetName(), sheetIndex), text);
      }
      return sections;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_DOCUMENT_PARSE_FAILED", "Excel 文件无法解析，请确认文件未损坏后重试");
    }
  }

  private List<String> rowValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    int first = Math.max(0, row.getFirstCellNum());
    int last = Math.min(Math.max(first, row.getLastCellNum()), first + MAX_COLUMNS_PER_ROW);
    ArrayList<String> values = new ArrayList<>();
    for (int index = first; index < last; index++) {
      Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      String value = "";
      if (cell != null) {
        try {
          value = formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ignored) {
          value = formatter.formatCellValue(cell);
        }
      }
      values.add(normalizeCell(value));
    }
    return values;
  }

  private List<ExtractedSection> parseDelimited(byte[] source, String locator) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        new ByteArrayInputStream(stripUtf8Bom(source)), StandardCharsets.UTF_8))) {
      StringBuilder text = new StringBuilder();
      String line;
      int lines = 0;
      while ((line = reader.readLine()) != null) {
        if (++lines > MAX_ROWS_PER_SHEET) {
          throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TOO_MANY_ROWS", "CSV 不能超过20000行，请拆分后上传");
        }
        appendLine(text, line);
      }
      return List.of(new ExtractedSection("工作表：" + locator, text.toString()));
    } catch (IOException ex) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_DOCUMENT_PARSE_FAILED", "CSV 文件无法解析，请确认编码为 UTF-8 后重试");
    }
  }

  private List<ExtractedSection> parseText(byte[] source) {
    String value = new String(stripUtf8Bom(source), StandardCharsets.UTF_8);
    return List.of(new ExtractedSection("正文", value));
  }

  private void validateExtracted(List<ExtractedSection> sections) {
    int total = sections.stream().map(ExtractedSection::text).mapToInt(String::length).sum();
    if (total == 0) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_EMPTY_DOCUMENT", "未从资料中读取到可检索的文字内容");
    }
    if (total > MAX_PARSED_CHARS) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_TEXT_TOO_LARGE", "资料可检索正文不能超过50万字，请拆分后上传");
    }
  }

  private void addSection(List<ExtractedSection> sections, String locator, StringBuilder value) {
    String text = value.toString().trim();
    if (!text.isBlank()) sections.add(new ExtractedSection(locator, text));
  }

  private void appendLine(StringBuilder target, String value) {
    String normalized = normalizeCell(value);
    if (!normalized.isBlank()) target.append(normalized).append('\n');
  }

  private String normalizeCell(String value) {
    return (value == null ? "" : value).replaceAll("\\s+", " ").trim();
  }

  private String extension(String fileName) {
    int index = fileName.lastIndexOf('.');
    return index < 1 || index == fileName.length() - 1 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
  }

  private String safeFileName(String original) {
    String candidate = original == null ? "" : original.replace('\\', '/');
    int slash = candidate.lastIndexOf('/');
    candidate = slash >= 0 ? candidate.substring(slash + 1) : candidate;
    candidate = candidate.replaceAll("[\\r\\n\\u0000]", "").trim();
    if (candidate.isBlank() || candidate.length() > 255) {
      throw KnowledgeBaseErrors.badRequest("KNOWLEDGE_BASE_FILE_NAME_INVALID", "资料文件名不正确");
    }
    return candidate;
  }

  private String safeSheetName(String value, int index) {
    String normalized = value == null ? "" : value.replaceAll("[\\r\\n]", " ").trim();
    return normalized.isBlank() ? "第" + (index + 1) + "个工作表" : normalized;
  }

  private byte[] stripUtf8Bom(byte[] value) {
    if (value.length >= 3 && (value[0] & 0xFF) == 0xEF && (value[1] & 0xFF) == 0xBB && (value[2] & 0xFF) == 0xBF) {
      byte[] stripped = new byte[value.length - 3];
      System.arraycopy(value, 3, stripped, 0, stripped.length);
      return stripped;
    }
    return value;
  }

  private String contentTypeFor(String extension) {
    return switch (extension) {
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "xls" -> "application/vnd.ms-excel";
      case "csv" -> "text/csv";
      default -> "text/plain";
    };
  }

  public record ParsedDocument(String fileName, String contentType, byte[] sourceContent, List<ExtractedSection> sections) {}

  public record ExtractedSection(String locator, String text) {}
}
