package com.storeprofit.system.platform.authorization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthRepository;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeRepository.DataScopeAssignmentRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DataScopeService {
  private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  /** Supervisor owns the former operations workspace, only for explicitly assigned scope. */
  private static final Set<String> SUPERVISOR_SCOPE_DOMAINS = Set.of(
      DataScopeDomains.STORE,
      DataScopeDomains.INSPECTION,
      DataScopeDomains.EXAM,
      DataScopeDomains.PLATFORM
  );

  private final DataScopeRepository repository;
  private final AuthRepository authRepository;
  private final ObjectMapper objectMapper;

  public DataScopeService(
      DataScopeRepository repository,
      AuthRepository authRepository,
      ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.authRepository = authRepository;
    this.objectMapper = objectMapper;
  }

  public Map<String, DataScope> dataScopes(AuthUser user) {
    LinkedHashMap<String, DataScope> result = emptyScopeMap();
    if (user == null) {
      return Map.copyOf(result);
    }
    if (AccessControlService.hasAllStoreScope(user)) {
      result.replaceAll((domain, ignored) -> DataScope.all());
      return Map.copyOf(result);
    }

    List<DataScopeAssignmentRow> rows = repository.assignmentsForUser(user.tenantId(), user.id());
    if (rows.isEmpty()) {
      applyConservativeCompatibilityFallback(result, user);
      return Map.copyOf(result);
    }
    String role = AccessControlService.canonicalRole(user.role());
    boolean storeManager = "STORE_MANAGER".equals(role);
    boolean supervisor = "SUPERVISOR".equals(role);
    for (DataScopeAssignmentRow row : rows) {
      String domain = normalizeDomain(row.domainCode());
      if (!DataScopeDomains.ALL.contains(domain)) {
        continue;
      }
      String mode = normalizeMode(row.scopeType());
      if (supervisor && (!SUPERVISOR_SCOPE_DOMAINS.contains(domain)
          || !DataScopeModes.STORE_LIST.equals(mode))) {
        result.put(domain, DataScope.none());
        continue;
      }
      if (DataScopeModes.WAREHOUSE_LIST.equals(mode)
          && !DataScopeDomains.WAREHOUSE.equals(domain)) {
        log.warn("Ignoring WAREHOUSE_LIST outside WAREHOUSE domain. tenantId={} userId={} domain={}",
            user.tenantId(), user.id(), domain);
        result.put(domain, DataScope.none());
        continue;
      }
      if (storeManager && !DataScopeModes.OWN_STORE.equals(mode)) {
        log.warn(
            "Ignoring non-OWN_STORE legacy data scope for store manager. tenantId={} userId={} domain={}",
            user.tenantId(), user.id(), domain);
        result.put(domain, DataScope.none());
        continue;
      }
      result.put(domain, materialize(mode, row.scopeValueJson(), user.storeId()));
    }
    return Map.copyOf(result);
  }

  public DataScope scope(AuthUser user, String domainCode) {
    return dataScopes(user).getOrDefault(normalizeDomain(domainCode), DataScope.none());
  }

  /**
   * Returns the persisted scope for a domain without applying role-wide compatibility shortcuts.
   * Knowledge-base publishing uses this stricter view so a supervisor with an explicit store list
   * cannot select or manage documents for stores outside that configured list.
   */
  public DataScope configuredScope(AuthUser user, String domainCode) {
    String domain = normalizeDomain(domainCode);
    if (user == null || !DataScopeDomains.ALL.contains(domain)) {
      return DataScope.none();
    }
    if (AccessControlService.isBoss(user)) {
      return DataScope.all();
    }
    List<DataScopeAssignmentRow> rows = repository.assignmentsForUser(user.tenantId(), user.id());
    for (DataScopeAssignmentRow row : rows) {
      if (domain.equals(normalizeDomain(row.domainCode()))) {
        String mode = normalizeMode(row.scopeType());
        if ("SUPERVISOR".equals(AccessControlService.canonicalRole(user.role()))
            && (!SUPERVISOR_SCOPE_DOMAINS.contains(domain)
            || !Set.of(DataScopeModes.STORE_LIST, DataScopeModes.NONE).contains(mode))) {
          return DataScope.none();
        }
        return materialize(mode, row.scopeValueJson(), user.storeId());
      }
    }
    if (!rows.isEmpty()) {
      return DataScope.none();
    }
    LinkedHashMap<String, DataScope> fallback = emptyScopeMap();
    applyConservativeCompatibilityFallback(fallback, user);
    return fallback.getOrDefault(domain, DataScope.none());
  }

  public boolean hasAllDataScope(AuthUser user, String domainCode) {
    return scope(user, domainCode).allowsAllStores();
  }

  public Set<String> allowedStoreIds(AuthUser user, String domainCode) {
    DataScope scope = scope(user, domainCode);
    if (scope.allowsAllStores()) {
      return Set.of("all");
    }
    if (DataScopeModes.WAREHOUSE_LIST.equals(scope.mode())) {
      return Set.of();
    }
    return Set.copyOf(scope.storeIds());
  }

  public Set<String> allowedWarehouseIds(AuthUser user) {
    DataScope scope = scope(user, DataScopeDomains.WAREHOUSE);
    if (DataScopeModes.ALL.equals(scope.mode())) {
      return Set.of("all");
    }
    return Set.copyOf(scope.warehouseIds());
  }

  public boolean canAccessStore(AuthUser user, String domainCode, String storeId) {
    return scope(user, domainCode).allowsStore(storeId);
  }

  public boolean canAccessWarehouse(AuthUser user, String warehouseId) {
    return scope(user, DataScopeDomains.WAREHOUSE).allowsWarehouse(warehouseId);
  }

  public Set<String> enabledWarehouseIds(long tenantId) {
    return Set.copyOf(repository.enabledWarehouseIds(tenantId));
  }

  public List<DataScopeAssignment> assignmentsForUser(long tenantId, long userId) {
    return repository.assignmentsForUser(tenantId, userId).stream()
        .map(row -> {
          String mode = normalizeMode(row.scopeType());
          List<String> ids = parseStoreIds(row.scopeValueJson());
          return new DataScopeAssignment(
              normalizeDomain(row.domainCode()),
              mode,
              ids,
              DataScopeModes.WAREHOUSE_LIST.equals(mode) ? ids : List.of()
          );
        })
        .toList();
  }

  public void replaceAssignments(
      long tenantId,
      long userId,
      List<DataScopeAssignment> assignments,
      Long actorId
  ) {
    List<DataScopeAssignmentRow> rows = normalizeAssignments(assignments).stream()
        .map(assignment -> new DataScopeAssignmentRow(
            assignment.domainCode(),
            assignment.mode(),
            DataScopeModes.STORE_LIST.equals(assignment.mode())
                ? writeStoreIds(assignment.storeIds())
                : DataScopeModes.WAREHOUSE_LIST.equals(assignment.mode())
                    ? writeStoreIds(assignment.warehouseIds())
                : null
        ))
        .toList();
    repository.replaceAssignments(tenantId, userId, rows, actorId);
  }

  private LinkedHashMap<String, DataScope> emptyScopeMap() {
    LinkedHashMap<String, DataScope> result = new LinkedHashMap<>();
    DataScopeDomains.ALL.stream().sorted().forEach(domain -> result.put(domain, DataScope.none()));
    return result;
  }

  private DataScope materialize(String rawMode, String scopeValueJson, String ownStoreId) {
    String mode = normalizeMode(rawMode);
    if (DataScopeModes.ALL.equals(mode)) {
      return DataScope.all();
    }
    if (DataScopeModes.OWN_STORE.equals(mode)) {
      return ownStoreId == null || ownStoreId.isBlank()
          ? DataScope.none()
          : new DataScope(mode, List.of(ownStoreId));
    }
    if (DataScopeModes.STORE_LIST.equals(mode)) {
      return new DataScope(mode, parseStoreIds(scopeValueJson));
    }
    if (DataScopeModes.WAREHOUSE_LIST.equals(mode)) {
      List<String> warehouseIds = parseStoreIds(scopeValueJson);
      return new DataScope(mode, warehouseIds, warehouseIds);
    }
    return new DataScope(mode, List.of());
  }

  private void applyConservativeCompatibilityFallback(Map<String, DataScope> result, AuthUser user) {
    String role = AccessControlService.canonicalRole(user.role());
    if ("EMPLOYEE".equals(role)) {
      result.put(DataScopeDomains.EXAM, new DataScope(DataScopeModes.SELF, List.of()));
      return;
    }
    if ("WAREHOUSE".equals(role)) {
      result.put(DataScopeDomains.WAREHOUSE,
          new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, List.of()));
      return;
    }
    if ("STORE_MANAGER".equals(role)) {
      for (String domain : List.of(
          DataScopeDomains.STORE,
          DataScopeDomains.FINANCE,
          DataScopeDomains.SALARY,
          DataScopeDomains.WAREHOUSE,
          DataScopeDomains.INSPECTION,
          DataScopeDomains.EXAM)) {
        result.put(domain, materialize(DataScopeModes.OWN_STORE, null, user.storeId()));
      }
      return;
    }
    List<String> assignedStores = authRepository.assignedStoreScope(user.tenantId(), user.id());
    if (assignedStores.isEmpty() && user.storeId() != null && !user.storeId().isBlank()) {
      assignedStores = List.of(user.storeId());
    }
    if (assignedStores.isEmpty()) {
      return;
    }
    DataScope scoped = new DataScope(DataScopeModes.STORE_LIST, assignedStores);
    if ("SUPERVISOR".equals(role)) {
      for (String domain : SUPERVISOR_SCOPE_DOMAINS) {
        result.put(domain, scoped);
      }
      return;
    }
    if ("FINANCE".equals(role)) {
      for (String domain : List.of(
          DataScopeDomains.STORE,
          DataScopeDomains.FINANCE,
          DataScopeDomains.SALARY,
          DataScopeDomains.WAREHOUSE)) {
        result.put(domain, scoped);
      }
    }
  }

  private List<DataScopeAssignment> normalizeAssignments(List<DataScopeAssignment> assignments) {
    if (assignments == null || assignments.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> seenDomains = new LinkedHashSet<>();
    List<DataScopeAssignment> normalized = new ArrayList<>();
    for (DataScopeAssignment assignment : assignments) {
      if (assignment == null) {
        continue;
      }
      String domain = normalizeDomain(assignment.domainCode());
      String mode = assignment.mode() == null ? "" : assignment.mode().trim().toUpperCase();
      if (!DataScopeDomains.ALL.contains(domain)) {
        throw new BusinessException("DATA_SCOPE_DOMAIN_INVALID", "数据范围业务域不正确", HttpStatus.BAD_REQUEST);
      }
      if (!DataScopeModes.ALL_MODES.contains(mode)) {
        throw new BusinessException("DATA_SCOPE_MODE_INVALID", "数据范围类型不正确", HttpStatus.BAD_REQUEST);
      }
      if (!seenDomains.add(domain)) {
        throw new BusinessException("DATA_SCOPE_DUPLICATE", "同一业务域不能重复配置", HttpStatus.BAD_REQUEST);
      }
      List<String> storeIds = DataScopeModes.STORE_LIST.equals(mode) ? assignment.storeIds() : List.of();
      List<String> warehouseIds = DataScopeModes.WAREHOUSE_LIST.equals(mode)
          ? assignment.warehouseIds()
          : List.of();
      if (DataScopeModes.STORE_LIST.equals(mode) && storeIds.isEmpty()) {
        throw new BusinessException("DATA_SCOPE_STORE_REQUIRED", "指定门店范围不能为空", HttpStatus.BAD_REQUEST);
      }
      if (DataScopeModes.WAREHOUSE_LIST.equals(mode)) {
        if (!DataScopeDomains.WAREHOUSE.equals(domain)) {
          throw new BusinessException(
              "DATA_SCOPE_MODE_DOMAIN_MISMATCH", "指定仓库范围只能用于仓库业务域", HttpStatus.BAD_REQUEST);
        }
        if (warehouseIds.isEmpty()) {
          throw new BusinessException(
              "DATA_SCOPE_WAREHOUSE_REQUIRED", "指定仓库范围不能为空", HttpStatus.BAD_REQUEST);
        }
      }
      normalized.add(new DataScopeAssignment(domain, mode, storeIds, warehouseIds));
    }
    return List.copyOf(normalized);
  }

  private List<String> parseStoreIds(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      List<String> parsed = objectMapper.readValue(value, STRING_LIST);
      return new DataScope(DataScopeModes.STORE_LIST, parsed).storeIds();
    } catch (Exception ex) {
      log.warn("Invalid user_data_scope scope_value_json; defaulting to NONE: {}", ex.getMessage());
      return List.of();
    }
  }

  private String writeStoreIds(List<String> storeIds) {
    try {
      return objectMapper.writeValueAsString(storeIds == null ? List.of() : storeIds);
    } catch (Exception ex) {
      throw new BusinessException("DATA_SCOPE_SERIALIZE_FAILED", "数据范围保存失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private String normalizeDomain(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private String normalizeMode(String value) {
    String mode = value == null ? "" : value.trim().toUpperCase();
    return DataScopeModes.ALL_MODES.contains(mode) ? mode : DataScopeModes.NONE;
  }
}
