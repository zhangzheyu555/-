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

@Service
public class OrganizationService {
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
  public void upsertStore(AuthUser user, StoreUpsertRequest request) {
    requireStoreManage(user);
    StoreResponse existing = organizationRepository.store(user.tenantId(), request.id()).orElse(null);
    if (businessScopeResolver != null && existing != null) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.STORE, request.id(), request.brandId(), "维护门店档案");
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.STORE, request.id(), "维护门店档案");
    }
    if (organizationRepository.storeIdBelongsToOtherTenant(user.tenantId(), request.id())) {
      throw new BusinessException("STORE_CONFLICT", "门店ID已被其他企业使用", HttpStatus.CONFLICT);
    }
    if (!organizationRepository.brandExists(user.tenantId(), request.brandId())) {
      throw new BusinessException("BRAND_NOT_FOUND", "品牌不存在或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    if (request.supplyWarehouseId() != null) {
      throw new BusinessException(
          "SUPPLY_WAREHOUSE_READ_ONLY", "供货仓由门店区域自动确定，不能手工指定", HttpStatus.BAD_REQUEST);
    }
    // The short constructors exist only for focused legacy unit tests. The Spring-managed
    // production service always has WarehouseTopologyService and therefore always executes
    // the explicit region/supply-warehouse validation below.
    if (warehouseTopologyService == null) {
      organizationRepository.upsertStore(user.tenantId(), request);
      return;
    }
    String regionCode = request.regionCode();
    if ((regionCode == null || regionCode.isBlank()) && existing != null) {
      regionCode = existing.regionCode();
    }
    FacilityRow supplyWarehouse = null;
    if (regionCode != null && !regionCode.isBlank()) {
      regionCode = warehouseTopologyService.normalizeRegion(regionCode);
      supplyWarehouse = warehouseTopologyService.resolveSupplyWarehouse(user.tenantId(), regionCode);
    }
    if (enabledStatus(request.status()) && (regionCode == null || supplyWarehouse == null)) {
      throw new BusinessException(
          "STORE_SUPPLY_WAREHOUSE_REQUIRED", "启用门店前必须明确选择荆州或山东区域并绑定供货仓", HttpStatus.CONFLICT);
    }
    StoreUpsertRequest normalized = new StoreUpsertRequest(
        request.id(), request.code(), request.name(), request.brandId(), request.area(),
        request.manager(), request.openDate(), request.status(), request.note(), regionCode, null);
    organizationRepository.upsertStore(
        user.tenantId(), normalized, supplyWarehouse == null ? null : supplyWarehouse.id());
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
