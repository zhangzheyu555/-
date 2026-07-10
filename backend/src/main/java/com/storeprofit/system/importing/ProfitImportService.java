package com.storeprofit.system.importing;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryRequest;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfitImportService {
  private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;

  private final SpreadsheetProfitParser spreadsheetProfitParser;
  private final OrganizationRepository organizationRepository;
  private final FinanceRepository financeRepository;
  private final FinanceService financeService;
  private final AccessControlService accessControl;

  public ProfitImportService(
      SpreadsheetProfitParser spreadsheetProfitParser,
      OrganizationRepository organizationRepository,
      FinanceRepository financeRepository,
      FinanceService financeService,
      AccessControlService accessControl
  ) {
    this.spreadsheetProfitParser = spreadsheetProfitParser;
    this.organizationRepository = organizationRepository;
    this.financeRepository = financeRepository;
    this.financeService = financeService;
    this.accessControl = accessControl;
  }

  public ProfitImportRecognizeResponse recognize(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType requestedSourceType,
      String storeId,
      String month
  ) {
    accessControl.requireFinanceWrite(user);
    if (storeId != null && !storeId.isBlank()) {
      accessControl.requireStoreAccess(user, storeId, "预览经营数据导入");
    }
    validateFile(file);
    ProfitImportSourceType sourceType = detectSourceType(file, requestedSourceType);
    if (sourceType == ProfitImportSourceType.SCREENSHOT) {
      return new ProfitImportRecognizeResponse(
          importId(),
          ProfitImportSourceType.SCREENSHOT,
          "ERROR",
          List.of(),
          List.of("截图识别后端视觉能力尚未配置。请先使用 Excel/CSV 导入，或手工录入后保存。")
      );
    }

    List<StoreResponse> stores = scopedStores(user);
    try {
      List<ProfitImportRow> rows = spreadsheetProfitParser.parse(file, sourceType, stores, storeId, month)
          .stream()
          .map(row -> enrich(user, row))
          .toList();
      if (rows.isEmpty()) {
        return new ProfitImportRecognizeResponse(
            importId(),
            sourceType,
            "ERROR",
            List.of(),
            List.of("没有识别到可导入的利润数据。请确认表格包含门店、月份、营业收入或成本费用字段。")
        );
      }
      String status = rows.stream().anyMatch(row -> "ERROR".equals(row.status())) ? "PARTIAL" : "READY";
      return new ProfitImportRecognizeResponse(importId(), sourceType, status, rows, List.of());
    } catch (IOException ex) {
      throw new BusinessException("IMPORT_READ_FAILED", "导入文件读取失败：" + ex.getMessage(), HttpStatus.BAD_REQUEST);
    } catch (RuntimeException ex) {
      throw new BusinessException("IMPORT_PARSE_FAILED", "导入文件解析失败：" + ex.getMessage(), HttpStatus.BAD_REQUEST);
    }
  }

  @Transactional
  public ProfitImportCommitResponse commit(AuthUser user, ProfitImportCommitRequest request) {
    accessControl.requireFinanceWrite(user);
    if (request == null || request.rows() == null || request.rows().isEmpty()) {
      throw new BusinessException("IMPORT_EMPTY", "请选择至少一条识别结果后再确认导入", HttpStatus.BAD_REQUEST);
    }
    int saved = 0;
    int skipped = 0;
    List<ProfitImportRow> rows = new ArrayList<>();
    for (ProfitImportCommitRequest.Row row : request.rows()) {
      ProfitImportRow result = commitRow(user, row);
      rows.add(result);
      if ("SAVED".equals(result.status())) {
        saved++;
      } else {
        skipped++;
      }
    }
    return new ProfitImportCommitResponse(saved, skipped, rows);
  }

  private ProfitImportRow commitRow(AuthUser user, ProfitImportCommitRequest.Row row) {
    List<String> errors = new ArrayList<>();
    String storeId = row == null ? "" : trim(row.storeId());
    String month = row == null ? "" : trim(row.month());
    Map<String, BigDecimal> values = row == null || row.values() == null ? Map.of() : row.values();

    if (storeId.isBlank()) {
      errors.add("门店不能为空");
    } else if (!financeRepository.storeExists(user.tenantId(), storeId)) {
      errors.add("门店不存在或不属于当前企业");
    }

    String normalizedMonth = "";
    if (month.isBlank()) {
      errors.add("月份不能为空");
    } else {
      try {
        normalizedMonth = YearMonth.parse(month).toString();
      } catch (Exception ex) {
        errors.add("月份格式必须为 YYYY-MM");
      }
    }

    if (values.isEmpty()) {
      errors.add("没有可保存的金额字段");
    }

    String rowId = row == null || row.rowId() == null || row.rowId().isBlank() ? UUID.randomUUID().toString() : row.rowId();
    if (!errors.isEmpty()) {
      return new ProfitImportRow(rowId, storeId, storeName(user, storeId), normalizedMonth, new BigDecimal("0.20"), ordered(values), List.of(), errors, false, "ERROR");
    }

    boolean existing = financeRepository.entryExists(user.tenantId(), storeId, normalizedMonth);
    if (existing && !row.overwrite()) {
      return new ProfitImportRow(
          rowId,
          storeId,
          storeName(user, storeId),
          normalizedMonth,
          new BigDecimal("0.60"),
          ordered(values),
          List.of("该门店月份已有数据，勾选覆盖后才能写入"),
          List.of(),
          true,
          "CONFLICT"
      );
    }

    financeService.save(user, toProfitEntryRequest(storeId, normalizedMonth, values, row.note()));
    return new ProfitImportRow(
        rowId,
        storeId,
        storeName(user, storeId),
        normalizedMonth,
        BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP),
        ordered(values),
        List.of(),
        List.of(),
        true,
        "SAVED"
    );
  }

  private ProfitEntryRequest toProfitEntryRequest(String storeId, String month, Map<String, BigDecimal> values, String note) {
    return new ProfitEntryRequest(
        storeId,
        month,
        value(values, "sales"),
        value(values, "refund"),
        value(values, "discount"),
        value(values, "material"),
        value(values, "packaging"),
        value(values, "loss"),
        value(values, "costOther"),
        value(values, "rent"),
        value(values, "labor"),
        value(values, "utility"),
        value(values, "property"),
        value(values, "commission"),
        value(values, "promo"),
        value(values, "repair"),
        value(values, "equip"),
        value(values, "expOther"),
        note == null || note.isBlank() ? "数据导入" : note.trim()
    );
  }

  private ProfitImportRow enrich(AuthUser user, ProfitImportRow row) {
    if (!row.errors().isEmpty()) {
      return row;
    }
    boolean existing = financeRepository.entryExists(user.tenantId(), row.storeId(), row.month());
    if (!existing) {
      return row;
    }
    List<String> warnings = new ArrayList<>(row.warnings());
    warnings.add("该门店月份已有数据，提交时需要确认覆盖");
    return new ProfitImportRow(
        row.rowId(),
        row.storeId(),
        row.storeName(),
        row.month(),
        row.confidence(),
        row.values(),
        warnings,
        row.errors(),
        true,
        "CONFLICT"
    );
  }

  private List<StoreResponse> scopedStores(AuthUser user) {
    List<StoreResponse> stores = organizationRepository.stores(user.tenantId());
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null && !user.storeId().isBlank()) {
      return stores.stream().filter(store -> user.storeId().equals(store.id())).toList();
    }
    return stores;
  }

  private String storeName(AuthUser user, String storeId) {
    if (storeId == null || storeId.isBlank()) {
      return "";
    }
    return organizationRepository.stores(user.tenantId()).stream()
        .filter(store -> storeId.equals(store.id()))
        .map(StoreResponse::name)
        .findFirst()
        .orElse(storeId);
  }

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException("IMPORT_FILE_EMPTY", "请先选择要导入的文件", HttpStatus.BAD_REQUEST);
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw new BusinessException("IMPORT_FILE_TOO_LARGE", "导入文件不能超过 10MB", HttpStatus.BAD_REQUEST);
    }
  }

  private ProfitImportSourceType detectSourceType(MultipartFile file, ProfitImportSourceType requestedSourceType) {
    ProfitImportSourceType sourceType = requestedSourceType == null ? ProfitImportSourceType.AUTO : requestedSourceType;
    if (sourceType != ProfitImportSourceType.AUTO) {
      return sourceType;
    }
    String name = lower(file.getOriginalFilename());
    String contentType = lower(file.getContentType());
    if (contentType.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")) {
      return ProfitImportSourceType.SCREENSHOT;
    }
    if (name.endsWith(".csv")) {
      return ProfitImportSourceType.CSV;
    }
    return ProfitImportSourceType.EXCEL;
  }

  private Map<String, BigDecimal> ordered(Map<String, BigDecimal> values) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (String field : List.of(
        "sales", "refund", "discount", "material", "packaging", "loss", "costOther",
        "rent", "labor", "utility", "property", "commission", "promo", "repair", "equip", "expOther"
    )) {
      out.put(field, value(values, field));
    }
    return out;
  }

  private BigDecimal value(Map<String, BigDecimal> values, String key) {
    BigDecimal value = values.get(key);
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private String importId() {
    return "profit_" + UUID.randomUUID();
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
