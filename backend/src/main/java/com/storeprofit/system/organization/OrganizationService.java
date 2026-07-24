package com.storeprofit.system.organization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.audit.AuditLogRequest;
import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.DataScopeService;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyService;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OrganizationService {
  private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Pattern MOBILE_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
  private static final Pattern LANDLINE_PHONE = Pattern.compile("^(?:0\\d{2,3}-?)?\\d{7,8}(?:-\\d{1,6})?$");
  private static final Pattern SERVICE_PHONE = Pattern.compile("^[48]00-?\\d{3}-?\\d{4}$");
  private final OrganizationRepository organizationRepository;
  private final DataScopeService dataScopeService;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;
  private final WarehouseTopologyService warehouseTopologyService;
  private final AuditRepository auditRepository;

  @Autowired
  public OrganizationService(
      OrganizationRepository organizationRepository,
      DataScopeService dataScopeService,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver,
      WarehouseTopologyService warehouseTopologyService,
      AuditRepository auditRepository
  ) {
    this.organizationRepository = organizationRepository;
    this.dataScopeService = dataScopeService;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
    this.warehouseTopologyService = warehouseTopologyService;
    this.auditRepository = auditRepository;
  }

  public OrganizationService(
      OrganizationRepository organizationRepository,
      DataScopeService dataScopeService,
      AccessControlService accessControl
  ) {
    this(organizationRepository, dataScopeService, accessControl, null, null, null);
  }

  public OrganizationService(OrganizationRepository organizationRepository) {
    this(organizationRepository, null, null, null, null, null);
  }

  public List<BrandResponse> brands(AuthUser user) {
    requireStoreRead(user);
    DataScope dataScope = resolvedStoreScope(user);
    return dataScope == null
        ? organizationRepository.brands(user.tenantId())
        : organizationRepository.brands(user.tenantId(), dataScope);
  }

  public List<StoreResponse> stores(AuthUser user) {
    requireStoreRead(user);
    DataScope dataScope = resolvedStoreScope(user);
    if (dataScope != null) {
      return organizationRepository.stores(user.tenantId(), dataScope);
    }
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null) {
      return organizationRepository.stores(
          user.tenantId(),
          new DataScope(DataScopeModes.OWN_STORE, List.of(user.storeId())));
    }
    return organizationRepository.stores(user.tenantId());
  }

  public List<StoreResponse> knowledgeBaseStores(AuthUser user) {
    if (accessControl == null) {
      throw new BusinessException(
          "AUTHORIZATION_SERVICE_UNAVAILABLE", "账号授权服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
    }
    accessControl.requireKnowledgeBaseManage(user);
    return organizationRepository.stores(
        user.tenantId(), accessControl.knowledgeBaseManagementStoreScope(user));
  }

  public StoreArchiveOptionsResponse storeOptions(AuthUser user) {
    requireStoreManage(user);
    DataScope dataScope = resolvedStoreScope(user);
    List<StoreArchiveOptionsResponse.CostAccountOption> costAccounts =
        organizationRepository.stores(user.tenantId(), dataScope).stream()
            .filter(store -> enabledStatus(store.status()))
            .map(store -> new StoreArchiveOptionsResponse.CostAccountOption(
                store.id(), store.code(), store.name(), store.status()))
            .toList();
    return new StoreArchiveOptionsResponse(
        organizationRepository.activeStoreRegions(user.tenantId()),
        organizationRepository.activeManagers(user.tenantId(), dataScope),
        List.of(
            new StoreArchiveOptionsResponse.StatusOption("营业中", "营业中", true),
            new StoreArchiveOptionsResponse.StatusOption("停用", "停用", false),
            new StoreArchiveOptionsResponse.StatusOption("停业", "停业", false)
        ),
        costAccounts
    );
  }

  @Transactional
  public void upsertStore(AuthUser user, StoreUpsertRequest input) {
    requireStoreManage(user);
    String requestedId = input == null ? null : blankToNull(input.id());
    if (requestedId != null && organizationRepository.store(user.tenantId(), requestedId).isPresent()) {
      StoreResponse existing = organizationRepository.store(user.tenantId(), requestedId).orElseThrow();
      StoreUpsertRequest versioned = input.version() == null
          ? withVersion(input, existing.version())
          : input;
      updateStore(user, versioned);
      return;
    }
    createStore(user, input);
  }

  @Transactional
  public StoreResponse createStore(AuthUser user, StoreUpsertRequest input) {
    return saveStore(user, input, true);
  }

  @Transactional
  public StoreResponse updateStore(AuthUser user, StoreUpsertRequest input) {
    return saveStore(user, input, false);
  }

  @Transactional
  public StoreResponse changeStoreStatus(
      AuthUser user,
      String storeId,
      StoreStatusChangeRequest request
  ) {
    requireStoreManage(user);
    String normalizedStoreId = normalizeStoreId(storeId);
    if (request == null || request.version() == null) {
      throw new BusinessException(
          "STORE_VERSION_REQUIRED", "门店档案版本缺失，请刷新后重试", HttpStatus.CONFLICT);
    }
    String status = normalizeRequiredStatus(request.status());
    StoreResponse existing = organizationRepository.store(user.tenantId(), normalizedStoreId)
        .orElseThrow(() -> new BusinessException(
            "STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.NOT_FOUND));
    if (businessScopeResolver != null) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, normalizedStoreId, existing.brandId(), "变更门店状态");
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(
          user, DataScopeDomains.STORE, normalizedStoreId, "变更门店状态");
    }
    if (enabledStatus(status)) {
      validateArchiveBeforeEnable(user, existing);
    }
    if (status.equals(existing.status())) {
      return existing;
    }
    if (organizationRepository.updateStoreStatus(
        user.tenantId(), normalizedStoreId, status, request.version()) == 0) {
      throw new BusinessException(
          "STORE_VERSION_CONFLICT", "门店档案已被其他人修改，请刷新后重试", HttpStatus.CONFLICT);
    }
    StoreResponse saved = organizationRepository.store(user.tenantId(), normalizedStoreId)
        .orElseThrow(() -> new BusinessException(
            "STORE_SAVE_FAILED", "门店状态保存失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR));
    auditStore(
        user,
        statusAction(existing, saved),
        normalizedStoreId,
        "门店经营状态由“" + existing.status() + "”变更为“" + saved.status() + "”",
        existing,
        saved
    );
    return saved;
  }

  private StoreResponse saveStore(AuthUser user, StoreUpsertRequest input, boolean creating) {
    requireStoreManage(user);
    StoreUpsertRequest request = normalizeStoreRequest(input, creating);
    StoreResponse existing = organizationRepository.store(user.tenantId(), request.id()).orElse(null);
    if (creating && existing != null) {
      throw new BusinessException(
          "STORE_ID_DUPLICATE", "门店档案已存在，请刷新列表后编辑", HttpStatus.CONFLICT);
    }
    if (!creating && existing == null) {
      throw new BusinessException(
          "STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.NOT_FOUND);
    }
    if (businessScopeResolver != null && existing != null) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, request.id(), request.brandId(), "维护门店档案");
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, request.id(), "维护门店档案");
    }
    if (organizationRepository.storeIdBelongsToOtherTenant(user.tenantId(), request.id())) {
      denyCrossTenantStoreAccess(user, request.id());
    }
    if (!organizationRepository.brandExists(user.tenantId(), request.brandId())) {
      throw new BusinessException("BRAND_NOT_FOUND", "品牌不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    OrganizationRepository.ManagerReference manager =
        organizationRepository.manager(user.tenantId(), request.managerEmployeeId())
            .orElseThrow(() -> new BusinessException(
                "STORE_MANAGER_NOT_FOUND",
                "负责人不存在、不属于当前企业或已被删除",
                HttpStatus.BAD_REQUEST
            ));
    if (!activeEmployeeStatus(manager.status())) {
      throw new BusinessException(
          "STORE_MANAGER_INACTIVE", "所选负责人已停用或离职，请重新选择", HttpStatus.CONFLICT);
    }
    requireRelatedStoreAccess(user, manager.storeId(), "选择门店负责人");

    if (!request.id().equals(request.costAccountStoreId())) {
      StoreResponse costAccount = organizationRepository.store(user.tenantId(), request.costAccountStoreId())
          .orElseThrow(() -> new BusinessException(
              "STORE_COST_ACCOUNT_NOT_FOUND",
              "成本账归属不存在或不属于当前企业",
              HttpStatus.BAD_REQUEST
          ));
      if (!enabledStatus(costAccount.status())) {
        throw new BusinessException(
            "STORE_COST_ACCOUNT_INACTIVE", "所选成本账归属已停用，请重新选择", HttpStatus.CONFLICT);
      }
      requireRelatedStoreAccess(user, costAccount.id(), "选择成本账归属");
    }
    if (organizationRepository.storeCodeBelongsToAnotherStore(
        user.tenantId(), request.code(), request.id())) {
      throw duplicateStoreCodeException();
    }
    if (organizationRepository.storeNameBelongsToAnotherStore(
        user.tenantId(), request.name(), request.id())) {
      throw new BusinessException(
          "STORE_NAME_DUPLICATE", "门店名称已存在，请更换后再保存", HttpStatus.CONFLICT);
    }
    if (request.supplyWarehouseId() != null) {
      throw new BusinessException(
          "SUPPLY_WAREHOUSE_READ_ONLY", "供货仓由门店区域自动确定，不能手工指定", HttpStatus.BAD_REQUEST);
    }
    String regionCode = request.regionCode();
    FacilityRow supplyWarehouse = null;
    if (warehouseTopologyService != null && regionCode != null && !regionCode.isBlank()) {
      regionCode = warehouseTopologyService.normalizeRegion(regionCode);
      supplyWarehouse = warehouseTopologyService.resolveSupplyWarehouse(user.tenantId(), regionCode);
    }
    if (warehouseTopologyService != null && enabledStatus(request.status()) && (regionCode == null || supplyWarehouse == null)) {
      throw new BusinessException(
          "STORE_SUPPLY_WAREHOUSE_REQUIRED", "启用门店前必须明确选择荆州或山东区域并绑定供货仓", HttpStatus.CONFLICT);
    }
    StoreUpsertRequest normalized = new StoreUpsertRequest(
        request.id(), request.code(), request.name(), request.brandId(),
        supplyWarehouse == null ? request.area() : supplyWarehouse.name(),
        manager.name(), request.managerPhone(), request.openDate(), request.status(), request.note(),
        regionCode, null, manager.employeeId(), request.costAccountStoreId(), request.version());
    try {
      if (creating) {
        organizationRepository.insertStore(
            user.tenantId(), normalized, supplyWarehouse == null ? null : supplyWarehouse.id());
      } else {
        if (normalized.version() == null) {
          throw new BusinessException(
              "STORE_VERSION_REQUIRED", "门店档案版本缺失，请刷新后重试", HttpStatus.CONFLICT);
        }
        int updated = organizationRepository.updateStore(
            user.tenantId(),
            normalized,
            supplyWarehouse == null ? null : supplyWarehouse.id(),
            normalized.version()
        );
        if (updated == 0) {
          throw new BusinessException(
              "STORE_VERSION_CONFLICT", "门店档案已被其他人修改，请刷新后重试", HttpStatus.CONFLICT);
        }
      }
    } catch (DataIntegrityViolationException exception) {
      if (organizationRepository.storeCodeBelongsToAnotherStore(
          user.tenantId(), request.code(), request.id())) {
        throw duplicateStoreCodeException();
      }
      if (organizationRepository.storeNameBelongsToAnotherStore(
          user.tenantId(), request.name(), request.id())) {
        throw new BusinessException(
            "STORE_NAME_DUPLICATE", "门店名称已存在，请更换后再保存", HttpStatus.CONFLICT);
      }
      throw exception;
    }
    StoreResponse saved = organizationRepository.store(user.tenantId(), request.id())
        .orElseThrow(() -> new BusinessException(
            "STORE_SAVE_FAILED", "门店档案保存失败，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR));
    String action = creating
        ? "新增门店档案"
        : statusAction(existing, saved);
    auditStore(
        user,
        action,
        request.id(),
        "门店档案已保存，状态：" + saved.status(),
        existing,
        saved
    );
    return saved;
  }

  @Transactional
  public void deleteStore(AuthUser user, String storeId) {
    requireStoreManage(user);
    String normalizedStoreId = normalizeStoreId(storeId);
    StoreResponse existing = organizationRepository.store(user.tenantId(), normalizedStoreId)
        .orElseThrow(() -> new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.NOT_FOUND));
    if (businessScopeResolver != null) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, normalizedStoreId, existing.brandId(), "删除门店档案");
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, normalizedStoreId, "删除门店档案");
    }
    throw new BusinessException(
        "STORE_DELETE_DISABLED",
        "门店档案不支持物理删除，请停用门店；历史经营、财务、库存和业务单据将继续保留。",
        HttpStatus.CONFLICT
    );
  }

  private boolean enabledStatus(String status) {
    String value = status == null || status.isBlank() ? "营业中" : status.trim();
    return "营业中".equals(value) || "ACTIVE".equalsIgnoreCase(value);
  }

  private StoreUpsertRequest normalizeStoreRequest(StoreUpsertRequest request, boolean creating) {
    if (request == null) {
      throw new BusinessException("STORE_REQUEST_REQUIRED", "请填写门店档案", HttpStatus.BAD_REQUEST);
    }
    String requestedId = blankToNull(request.id());
    String storeId = requestedId == null && creating
        ? "store-" + UUID.randomUUID()
        : normalizeStoreId(requestedId);
    String name = request.name() == null ? "" : request.name().trim();
    if (name.isBlank()) {
      throw new BusinessException("STORE_NAME_REQUIRED", "请填写门店名称", HttpStatus.BAD_REQUEST);
    }
    if (request.brandId() == null || request.brandId() <= 0) {
      throw new BusinessException("STORE_BRAND_REQUIRED", "请选择品牌", HttpStatus.BAD_REQUEST);
    }
    String code = request.code() == null ? "" : request.code().trim();
    if (code.isBlank()) {
      throw new BusinessException("STORE_CODE_REQUIRED", "请填写门店编号", HttpStatus.BAD_REQUEST);
    }
    String managerEmployeeId = blankToNull(request.managerEmployeeId());
    if (managerEmployeeId == null) {
      throw new BusinessException("STORE_MANAGER_REQUIRED", "请选择负责人", HttpStatus.BAD_REQUEST);
    }
    String managerPhone = request.managerPhone() == null ? "" : request.managerPhone().trim();
    if (managerPhone.isBlank()) {
      throw new BusinessException("STORE_CONTACT_REQUIRED", "请填写联系方式", HttpStatus.BAD_REQUEST);
    }
    if (!validContact(managerPhone)) {
      throw new BusinessException(
          "STORE_CONTACT_INVALID", "联系方式格式不正确，请填写手机号或合法联系电话", HttpStatus.BAD_REQUEST);
    }
    String status = normalizeRequiredStatus(request.status());
    String regionCode = blankToNull(request.regionCode());
    if (regionCode == null) {
      throw new BusinessException("STORE_REGION_REQUIRED", "请选择所属区域", HttpStatus.BAD_REQUEST);
    }
    String costAccountStoreId = blankToNull(request.costAccountStoreId());
    if (costAccountStoreId == null) {
      throw new BusinessException("STORE_COST_ACCOUNT_REQUIRED", "请选择成本账归属", HttpStatus.BAD_REQUEST);
    }
    if ("SELF".equalsIgnoreCase(costAccountStoreId)) {
      costAccountStoreId = storeId;
    }
    return new StoreUpsertRequest(
        storeId,
        code,
        name,
        request.brandId(),
        request.area(),
        request.manager(),
        managerPhone,
        request.openDate(),
        status,
        request.note(),
        regionCode,
        request.supplyWarehouseId(),
        managerEmployeeId,
        costAccountStoreId,
        request.version()
    );
  }

  private boolean validContact(String value) {
    String normalized = value == null ? "" : value.replace(" ", "").trim();
    return MOBILE_PHONE.matcher(normalized).matches()
        || LANDLINE_PHONE.matcher(normalized).matches()
        || SERVICE_PHONE.matcher(normalized).matches();
  }

  private boolean activeEmployeeStatus(String status) {
    return "在职".equals(status) || "ACTIVE".equalsIgnoreCase(String.valueOf(status));
  }

  private String normalizeRequiredStatus(String value) {
    String status = blankToNull(value);
    if (status == null) {
      throw new BusinessException("STORE_STATUS_REQUIRED", "请选择经营状态", HttpStatus.BAD_REQUEST);
    }
    if (!("营业中".equals(status) || "停用".equals(status) || "停业".equals(status))) {
      throw new BusinessException(
          "STORE_STATUS_INVALID", "门店状态仅支持营业中、停用或停业", HttpStatus.BAD_REQUEST);
    }
    return status;
  }

  private void validateArchiveBeforeEnable(AuthUser user, StoreResponse store) {
    String managerEmployeeId = blankToNull(store.managerEmployeeId());
    String costAccountStoreId = blankToNull(store.costAccountStoreId());
    if (managerEmployeeId == null
        || costAccountStoreId == null
        || blankToNull(store.regionCode()) == null
        || store.supplyWarehouseId() == null
        || blankToNull(store.managerPhone()) == null) {
      throw new BusinessException(
          "STORE_ARCHIVE_INCOMPLETE",
          "重新启用前请先完善所属区域、负责人、联系方式和成本账归属",
          HttpStatus.CONFLICT
      );
    }
    if (!validContact(store.managerPhone())) {
      throw new BusinessException(
          "STORE_CONTACT_INVALID", "联系方式格式不正确，请先完善门店档案", HttpStatus.BAD_REQUEST);
    }
    OrganizationRepository.ManagerReference manager =
        organizationRepository.manager(user.tenantId(), managerEmployeeId)
            .orElseThrow(() -> new BusinessException(
                "STORE_MANAGER_NOT_FOUND", "负责人不存在或不属于当前企业，请先完善门店档案",
                HttpStatus.BAD_REQUEST));
    if (!activeEmployeeStatus(manager.status())) {
      throw new BusinessException(
          "STORE_MANAGER_INACTIVE", "所选负责人已停用或离职，请先重新选择", HttpStatus.CONFLICT);
    }
    requireRelatedStoreAccess(user, manager.storeId(), "重新启用门店");
    if (!store.id().equals(costAccountStoreId)) {
      StoreResponse costAccount = organizationRepository.store(user.tenantId(), costAccountStoreId)
          .orElseThrow(() -> new BusinessException(
              "STORE_COST_ACCOUNT_NOT_FOUND", "成本账归属不存在，请先完善门店档案",
              HttpStatus.BAD_REQUEST));
      if (!enabledStatus(costAccount.status())) {
        throw new BusinessException(
            "STORE_COST_ACCOUNT_INACTIVE", "成本账归属已停用，请先重新选择", HttpStatus.CONFLICT);
      }
      requireRelatedStoreAccess(user, costAccount.id(), "重新启用门店");
    }
    if (warehouseTopologyService != null) {
      FacilityRow supply = warehouseTopologyService.resolveSupplyWarehouse(
          user.tenantId(), warehouseTopologyService.normalizeRegion(store.regionCode()));
      if (supply.id() != store.supplyWarehouseId()) {
        throw new BusinessException(
            "STORE_SUPPLY_WAREHOUSE_CHANGED",
            "所属区域的供货仓已变化，请先重新保存门店档案",
            HttpStatus.CONFLICT
        );
      }
    }
  }

  private void requireRelatedStoreAccess(AuthUser user, String storeId, String action) {
    if (businessScopeResolver != null) {
      businessScopeResolver.resolve(user, DataScopeDomains.STORE, storeId, null, action);
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, storeId, action);
    }
  }

  private StoreUpsertRequest withVersion(StoreUpsertRequest request, long version) {
    return new StoreUpsertRequest(
        request.id(), request.code(), request.name(), request.brandId(), request.area(),
        request.manager(), request.managerPhone(), request.openDate(), request.status(), request.note(),
        request.regionCode(), request.supplyWarehouseId(), request.managerEmployeeId(),
        request.costAccountStoreId(), version);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String statusAction(StoreResponse before, StoreResponse after) {
    if (before != null && enabledStatus(before.status()) && !enabledStatus(after.status())) {
      return "停用门店档案";
    }
    if (before != null && !enabledStatus(before.status()) && enabledStatus(after.status())) {
      return "重新启用门店档案";
    }
    return "保存门店档案";
  }

  private BusinessException duplicateStoreCodeException() {
    return new BusinessException("STORE_CODE_DUPLICATE", "门店编号已存在，请更换后再保存", HttpStatus.CONFLICT);
  }

  private void denyCrossTenantStoreAccess(AuthUser user, String storeId) {
    if (auditRepository != null && user != null) {
      try {
        auditRepository.writePermissionDenied(
            user,
            "维护门店档案",
            "API",
            storeId,
            storeId,
            "门店不属于当前企业"
        );
      } catch (RuntimeException exception) {
        log.warn("Failed to audit cross-tenant store denial for user {}: {}", user.id(), exception.getMessage());
      }
    }
    throw new BusinessException("FORBIDDEN", "当前账号没有访问该门店档案的权限", HttpStatus.FORBIDDEN);
  }

  private String normalizeStoreId(String storeId) {
    if (storeId == null || storeId.isBlank()) {
      throw new BusinessException("STORE_ID_REQUIRED", "请选择门店", HttpStatus.BAD_REQUEST);
    }
    return storeId.trim();
  }

  private void auditStore(
      AuthUser user,
      String action,
      String storeId,
      String reason,
      StoreResponse before,
      StoreResponse after
  ) {
    if (auditRepository == null) {
      return;
    }
    auditRepository.writeLog(user, new AuditLogRequest(
        action,
        "store_branch",
        storeId,
        storeId,
        null,
        reason,
        json(before),
        json(after)
    ));
  }

  private String json(StoreResponse value) {
    if (value == null) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("门店审计快照序列化失败", exception);
    }
  }

  private void requireStoreRead(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireStoreRead(user);
    }
  }

  private void requireStoreManage(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireStoreManage(user);
      return;
    }
    if (!AccessControlService.isBoss(user)) {
      throw new BusinessException("FORBIDDEN", "当前账号无权维护门店档案", HttpStatus.FORBIDDEN);
    }
  }

  private DataScope storeScope(AuthUser user) {
    return dataScopeService == null ? null : dataScopeService.scope(user, DataScopeDomains.STORE);
  }

  private DataScope resolvedStoreScope(AuthUser user) {
    if (businessScopeResolver != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()))) {
      BusinessScope scope = businessScopeResolver.sessionScope(user);
      return scope.dataScope();
    }
    return storeScope(user);
  }
}
