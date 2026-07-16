package com.storeprofit.system.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * R1-03: Removed @Component and ApplicationRunner to prevent automatic runtime seed.
 * The importSeed() method can be called explicitly for controlled dev/demo fixture use.
 *
 * <h3>Data source format</h3>
 * Reads a file containing JavaScript arrays:
 * <pre>const SALARY_SEED = [{sid, month, name, position, base, vacationNote}, ...];
 * const SALARY_SEED_Q1 = [{...}, ...];</pre>
 *
 * <h3>Import logic</h3>
 * <ol>
 *   <li>Parse SALARY_SEED + SALARY_SEED_Q1 rows (e.g. 194 total rows)</li>
 *   <li>Filter: skip blank storeId/name rows and stores that don't exist in store_branch</li>
 *   <li>Deduplicate by (store_id + name), keeping the latest month's data per employee</li>
 *   <li>Insert remaining unique employees via {@link EmployeeRepository#upsertSeed}</li>
 * </ol>
 *
 * <h3>Production safety</h3>
 * This seed is DISABLED by default (app.seed.legacy-employee-enabled=false).
 * importSeed() logs a message and returns immediately when disabled.
 * It must only be enabled in dev/demo environments with an explicit legacy file path.
 * When enabled, upsertSeed sets data_source='LEGACY_SEED' and will NOT overwrite
 * employees whose data_source is not LEGACY_SEED (e.g. MANUAL_ENTRY or IMPORT).
 */
public class LegacyEmployeeSeedService {
  private static final Logger log = LoggerFactory.getLogger(LegacyEmployeeSeedService.class);
  private static final long DEFAULT_TENANT_ID = 1L;
  private static final String SOURCE_LEGACY_SEED = "LEGACY_SEED";
  private static final TypeReference<List<JsonNode>> JSON_NODE_LIST = new TypeReference<>() {
  };

  private final EmployeeRepository employeeRepository;
  private final ObjectMapper objectMapper;
  private final boolean legacyEmployeeSeedEnabled;
  private final String legacyEmployeeSeedFile;

  public LegacyEmployeeSeedService(
      EmployeeRepository employeeRepository,
      ObjectMapper objectMapper,
      @Value("${app.seed.legacy-employee-enabled:false}") boolean legacyEmployeeSeedEnabled,
      @Value("${app.seed.legacy-employee-file:}") String legacyEmployeeSeedFile
  ) {
    this.employeeRepository = employeeRepository;
    this.objectMapper = objectMapper;
    this.legacyEmployeeSeedEnabled = legacyEmployeeSeedEnabled;
    this.legacyEmployeeSeedFile = legacyEmployeeSeedFile == null ? "" : legacyEmployeeSeedFile.trim();
  }

  // R1-03: renamed from run() to importSeed(); no longer auto-triggered by ApplicationRunner.
  public void importSeed() throws Exception {
    if (!legacyEmployeeSeedEnabled) {
      log.info("Legacy employee seed is DISABLED (production-safe). Set APP_SEED_LEGACY_EMPLOYEE_ENABLED=true to enable for demo/dev only.");
      return;
    }
    if (legacyEmployeeSeedFile.isBlank()) {
      log.error("Legacy employee seed was requested without APP_SEED_LEGACY_EMPLOYEE_FILE; no employee data was imported.");
      return;
    }
    Resource resource = new FileSystemResource(legacyEmployeeSeedFile);
    if (!resource.isReadable()) {
      log.error("Legacy employee seed file is not readable: {}", legacyEmployeeSeedFile);
      return;
    }
    String source = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    List<JsonNode> salaryRows = new ArrayList<>();
    salaryRows.addAll(readSeedArray(source, "SALARY_SEED"));
    salaryRows.addAll(readSeedArray(source, "SALARY_SEED_Q1"));
    Map<String, JsonNode> latestRows = new LinkedHashMap<>();
    for (JsonNode row : salaryRows) {
      String storeId = text(row, "sid");
      String name = text(row, "name");
      if (storeId.isBlank() || name.isBlank() || !employeeRepository.storeExists(DEFAULT_TENANT_ID, storeId)) {
        continue;
      }
      String key = storeId + "|" + name;
      JsonNode previous = latestRows.get(key);
      if (previous == null || text(row, "month").compareTo(text(previous, "month")) >= 0) {
        latestRows.put(key, row);
      }
    }
    for (JsonNode row : latestRows.values()) {
      seedEmployee(row);
    }
  }

  private void seedEmployee(JsonNode row) {
    String storeId = text(row, "sid");
    String name = text(row, "name");
    String position = text(row, "position");
    String remark = text(row, "vacationNote");
    employeeRepository.upsertSeed(
        DEFAULT_TENANT_ID,
        employeeId(DEFAULT_TENANT_ID, storeId, name),
        storeId,
        employeeRepository.storeName(DEFAULT_TENANT_ID, storeId).orElse(storeId),
        employeeRepository.brandName(DEFAULT_TENANT_ID, storeId).orElse(null),
        name,
        roleFromPosition(position),
        position,
        employmentTypeFromPosition(position),
        amount(row, "base"),
        statusFrom(position, remark),
        truncate(remark, 255),
        SOURCE_LEGACY_SEED
    );
  }

  private List<JsonNode> readSeedArray(String source, String constName) throws IOException {
    String token = "const " + constName + "=";
    int tokenStart = source.indexOf(token);
    if (tokenStart < 0) {
      return List.of();
    }
    int arrayStart = source.indexOf('[', tokenStart);
    int arrayEnd = findArrayEnd(source, arrayStart);
    if (arrayStart < 0 || arrayEnd < 0) {
      return List.of();
    }
    return objectMapper.readValue(source.substring(arrayStart, arrayEnd + 1), JSON_NODE_LIST);
  }

  private int findArrayEnd(String source, int arrayStart) {
    if (arrayStart < 0) {
      return -1;
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = arrayStart; i < source.length(); i++) {
      char ch = source.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (ch == '\\') {
          escaped = true;
        } else if (ch == '"') {
          inString = false;
        }
        continue;
      }
      if (ch == '"') {
        inString = true;
      } else if (ch == '[') {
        depth++;
      } else if (ch == ']') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private String employeeId(long tenantId, String storeId, String name) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] bytes = digest.digest((tenantId + "|" + storeId + "|" + name).getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder("emp-");
      for (int i = 0; i < 8; i++) {
        value.append(String.format("%02x", bytes[i]));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException ex) {
      return "emp-" + Math.abs((tenantId + "|" + storeId + "|" + name).hashCode());
    }
  }

  private String roleFromPosition(String position) {
    if (position == null || position.isBlank()) {
      return "员工";
    }
    if (position.contains("店长")) {
      return "店长";
    }
    if (position.contains("领班")) {
      return "领班";
    }
    if (position.contains("训练员")) {
      return "训练员";
    }
    if (position.contains("实习")) {
      return "实习";
    }
    if (position.contains("兼职") || position.contains("阿姨")) {
      return "兼职";
    }
    if (position.contains("营业员")) {
      return "营业员";
    }
    return position;
  }

  private String employmentTypeFromPosition(String position) {
    if (position == null) {
      return "全职";
    }
    if (position.contains("实习")) {
      return "实习";
    }
    if (position.contains("兼职") || position.contains("阿姨")) {
      return "兼职";
    }
    return "全职";
  }

  private String statusFrom(String position, String remark) {
    String source = (position == null ? "" : position) + " " + (remark == null ? "" : remark);
    return source.contains("已离职") ? "离职" : "在职";
  }

  private BigDecimal amount(JsonNode row, String field) {
    JsonNode value = row.get(field);
    if (value == null || value.isNull()) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return value.decimalValue().setScale(2, RoundingMode.HALF_UP);
  }

  private String text(JsonNode row, String field) {
    JsonNode value = row.get(field);
    return value == null || value.isNull() ? "" : value.asText("").trim();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
  }
}
