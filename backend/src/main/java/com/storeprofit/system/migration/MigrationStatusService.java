package com.storeprofit.system.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.inspection.InspectionScoringRules;
import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.ProfitEntryRequest;
import com.storeprofit.system.organization.OrganizationRepository;
import com.storeprofit.system.organization.StoreUpsertRequest;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.storage.StorageService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationStatusService {
  private static final String STATE_NEEDS_MIGRATION = "NEEDS_STRUCTURED_MIGRATION";
  private static final String STATE_NOT_PRESENT = "NOT_PRESENT";
  private static final String ACTION_MAP_TO_STRUCTURED_TABLE = "MAP_TO_STRUCTURED_TABLE";
  private static final String ACTION_NOOP = "NOOP";
  private static final String ACTION_UPLOAD_TO_MYSQL = "UPLOAD_TO_MYSQL";
  private static final String ACTION_BLOCKED = "BLOCKED";
  private static final String ACTION_IGNORE = "IGNORE";
  private static final String RESULT_WRITTEN_TO_MYSQL = "WRITTEN_TO_MYSQL";
  private static final String RESULT_MIGRATED = "MIGRATED";
  private static final String RESULT_NOT_PRESENT = "NOT_PRESENT";
  private static final String RESULT_UNSUPPORTED = "UNSUPPORTED";
  private static final String RESULT_FAILED = "FAILED";
  private static final String CATEGORY_BUSINESS_DATA = "BUSINESS_DATA";
  private static final String CATEGORY_SENSITIVE_AUTH = "SENSITIVE_AUTH";
  private static final String CATEGORY_COMPATIBILITY_METADATA = "COMPATIBILITY_METADATA";
  private static final String CATEGORY_UNKNOWN = "UNKNOWN";
  private static final Set<String> BLOCKED_BROWSER_KEYS = Set.of("accounts", "app_pin", "tokens", "passwords");
  private static final Set<String> METADATA_BROWSER_KEYS = Set.of("schema_v");
  private static final List<LegacyBusinessKey> LEGACY_BUSINESS_KEYS = List.of(
      new LegacyBusinessKey("stores", "store_branch"),
      new LegacyBusinessKey("entries", "profit_entry"),
      new LegacyBusinessKey("salary", "salary_record"),
      new LegacyBusinessKey("expenses", "expense_claim"),
      new LegacyBusinessKey("inspections", "inspection_record"),
      new LegacyBusinessKey("logs", "operation_log")
  );

  private final JdbcTemplate jdbcTemplate;
  private final StorageService storageService;
  private final OrganizationRepository organizationRepository;
  private final FinanceRepository financeRepository;
  private final ObjectMapper objectMapper;

  public MigrationStatusService(
      JdbcTemplate jdbcTemplate,
      StorageService storageService,
      OrganizationRepository organizationRepository,
      FinanceRepository financeRepository,
      ObjectMapper objectMapper
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.storageService = storageService;
    this.organizationRepository = organizationRepository;
    this.financeRepository = financeRepository;
    this.objectMapper = objectMapper;
  }

  public MigrationStatusResponse status(AuthUser user) {
    requireOwner(user);
    List<LegacyKvKeyStatusResponse> keyStatuses = LEGACY_BUSINESS_KEYS.stream()
        .map(this::statusFor)
        .toList();
    int presentCount = (int) keyStatuses.stream()
        .filter(LegacyKvKeyStatusResponse::present)
        .count();
    return new MigrationStatusResponse(
        presentCount > 0,
        keyStatuses.size(),
        presentCount,
        keyStatuses
    );
  }

  public LegacyKvMigrationPreviewResponse legacyKvPreview(AuthUser user) {
    MigrationStatusResponse status = status(user);
    List<LegacyKvMigrationPreviewItemResponse> items = status.legacyBusinessKeys().stream()
        .map(this::previewItemFor)
        .toList();
    int actionableKeyCount = (int) items.stream()
        .filter(LegacyKvMigrationPreviewItemResponse::present)
        .count();
    long totalValueBytes = items.stream()
        .mapToLong(LegacyKvMigrationPreviewItemResponse::valueBytes)
        .sum();
    return new LegacyKvMigrationPreviewResponse(
        false,
        status.businessKeyCount(),
        actionableKeyCount,
        totalValueBytes,
        items
    );
  }

  public BrowserStoragePreviewResponse browserStoragePreview(AuthUser user, BrowserStoragePreviewRequest request) {
    requireOwner(user);
    Map<String, String> entries = normalizedEntries(request);
    List<BrowserStoragePreviewItemResponse> items = entries.entrySet().stream()
        .map(entry -> browserStoragePreviewItem(entry.getKey(), entry.getValue()))
        .toList();
    int businessKeyCount = (int) items.stream()
        .filter(item -> CATEGORY_BUSINESS_DATA.equals(item.category()))
        .count();
    int blockedKeyCount = (int) items.stream()
        .filter(item -> CATEGORY_SENSITIVE_AUTH.equals(item.category()))
        .count();
    int ignoredKeyCount = items.size() - businessKeyCount - blockedKeyCount;
    long totalBusinessValueBytes = items.stream()
        .filter(item -> CATEGORY_BUSINESS_DATA.equals(item.category()))
        .mapToLong(BrowserStoragePreviewItemResponse::valueBytes)
        .sum();
    return new BrowserStoragePreviewResponse(
        businessKeyCount > 0,
        items.size(),
        businessKeyCount,
        blockedKeyCount,
        ignoredKeyCount,
        totalBusinessValueBytes,
        items
    );
  }

  public BrowserStorageMigrationRunResponse browserStorageRun(AuthUser user, BrowserStoragePreviewRequest request) {
    requireOwner(user);
    Map<String, String> entries = normalizedEntries(request);
    List<BrowserStorageMigrationRunItemResponse> items = entries.entrySet().stream()
        .map(entry -> browserStorageRunItem(user, entry.getKey(), entry.getValue()))
        .toList();
    int writtenKeyCount = (int) items.stream()
        .filter(item -> RESULT_WRITTEN_TO_MYSQL.equals(item.result()))
        .count();
    int blockedKeyCount = (int) items.stream()
        .filter(item -> ACTION_BLOCKED.equals(item.result()))
        .count();
    int ignoredKeyCount = items.size() - writtenKeyCount - blockedKeyCount;
    return new BrowserStorageMigrationRunResponse(
        writtenKeyCount > 0,
        items.size(),
        writtenKeyCount,
        blockedKeyCount,
        ignoredKeyCount,
        items
    );
  }

  @Transactional
  public LegacyKvMigrationRunResponse legacyKvRun(AuthUser user, LegacyKvMigrationRunRequest request) {
    requireOwner(user);
    List<String> keys = normalizedLegacyRunKeys(request);
    List<LegacyKvMigrationRunItemResponse> items = keys.stream()
        .map(key -> legacyKvRunItem(user, key))
        .toList();
    int migratedKeyCount = (int) items.stream()
        .filter(item -> RESULT_MIGRATED.equals(item.result()))
        .count();
    int failedKeyCount = (int) items.stream()
        .filter(item -> RESULT_FAILED.equals(item.result()))
        .count();
    int skippedKeyCount = items.size() - migratedKeyCount - failedKeyCount;
    return new LegacyKvMigrationRunResponse(
        migratedKeyCount > 0,
        items.size(),
        migratedKeyCount,
        skippedKeyCount,
        failedKeyCount,
        items
    );
  }

  private LegacyKvKeyStatusResponse statusFor(LegacyBusinessKey key) {
    Optional<Long> valueBytes = valueBytes(key.key());
    return new LegacyKvKeyStatusResponse(
        key.key(),
        key.targetTable(),
        valueBytes.isPresent(),
        valueBytes.orElse(0L),
        valueBytes.isPresent() ? STATE_NEEDS_MIGRATION : STATE_NOT_PRESENT
    );
  }

  private Optional<Long> valueBytes(String key) {
    return jdbcTemplate.query(
        "select coalesce(length(storage_value), 0) from kv_storage where storage_key = ?",
        resultSet -> {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          return Optional.of(resultSet.getLong(1));
        },
        key
    );
  }

  private Optional<String> storageValue(String key) {
    return jdbcTemplate.query(
        "select storage_value from kv_storage where storage_key = ?",
        resultSet -> {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          return Optional.ofNullable(resultSet.getString(1));
        },
        key
    );
  }

  private LegacyKvMigrationPreviewItemResponse previewItemFor(LegacyKvKeyStatusResponse status) {
    return new LegacyKvMigrationPreviewItemResponse(
        status.key(),
        status.targetTable(),
        status.present(),
        status.valueBytes(),
        status.present() ? ACTION_MAP_TO_STRUCTURED_TABLE : ACTION_NOOP,
        false
    );
  }

  private BrowserStoragePreviewItemResponse browserStoragePreviewItem(String key, String value) {
    String normalizedKey = normalizeKey(key);
    long valueBytes = value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    Optional<LegacyBusinessKey> businessKey = legacyBusinessKey(normalizedKey);
    if (businessKey.isPresent()) {
      return new BrowserStoragePreviewItemResponse(
          normalizedKey,
          CATEGORY_BUSINESS_DATA,
          businessKey.get().targetTable(),
          valueBytes,
          ACTION_UPLOAD_TO_MYSQL,
          true
      );
    }
    if (BLOCKED_BROWSER_KEYS.contains(normalizedKey)) {
      return new BrowserStoragePreviewItemResponse(
          normalizedKey,
          CATEGORY_SENSITIVE_AUTH,
          null,
          valueBytes,
          ACTION_BLOCKED,
          false
      );
    }
    if (METADATA_BROWSER_KEYS.contains(normalizedKey)) {
      return new BrowserStoragePreviewItemResponse(
          normalizedKey,
          CATEGORY_COMPATIBILITY_METADATA,
          null,
          valueBytes,
          ACTION_IGNORE,
          false
      );
    }
    return new BrowserStoragePreviewItemResponse(
        normalizedKey,
        CATEGORY_UNKNOWN,
        null,
        valueBytes,
        ACTION_IGNORE,
        false
    );
  }

  private BrowserStorageMigrationRunItemResponse browserStorageRunItem(AuthUser user, String key, String value) {
    BrowserStoragePreviewItemResponse preview = browserStoragePreviewItem(key, value);
    if (CATEGORY_BUSINESS_DATA.equals(preview.category())) {
      storageService.set(user, preview.key(), value == null ? "" : value);
      return new BrowserStorageMigrationRunItemResponse(
          preview.key(),
          preview.category(),
          preview.targetTable(),
          RESULT_WRITTEN_TO_MYSQL,
          true
      );
    }
    return new BrowserStorageMigrationRunItemResponse(
        preview.key(),
        preview.category(),
        preview.targetTable(),
        preview.plannedAction(),
        false
    );
  }

  private LegacyKvMigrationRunItemResponse legacyKvRunItem(AuthUser user, String key) {
    Optional<LegacyBusinessKey> businessKey = legacyBusinessKey(key);
    if (businessKey.isEmpty()) {
      return new LegacyKvMigrationRunItemResponse(key, null, RESULT_UNSUPPORTED, 0, "legacy KV key is not a known business key");
    }
    Optional<String> value = storageValue(key);
    if (value.isEmpty()) {
      return new LegacyKvMigrationRunItemResponse(key, businessKey.get().targetTable(), RESULT_NOT_PRESENT, 0, "legacy KV key is not present");
    }
    if ("entries".equals(key)) {
      return legacyEntriesRunItem(user, key, businessKey.get().targetTable(), value.get());
    }
    if ("salary".equals(key)) {
      return legacySalaryRunItem(user, key, businessKey.get().targetTable(), value.get());
    }
    if ("expenses".equals(key)) {
      return legacyExpensesRunItem(user, key, businessKey.get().targetTable(), value.get());
    }
    if ("inspections".equals(key)) {
      return legacyInspectionsRunItem(user, key, businessKey.get().targetTable(), value.get());
    }
    if ("logs".equals(key)) {
      return legacyLogsRunItem(user, key, businessKey.get().targetTable(), value.get());
    }
    if (!"stores".equals(key)) {
      return new LegacyKvMigrationRunItemResponse(key, businessKey.get().targetTable(), RESULT_UNSUPPORTED, 0, "structured migration for this key is not implemented yet");
    }
    try {
      int migratedRecordCount = migrateStores(user, value.get());
      logStructuredMigration(user, key, businessKey.get().targetTable(), migratedRecordCount);
      return new LegacyKvMigrationRunItemResponse(key, businessKey.get().targetTable(), RESULT_MIGRATED, migratedRecordCount, "migrated " + migratedRecordCount + " stores");
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, businessKey.get().targetTable(), RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private LegacyKvMigrationRunItemResponse legacyEntriesRunItem(AuthUser user, String key, String targetTable, String value) {
    try {
      EntryMigrationResult result = migrateEntries(user, value);
      logStructuredMigration(user, key, targetTable, result.migratedRecordCount());
      return new LegacyKvMigrationRunItemResponse(
          key,
          targetTable,
          RESULT_MIGRATED,
          result.migratedRecordCount(),
          "migrated " + result.migratedRecordCount() + " profit entries, skipped=" + result.skippedRecordCount()
      );
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, targetTable, RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private LegacyKvMigrationRunItemResponse legacySalaryRunItem(AuthUser user, String key, String targetTable, String value) {
    try {
      SalaryMigrationResult result = migrateSalary(user, value);
      logStructuredMigration(user, key, targetTable, result.migratedRecordCount());
      return new LegacyKvMigrationRunItemResponse(
          key,
          targetTable,
          RESULT_MIGRATED,
          result.migratedRecordCount(),
          "migrated " + result.migratedRecordCount() + " salary records, skipped=" + result.skippedRecordCount()
      );
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, targetTable, RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private LegacyKvMigrationRunItemResponse legacyExpensesRunItem(AuthUser user, String key, String targetTable, String value) {
    try {
      ExpenseMigrationResult result = migrateExpenses(user, value);
      logStructuredMigration(user, key, targetTable, result.migratedRecordCount());
      return new LegacyKvMigrationRunItemResponse(
          key,
          targetTable,
          RESULT_MIGRATED,
          result.migratedRecordCount(),
          "migrated " + result.migratedRecordCount() + " expense claims, skipped=" + result.skippedRecordCount()
      );
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, targetTable, RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private LegacyKvMigrationRunItemResponse legacyInspectionsRunItem(AuthUser user, String key, String targetTable, String value) {
    try {
      InspectionMigrationResult result = migrateInspections(user, value);
      logStructuredMigration(user, key, targetTable, result.migratedRecordCount());
      return new LegacyKvMigrationRunItemResponse(
          key,
          targetTable,
          RESULT_MIGRATED,
          result.migratedRecordCount(),
          "migrated " + result.migratedRecordCount() + " inspection records, skipped=" + result.skippedRecordCount()
      );
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, targetTable, RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private LegacyKvMigrationRunItemResponse legacyLogsRunItem(AuthUser user, String key, String targetTable, String value) {
    try {
      LogMigrationResult result = migrateLogs(user, value);
      logStructuredMigration(user, key, targetTable, result.migratedRecordCount());
      return new LegacyKvMigrationRunItemResponse(
          key,
          targetTable,
          RESULT_MIGRATED,
          result.migratedRecordCount(),
          "migrated " + result.migratedRecordCount() + " operation logs, skipped=" + result.skippedRecordCount()
      );
    } catch (IllegalArgumentException | JsonProcessingException ex) {
      return new LegacyKvMigrationRunItemResponse(key, targetTable, RESULT_FAILED, 0, ex.getMessage());
    }
  }

  private int migrateStores(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isArray()) {
      throw new IllegalArgumentException("stores legacy KV value must be a JSON array");
    }
    int migratedRecordCount = 0;
    for (JsonNode store : root) {
      StoreUpsertRequest request = storeUpsertRequest(user, store);
      organizationRepository.upsertStore(user.tenantId(), request);
      migratedRecordCount++;
    }
    return migratedRecordCount;
  }

  private StoreUpsertRequest storeUpsertRequest(AuthUser user, JsonNode store) {
    String id = requiredText(store, "id");
    String name = requiredText(store, "name");
    String brandName = canonicalBrandName(textOrDefault(store, "brand", "未分类品牌"));
    long brandId = organizationRepository.ensureBrand(
        user.tenantId(),
        legacyBrandCode(brandName),
        brandName,
        "#64748b",
        900
    );
    String regionCode = textOrNull(store, "regionCode", "region_code", "provinceCode", "province_code");
    if (regionCode == null) {
      throw new IllegalArgumentException(
          "store " + id + " must provide explicit regionCode JINGZHOU or SHANDONG");
    }
    regionCode = regionCode.trim().toUpperCase();
    if (!Set.of("JINGZHOU", "SHANDONG").contains(regionCode)) {
      throw new IllegalArgumentException(
          "store " + id + " has unsupported regionCode " + regionCode);
    }
    return new StoreUpsertRequest(
        id,
        textOrDefault(store, "code", id),
        name,
        brandId,
        textOrNull(store, "area"),
        textOrNull(store, "manager"),
        textOrNull(store, "managerPhone", "manager_phone"),
        textOrNull(store, "openDate", "open_date"),
        textOrNull(store, "status"),
        textOrNull(store, "note"),
        regionCode,
        null
    );
  }

  private EntryMigrationResult migrateEntries(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isObject()) {
      throw new IllegalArgumentException("entries legacy KV value must be a JSON object");
    }
    int migratedRecordCount = 0;
    int skippedRecordCount = 0;
    Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      Optional<LegacyEntryKey> entryKey = legacyEntryKey(field.getKey());
      if (entryKey.isEmpty() || !financeRepository.storeExists(user.tenantId(), entryKey.get().storeId())) {
        skippedRecordCount++;
        continue;
      }
      financeRepository.upsert(user.tenantId(), profitEntryRequest(entryKey.get(), field.getValue()), user.id());
      migratedRecordCount++;
    }
    return new EntryMigrationResult(migratedRecordCount, skippedRecordCount);
  }

  private SalaryMigrationResult migrateSalary(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isArray()) {
      throw new IllegalArgumentException("salary legacy KV value must be a JSON array");
    }
    int migratedRecordCount = 0;
    int skippedRecordCount = 0;
    for (JsonNode salary : root) {
      String storeId = textOrNull(salary, "sid", "storeId", "store_id");
      String month = textOrNull(salary, "month");
      String employeeName = textOrNull(salary, "name", "employeeName", "employee_name");
      if (storeId == null || month == null || employeeName == null
          || !financeRepository.storeExists(user.tenantId(), storeId)) {
        skippedRecordCount++;
        continue;
      }
      String salaryId = salaryRecordId(salary, storeId, month, employeeName);
      upsertSalaryRecord(user, salaryId, storeId, month, employeeName, salary);
      upsertEmployeeFromSalary(user, salaryId, storeId, month, employeeName, salary);
      migratedRecordCount++;
    }
    return new SalaryMigrationResult(migratedRecordCount, skippedRecordCount);
  }

  private ExpenseMigrationResult migrateExpenses(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isArray()) {
      throw new IllegalArgumentException("expenses legacy KV value must be a JSON array");
    }
    int migratedRecordCount = 0;
    int skippedRecordCount = 0;
    for (JsonNode expense : root) {
      String storeId = textOrNull(expense, "sid", "storeId", "store_id");
      if (storeId == null || !financeRepository.storeExists(user.tenantId(), storeId)) {
        skippedRecordCount++;
        continue;
      }
      String expenseId = expenseClaimId(expense, storeId);
      upsertExpenseClaim(user, expenseId, storeId, expense);
      upsertExpenseAttachment(user, expenseId, storeId, expense);
      migratedRecordCount++;
    }
    return new ExpenseMigrationResult(migratedRecordCount, skippedRecordCount);
  }

  private InspectionMigrationResult migrateInspections(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isArray()) {
      throw new IllegalArgumentException("inspections legacy KV value must be a JSON array");
    }
    int migratedRecordCount = 0;
    int skippedRecordCount = 0;
    for (JsonNode inspection : root) {
      String storeId = textOrNull(inspection, "sid", "storeId", "store_id");
      String inspectionDate = inspectionDate(inspection);
      if (storeId == null || inspectionDate == null || !financeRepository.storeExists(user.tenantId(), storeId)) {
        skippedRecordCount++;
        continue;
      }
      upsertInspectionRecord(user, inspectionRecordId(inspection, storeId, inspectionDate), storeId, inspectionDate, inspection);
      migratedRecordCount++;
    }
    return new InspectionMigrationResult(migratedRecordCount, skippedRecordCount);
  }

  private LogMigrationResult migrateLogs(AuthUser user, String value) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(value);
    if (!root.isArray()) {
      throw new IllegalArgumentException("logs legacy KV value must be a JSON array");
    }
    int migratedRecordCount = 0;
    int skippedRecordCount = 0;
    for (JsonNode log : root) {
      String targetId = legacyLogTargetId(log);
      if (legacyOperationLogExists(user, targetId)) {
        skippedRecordCount++;
        continue;
      }
      insertLegacyOperationLog(user, targetId, log);
      migratedRecordCount++;
    }
    return new LogMigrationResult(migratedRecordCount, skippedRecordCount);
  }

  private void upsertSalaryRecord(
      AuthUser user,
      String id,
      String storeId,
      String month,
      String employeeName,
      JsonNode salary
  ) {
    jdbcTemplate.update("""
        insert into salary_record(
          id, tenant_id, store_id, month, employee_name, position, attendance, gross,
          normal_hours, ot_hours, work_hours, vacation_left, vacation_note,
          base, social, post, meal, full_attendance, commission, overtime, seniority,
          birthday_benefit, late_night, subsidy, performance, deduct_uniform, return_uniform, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          tenant_id = values(tenant_id),
          store_id = values(store_id),
          month = values(month),
          employee_name = values(employee_name),
          position = values(position),
          attendance = values(attendance),
          gross = values(gross),
          normal_hours = values(normal_hours),
          ot_hours = values(ot_hours),
          work_hours = values(work_hours),
          vacation_left = values(vacation_left),
          vacation_note = values(vacation_note),
          base = values(base),
          social = values(social),
          post = values(post),
          meal = values(meal),
          full_attendance = values(full_attendance),
          commission = values(commission),
          overtime = values(overtime),
          seniority = values(seniority),
          birthday_benefit = values(birthday_benefit),
          late_night = values(late_night),
          subsidy = values(subsidy),
          performance = values(performance),
          deduct_uniform = values(deduct_uniform),
          return_uniform = values(return_uniform),
          updated_at = current_timestamp
        """,
        id,
        user.tenantId(),
        storeId,
        month,
        employeeName,
        textOrNull(salary, "position"),
        textOrNull(salary, "attendance"),
        amount(salary, "gross"),
        amount(salary, "normalHours", "normal_hours"),
        amount(salary, "otHours", "ot_hours"),
        amount(salary, "workHours", "work_hours"),
        amount(salary, "vacationLeft", "vacation_left"),
        textOrNull(salary, "vacationNote", "vacation_note"),
        amount(salary, "base"),
        amount(salary, "social"),
        amount(salary, "post"),
        amount(salary, "meal"),
        amount(salary, "fullAttendance", "full_attendance", "full"),
        amount(salary, "commission"),
        amount(salary, "overtime"),
        amount(salary, "seniority"),
        amount(salary, "birthdayBenefit", "birthday_benefit"),
        amount(salary, "lateNight", "late_night", "latenight"),
        amount(salary, "subsidy"),
        amount(salary, "performance"),
        amount(salary, "deductUniform", "deduct_uniform"),
        amount(salary, "returnUniform", "return_uniform")
    );
  }

  private void upsertInspectionRecord(
      AuthUser user,
      String id,
      String storeId,
      String inspectionDate,
      JsonNode inspection
  ) throws JsonProcessingException {
    BigDecimal sourceFullScore = amountOrDefault(
        inspection, InspectionScoringRules.LEGACY_MAX_SCORE, "fullScore", "full_score");
    BigDecimal sourceScore = inspectionScore(inspection, sourceFullScore);
    BigDecimal fullScore = InspectionScoringRules.MAX_SCORE;
    BigDecimal score = InspectionScoringRules.normalizeScore(sourceScore, sourceFullScore);
    boolean redLineHit = inspectionRedLineHit(inspection);
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector, brand, full_score, score, passed,
          deductions_json, redlines_json, photos_json, note, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on duplicate key update
          tenant_id = values(tenant_id),
          store_id = values(store_id),
          inspection_date = values(inspection_date),
          inspector = values(inspector),
          brand = values(brand),
          full_score = values(full_score),
          score = values(score),
          passed = values(passed),
          deductions_json = values(deductions_json),
          redlines_json = values(redlines_json),
          photos_json = values(photos_json),
          note = values(note),
          updated_at = current_timestamp
        """,
        id,
        user.tenantId(),
        storeId,
        inspectionDate,
        textOrNull(inspection, "inspector"),
        canonicalBrandName(textOrNull(inspection, "brand")),
        fullScore,
        score,
        InspectionScoringRules.passed(score, redLineHit) ? 1 : 0,
        jsonArrayText(inspection, "deductions"),
        jsonArrayText(inspection, "redlines"),
        jsonArrayText(inspection, "photos"),
        textOrNull(inspection, "note")
    );
  }

  private void insertLegacyOperationLog(AuthUser user, String targetId, JsonNode log) throws JsonProcessingException {
    jdbcTemplate.update("""
        insert into operation_log(
          tenant_id, operator_id, operator_name, action, target_type, target_id,
          store_id, month, before_json, after_json, reason, created_at
        )
        values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, current_timestamp))
        """,
        user.tenantId(),
        textOrDefault(log, "by", "legacy"),
        truncate(textOrDefault(log, "type", "legacy_log"), 80),
        truncate(textOrDefault(log, "dataType", "legacy_operation_log"), 80),
        targetId,
        truncate(textOrNull(log, "sid", "storeId", "store_id", "store"), 64),
        truncate(textOrNull(log, "month"), 7),
        jsonOrText(log, "before", "beforeJson", "before_json"),
        jsonOrText(log, "after", "afterJson", "after_json"),
        truncate(textOrNull(log, "reason"), 255),
        legacyLogTimestamp(log)
    );
  }

  private void upsertExpenseClaim(AuthUser user, String id, String storeId, JsonNode expense) {
    jdbcTemplate.update("""
        insert into expense_claim(
          id, tenant_id, store_id, month, amount, category, reason, status, image_url,
          submitted_by, reviewed_by, reviewed_at, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null, current_timestamp)
        on duplicate key update
          tenant_id = values(tenant_id),
          store_id = values(store_id),
          month = values(month),
          amount = values(amount),
          category = values(category),
          reason = values(reason),
          status = values(status),
          image_url = values(image_url),
          submitted_by = values(submitted_by),
          updated_at = current_timestamp
        """,
        id,
        user.tenantId(),
        storeId,
        expenseMonth(expense),
        amount(expense, "amount"),
        textOrNull(expense, "category", "cat"),
        textOrNull(expense, "reason", "note"),
        expenseStatus(expense),
        externalImageUrl(expense),
        user.id()
    );
  }

  private void upsertEmployeeFromSalary(AuthUser user, String salaryId, String storeId, String month, String employeeName, JsonNode salary) {
    String employeeId = "legacy-employee-" + Integer.toUnsignedString((storeId + "\u0000" + employeeName).hashCode(), 36);
    jdbcTemplate.update("""
        insert into employee(id, tenant_id, store_id, store_name, brand_name, name, position, base_salary, status)
        select ?, ?, s.id, s.name, b.name, ?, ?, ?, '在职'
        from store_branch s left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id
        where s.tenant_id = ? and s.id = ?
        on duplicate key update position=values(position), base_salary=values(base_salary), updated_at=current_timestamp
        """, employeeId, user.tenantId(), employeeName, textOrNull(salary, "position"), amount(salary, "base"),
        user.tenantId(), storeId);
    Integer linked = jdbcTemplate.queryForObject("""
        select count(*) from salary_record
        where tenant_id=? and employee_id=? and month=?
        """, Integer.class, user.tenantId(), employeeId, month);
    if (linked != null && linked == 0) {
      jdbcTemplate.update("update salary_record set employee_id=? where tenant_id=? and id=?",
          employeeId, user.tenantId(), salaryId);
    }
  }

  private String externalImageUrl(JsonNode expense) {
    String value = textOrNull(expense, "imageUrl", "image_url", "img");
    if (value == null || value.startsWith("data:") || value.length() > 500) {
      return null;
    }
    return value;
  }

  private void upsertExpenseAttachment(AuthUser user, String expenseId, String storeId, JsonNode expense) {
    String value = textOrNull(expense, "img");
    if (value == null || !value.startsWith("data:")) {
      return;
    }
    int comma = value.indexOf(',');
    if (comma < 0 || !value.substring(0, comma).contains(";base64")) {
      throw new IllegalArgumentException("legacy expense attachment is not a valid base64 data URL");
    }
    String contentType = value.substring(5, value.indexOf(';'));
    byte[] content = Base64.getDecoder().decode(value.substring(comma + 1));
    jdbcTemplate.update("delete from warehouse_attachment where tenant_id=? and business_type='EXPENSE_CLAIM' and business_id=?",
        user.tenantId(), expenseId);
    jdbcTemplate.update("""
        insert into warehouse_attachment(
          tenant_id, store_id, business_type, business_id, file_name, content_type,
          file_size, storage_path, content, uploaded_by
        ) values (?, ?, 'EXPENSE_CLAIM', ?, ?, ?, ?, null, ?, ?)
        """, user.tenantId(), storeId, expenseId, expenseId + imageExtension(contentType), contentType,
        content.length, content, user.id());
  }

  private String imageExtension(String contentType) {
    if ("image/png".equalsIgnoreCase(contentType)) return ".png";
    if ("image/gif".equalsIgnoreCase(contentType)) return ".gif";
    if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
    return ".jpg";
  }

  private ProfitEntryRequest profitEntryRequest(LegacyEntryKey key, JsonNode entry) {
    return new ProfitEntryRequest(
        key.storeId(),
        key.month(),
        amount(entry, "sales", "rev", "revenue"),
        amount(entry, "refund"),
        amount(entry, "discount"),
        amount(entry, "material"),
        amount(entry, "packaging"),
        amount(entry, "loss"),
        amount(entry, "costOther", "cost_other"),
        amount(entry, "rent"),
        amount(entry, "labor"),
        amount(entry, "utility"),
        amount(entry, "property"),
        amount(entry, "commission"),
        amount(entry, "promo"),
        amount(entry, "repair"),
        amount(entry, "equip"),
        amount(entry, "expOther", "exp_other", "other"),
        textOrNull(entry, "note")
    );
  }

  private Map<String, String> normalizedEntries(BrowserStoragePreviewRequest request) {
    Map<String, String> normalizedEntries = new TreeMap<>();
    if (request == null) {
      return normalizedEntries;
    }
    request.entries().forEach((key, value) -> normalizedEntries.put(normalizeKey(key), value));
    return normalizedEntries;
  }

  private List<String> normalizedLegacyRunKeys(LegacyKvMigrationRunRequest request) {
    if (request == null || request.keys().isEmpty()) {
      return LEGACY_BUSINESS_KEYS.stream()
          .map(LegacyBusinessKey::key)
          .toList();
    }
    List<String> keys = new ArrayList<>();
    for (String key : request.keys()) {
      String normalizedKey = normalizeKey(key);
      if (!normalizedKey.isBlank() && !keys.contains(normalizedKey)) {
        keys.add(normalizedKey);
      }
    }
    return keys;
  }

  private Optional<LegacyBusinessKey> legacyBusinessKey(String key) {
    return LEGACY_BUSINESS_KEYS.stream()
        .filter(candidate -> candidate.key().equals(key))
        .findFirst();
  }

  private String normalizeKey(String key) {
    return key == null ? "" : key.trim().toLowerCase();
  }

  private String requiredText(JsonNode node, String fieldName) {
    String value = textOrNull(node, fieldName);
    if (value == null) {
      throw new IllegalArgumentException("stores item missing required field: " + fieldName);
    }
    return value;
  }

  private String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
    String value = textOrNull(node, fieldName);
    return value == null ? defaultValue : value;
  }

  private String textOrNull(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value != null && !value.isNull()) {
        String text = value.asText();
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }

  private BigDecimal amount(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isNumber()) {
        return value.decimalValue();
      }
      String text = value.asText();
      if (!text.isBlank()) {
        return new BigDecimal(text.trim());
      }
    }
    return BigDecimal.ZERO;
  }

  private Optional<LegacyEntryKey> legacyEntryKey(String rawKey) {
    if (rawKey == null) {
      return Optional.empty();
    }
    String[] parts = rawKey.split("\\|", 2);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new LegacyEntryKey(parts[0].trim(), parts[1].trim()));
  }

  private String salaryRecordId(JsonNode salary, String storeId, String month, String employeeName) {
    String id = textOrNull(salary, "id");
    if (id != null) {
      return id;
    }
    String source = storeId + "|" + month + "|" + employeeName;
    return "legacy-salary-" + Integer.toUnsignedString(source.hashCode(), 36);
  }

  private String expenseClaimId(JsonNode expense, String storeId) {
    String id = textOrNull(expense, "id");
    if (id != null) {
      return id;
    }
    String source = storeId + "|" + textOrNull(expense, "month", "at") + "|" + amount(expense, "amount");
    return "legacy-expense-" + Integer.toUnsignedString(source.hashCode(), 36);
  }

  private String inspectionRecordId(JsonNode inspection, String storeId, String inspectionDate) {
    String id = textOrNull(inspection, "id");
    if (id != null) {
      return id;
    }
    String source = storeId + "|" + inspectionDate + "|" + textOrNull(inspection, "inspector", "at");
    return "legacy-inspection-" + Integer.toUnsignedString(source.hashCode(), 36);
  }

  private String legacyLogTargetId(JsonNode log) throws JsonProcessingException {
    String id = textOrNull(log, "id");
    if (id != null) {
      return truncate("legacy-log-" + id, 120);
    }
    return "legacy-log-" + Integer.toUnsignedString(objectMapper.writeValueAsString(log).hashCode(), 36);
  }

  private boolean legacyOperationLogExists(AuthUser user, String targetId) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from operation_log where tenant_id = ? and target_id = ?",
        Integer.class,
        user.tenantId(),
        targetId
    );
    return count != null && count > 0;
  }

  private String jsonOrText(JsonNode node, String... fieldNames) throws JsonProcessingException {
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isTextual()) {
        String text = value.asText();
        return text.isBlank() ? null : text;
      }
      return objectMapper.writeValueAsString(value);
    }
    return null;
  }

  private Timestamp legacyLogTimestamp(JsonNode log) {
    String value = textOrNull(log, "at", "createdAt", "created_at");
    if (value == null) {
      return null;
    }
    try {
      return Timestamp.from(Instant.parse(value));
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private String inspectionDate(JsonNode inspection) {
    String date = textOrNull(inspection, "date", "inspectionDate", "inspection_date");
    if (date != null) {
      return date;
    }
    String createdAt = textOrNull(inspection, "at", "createdAt", "created_at");
    if (createdAt != null && createdAt.length() >= 10) {
      return createdAt.substring(0, 10);
    }
    return null;
  }

  private BigDecimal inspectionScore(JsonNode inspection, BigDecimal fullScore) {
    if (hasAnyField(inspection, "score")) {
      return amount(inspection, "score");
    }
    BigDecimal score = fullScore.subtract(deductionTotal(inspection));
    return score.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : score;
  }

  private BigDecimal deductionTotal(JsonNode inspection) {
    JsonNode deductions = inspection.get("deductions");
    if (deductions == null || !deductions.isArray()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (JsonNode deduction : deductions) {
      total = total.add(amount(deduction, "deduct"));
    }
    return total;
  }

  private boolean inspectionRedLineHit(JsonNode inspection) {
    String resultCode = textOrNull(inspection, "resultCode", "result_code");
    if ("RED_LINE_FAILED".equalsIgnoreCase(resultCode)) {
      return true;
    }
    JsonNode redlines = inspection.get("redlines");
    return redlines != null && redlines.isArray() && !redlines.isEmpty();
  }

  private String jsonArrayText(JsonNode node, String fieldName) throws JsonProcessingException {
    JsonNode value = node.get(fieldName);
    if (value == null || !value.isArray()) {
      return "[]";
    }
    return objectMapper.writeValueAsString(value);
  }

  private BigDecimal amountOrDefault(JsonNode node, BigDecimal defaultValue, String... fieldNames) {
    if (!hasAnyField(node, fieldNames)) {
      return defaultValue;
    }
    return amount(node, fieldNames);
  }

  private boolean hasAnyField(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode value = node.get(fieldName);
      if (value != null && !value.isNull()) {
        return true;
      }
    }
    return false;
  }

  private String expenseMonth(JsonNode expense) {
    String month = textOrNull(expense, "month");
    if (month != null) {
      return month;
    }
    String submittedAt = textOrNull(expense, "at", "createdAt", "created_at");
    if (submittedAt != null && submittedAt.length() >= 7) {
      return submittedAt.substring(0, 7);
    }
    return null;
  }

  private String expenseStatus(JsonNode expense) {
    String status = textOrNull(expense, "status");
    if (status == null) {
      return "待审核";
    }
    return switch (status.trim().toLowerCase()) {
      case "pending", "submitted" -> "待审核";
      case "done", "approved" -> "已完成";
      case "rejected" -> "已驳回";
      default -> status;
    };
  }

  private String legacyBrandCode(String brandName) {
    if (brandName == null || brandName.isBlank()) {
      return "legacy-default";
    }
    return "legacy-" + Integer.toUnsignedString(brandName.hashCode(), 36);
  }

  private String canonicalBrandName(String brandName) {
    return brandName == null ? null : brandName.replace("茹果", "茹菓");
  }

  private void logStructuredMigration(AuthUser user, String key, String targetTable, int migratedRecordCount) {
    jdbcTemplate.update("""
        insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, reason, created_at)
        values (?, ?, ?, 'legacy_kv_structured_migration', ?, ?, ?, current_timestamp)
        """,
        user.tenantId(),
        user.id(),
        user.displayName(),
        targetTable,
        key,
        "legacy KV structured migration: " + key + ", records=" + migratedRecordCount
    );
  }

  private void requireOwner(AuthUser user) {
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "仅老板（系统管理员）可查看迁移状态", HttpStatus.FORBIDDEN);
    }
  }

  private record LegacyBusinessKey(String key, String targetTable) {
  }

  private record LegacyEntryKey(String storeId, String month) {
  }

  private record EntryMigrationResult(int migratedRecordCount, int skippedRecordCount) {
  }

  private record SalaryMigrationResult(int migratedRecordCount, int skippedRecordCount) {
  }

  private record ExpenseMigrationResult(int migratedRecordCount, int skippedRecordCount) {
  }

  private record InspectionMigrationResult(int migratedRecordCount, int skippedRecordCount) {
  }

  private record LogMigrationResult(int migratedRecordCount, int skippedRecordCount) {
  }
}
