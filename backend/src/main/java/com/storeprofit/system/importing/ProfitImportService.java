package com.storeprofit.system.importing;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.finance.ProfitEntryRequest;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfitImportService {
  private static final Logger log = LoggerFactory.getLogger(ProfitImportService.class);
  private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;

  private final SpreadsheetProfitParser spreadsheetProfitParser;
  private final OrganizationRepository organizationRepository;
  private final FinanceRepository financeRepository;
  private final FinanceService financeService;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;

  @Autowired
  public ProfitImportService(
      SpreadsheetProfitParser spreadsheetProfitParser,
      OrganizationRepository organizationRepository,
      FinanceRepository financeRepository,
      FinanceService financeService,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this.spreadsheetProfitParser = spreadsheetProfitParser;
    this.organizationRepository = organizationRepository;
    this.financeRepository = financeRepository;
    this.financeService = financeService;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
  }

  public ProfitImportService(
      SpreadsheetProfitParser spreadsheetProfitParser,
      OrganizationRepository organizationRepository,
      FinanceRepository financeRepository,
      FinanceService financeService,
      AccessControlService accessControl
  ) {
    this(spreadsheetProfitParser, organizationRepository, financeRepository, financeService,
        accessControl, null);
  }

  public ProfitImportRecognizeResponse recognize(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType requestedSourceType,
      String storeId,
      String month
  ) {
    return recognize(user, file, requestedSourceType, storeId, month, true);
  }

  /** Shared authorization boundary for every spreadsheet-recognition and commit entry point. */
  public void requireImportAccess(AuthUser user) {
    requireImportAccess(user, null, null);
  }

  public void requireImportAccess(AuthUser user, String storeId, String month) {
    accessControl.requireFinanceImport(user, storeId, month);
  }

  /**
   * The data-entry drawer is deliberately stricter than the historic bulk-recognize endpoint.
   * In particular, it must retain a parsed row's own store identity so PreviewJobService can
   * reject a multi-store file instead of silently converting it to the current store.
   */
  public ProfitImportRecognizeResponse recognizeForSingleStoreMonthPreview(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType requestedSourceType,
      String storeId,
      String month
  ) {
    return recognize(user, file, requestedSourceType, storeId, month, false);
  }

  private ProfitImportRecognizeResponse recognize(
      AuthUser user,
      MultipartFile file,
      ProfitImportSourceType requestedSourceType,
      String storeId,
      String month,
      boolean applyLegacyStoreManagerLock
  ) {
    requireImportAccess(user, storeId, month);
    BusinessScope businessScope = resolveBusinessScope(user, storeId, "预览经营数据导入");
    String targetStoreId = businessScope.storeId();
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
      long parseStarted = System.nanoTime();
      List<ProfitImportRow> parsedRows = spreadsheetProfitParser.parse(
          file, sourceType, stores, targetStoreId, month);
      long parseMs = (System.nanoTime() - parseStarted) / 1_000_000L;
      long validationStarted = System.nanoTime();
      List<ProfitImportRow> rows = parsedRows
          .stream()
          .map(row -> applyLegacyStoreManagerLock ? lockParsedRow(user, row, businessScope) : row)
          .map(row -> enrich(user, row))
          .toList();
      long validationMs = (System.nanoTime() - validationStarted) / 1_000_000L;
      log.info(
          "Profit import preview parsed: sourceType={} fileBytes={} parsedRows={} parseMs={} databaseValidationMs={}",
          sourceType, file.getSize(), rows.size(), parseMs, validationMs);
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
    } catch (ProfitImportParseException ex) {
      return new ProfitImportRecognizeResponse(
          importId(),
          sourceType,
          "ERROR",
          List.of(),
          List.of(ex.getMessage())
      );
    } catch (IOException ex) {
      throw new BusinessException("IMPORT_READ_FAILED", "导入文件读取失败：" + ex.getMessage(), HttpStatus.BAD_REQUEST);
    } catch (RuntimeException ex) {
      throw new BusinessException("IMPORT_PARSE_FAILED", "导入文件解析失败：" + ex.getMessage(), HttpStatus.BAD_REQUEST);
    }
  }

  @Transactional
  public ProfitImportCommitResponse commit(AuthUser user, ProfitImportCommitRequest request) {
    requireImportAccess(user);
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
    BusinessScope businessScope = resolveBusinessScope(
        user, row == null ? null : row.storeId(), "导入经营数据");
    String storeId = businessScope.storeId() == null ? "" : businessScope.storeId();
    String month = row == null ? "" : trim(row.month());
    Map<String, BigDecimal> values = row == null || row.values() == null ? Map.of() : row.values();

    if (storeId.isBlank()) {
      errors.add("门店不能为空");
    } else {
      if (!financeRepository.storeExists(user.tenantId(), storeId)) {
        errors.add("门店不存在或不属于当前企业");
      }
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
    Map<String, BigDecimal> existingValues = existing
        ? existingValues(user, storeId, normalizedMonth)
        : Map.of();
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
          "CONFLICT",
          existingValues
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
        existing,
        "SAVED",
        existingValues
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
        "CONFLICT",
        existingValues(user, row.storeId(), row.month())
    );
  }

  private Map<String, BigDecimal> existingValues(AuthUser user, String storeId, String month) {
    return financeRepository.entry(user.tenantId(), storeId, month, financeScope(user))
        .map(entry -> ordered(Map.ofEntries(
            Map.entry("sales", entry.sales()),
            Map.entry("refund", entry.refund()),
            Map.entry("discount", entry.discount()),
            Map.entry("material", entry.material()),
            Map.entry("packaging", entry.packaging()),
            Map.entry("loss", entry.loss()),
            Map.entry("costOther", entry.costOther()),
            Map.entry("rent", entry.rent()),
            Map.entry("labor", entry.labor()),
            Map.entry("utility", entry.utility()),
            Map.entry("property", entry.property()),
            Map.entry("commission", entry.commission()),
            Map.entry("promo", entry.promo()),
            Map.entry("repair", entry.repair()),
            Map.entry("equip", entry.equip()),
            Map.entry("expOther", entry.expOther())
        )))
        .orElse(Map.of());
  }

  private List<StoreResponse> scopedStores(AuthUser user) {
    return organizationRepository.stores(user.tenantId(), financeScope(user));
  }

  private ProfitImportRow lockParsedRow(
      AuthUser user,
      ProfitImportRow row,
      BusinessScope businessScope
  ) {
    // This compatibility behaviour belongs only to the legacy bulk-recognize endpoint.  The
    // single-store Data Entry preview deliberately bypasses it so cross-store rows remain visible
    // and can be rejected before any confirmation is possible.
    if (row == null || businessScope.storeId() == null
        || !"STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      return row;
    }
    return new ProfitImportRow(
        row.rowId(),
        businessScope.storeId(),
        businessScope.storeName() == null ? businessScope.storeId() : businessScope.storeName(),
        row.month(),
        row.confidence(),
        row.values(),
        row.warnings(),
        row.errors(),
        row.existing(),
        row.status()
    );
  }

  private BusinessScope resolveBusinessScope(AuthUser user, String storeId, String action) {
    if (businessScopeResolver != null) {
      return businessScopeResolver.resolve(
          user, DataScopeDomains.FINANCE, storeId, null, action);
    }
    String targetStoreId = trim(storeId);
    if (targetStoreId.isBlank()
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      targetStoreId = trim(user.storeId());
    }
    if (!targetStoreId.isBlank()) {
      accessControl.requireStoreAccess(
          user, DataScopeDomains.FINANCE, targetStoreId, action);
    }
    return new BusinessScope(
        targetStoreId.isBlank() ? null : targetStoreId,
        null,
        null,
        null,
        financeScope(user));
  }

  private String storeName(AuthUser user, String storeId) {
    if (storeId == null || storeId.isBlank()) {
      return "";
    }
    return scopedStores(user).stream()
        .filter(store -> storeId.equals(store.id()))
        .map(StoreResponse::name)
        .findFirst()
        .orElse(storeId);
  }

  private DataScope financeScope(AuthUser user) {
    if (accessControl.hasAllDataScope(user, DataScopeDomains.FINANCE)) {
      return DataScope.all();
    }
    List<String> storeIds = accessControl.allowedStoreIds(user, DataScopeDomains.FINANCE).stream()
        .filter(value -> value != null && !value.isBlank() && !"all".equalsIgnoreCase(value))
        .map(String::trim)
        .distinct()
        .sorted()
        .toList();
    return storeIds.isEmpty()
        ? DataScope.none()
        : new DataScope(DataScopeModes.STORE_LIST, storeIds);
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
