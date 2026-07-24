package com.storeprofit.system.organization;

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

  @Transactional
  public void upsertStore(AuthUser user, StoreUpsertRequest input) {
    requireStoreManage(user);
    StoreUpsertRequest request = normalizeStoreRequest(input);
    StoreResponse existing = organizationRepository.store(user.tenantId(), request.id()).orElse(null);
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
    if (organizationRepository.storeCodeBelongsToAnotherStore(
        user.tenantId(), request.code(), request.id())) {
      throw duplicateStoreCodeException();
    }
    if (request.supplyWarehouseId() != null) {
      throw new BusinessException(
          "SUPPLY_WAREHOUSE_READ_ONLY", "供货仓由门店区域自动确定，不能手工指定", HttpStatus.BAD_REQUEST);
    }
    String regionCode = request.regionCode();
    if (warehouseTopologyService != null && (regionCode == null || regionCode.isBlank()) && existing != null) {
      regionCode = existing.regionCode();
    }
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
        request.id(), request.code(), request.name(), request.brandId(), request.area(),
        request.manager(), request.managerPhone(), request.openDate(), request.status(), request.note(), regionCode, null);
    try {
      if (warehouseTopologyService == null) {
        organizationRepository.upsertStore(user.tenantId(), normalized);
      } else {
        organizationRepository.upsertStore(
            user.tenantId(), normalized, supplyWarehouse == null ? null : supplyWarehouse.id());
      }
    } catch (DataIntegrityViolationException exception) {
      if (organizationRepository.storeCodeBelongsToAnotherStore(
          user.tenantId(), request.code(), request.id())) {
        throw duplicateStoreCodeException();
      }
      throw exception;
    }
    auditStore(
        user,
        existing == null ? "新增门店档案" : "保存门店档案",
        request.id(),
        "门店档案已保存，状态：" + (request.status() == null || request.status().isBlank() ? "营业中" : request.status().trim())
    );
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
    if (organizationRepository.storeHasLinkedData(user.tenantId(), normalizedStoreId)) {
      throw storeHasLinkedDataException();
    }
    try {
      int deleted = organizationRepository.deleteStore(user.tenantId(), normalizedStoreId);
      if (deleted == 0) {
        throw new BusinessException("STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.NOT_FOUND);
      }
    } catch (DataIntegrityViolationException exception) {
      throw storeHasLinkedDataException();
    }
    auditStore(user, "删除门店档案", normalizedStoreId, "已删除未产生业务关联的门店档案");
  }

  private boolean enabledStatus(String status) {
    String value = status == null || status.isBlank() ? "营业中" : status.trim();
    return "营业中".equals(value) || "ACTIVE".equalsIgnoreCase(value);
  }

  private StoreUpsertRequest normalizeStoreRequest(StoreUpsertRequest request) {
    if (request == null) {
      throw new BusinessException("STORE_REQUEST_REQUIRED", "请填写门店档案", HttpStatus.BAD_REQUEST);
    }
    String storeId = normalizeStoreId(request.id());
    String name = request.name() == null ? "" : request.name().trim();
    if (name.isBlank()) {
      throw new BusinessException("STORE_NAME_REQUIRED", "请填写门店名称", HttpStatus.BAD_REQUEST);
    }
    if (request.brandId() == null || request.brandId() <= 0) {
      throw new BusinessException("STORE_BRAND_REQUIRED", "请选择品牌", HttpStatus.BAD_REQUEST);
    }
    String status = request.status() == null || request.status().isBlank() ? "营业中" : request.status().trim();
    if (!("营业中".equals(status) || "停用".equals(status) || "停业".equals(status)
        || "ACTIVE".equalsIgnoreCase(status))) {
      throw new BusinessException("STORE_STATUS_INVALID", "门店状态仅支持营业中、停用或停业", HttpStatus.BAD_REQUEST);
    }
    return new StoreUpsertRequest(
        storeId,
        request.code() == null ? null : request.code().trim(),
        name,
        request.brandId(),
        request.area(),
        request.manager(),
        request.managerPhone(),
        request.openDate(),
        status,
        request.note(),
        request.regionCode(),
        request.supplyWarehouseId()
    );
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

  private BusinessException storeHasLinkedDataException() {
    return new BusinessException(
        "STORE_HAS_LINKED_DATA",
        "该门店已有经营、仓库、工资、报销、巡店或账号关联数据，不能删除；请改为停用门店。",
        HttpStatus.CONFLICT
    );
  }

  private void auditStore(AuthUser user, String action, String storeId, String reason) {
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
        null,
        null
    ));
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
