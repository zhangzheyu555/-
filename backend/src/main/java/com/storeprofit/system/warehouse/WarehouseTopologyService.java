package com.storeprofit.system.warehouse;

import com.storeprofit.system.audit.AuditRepository;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.StoreSupplyRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Enforces operation permission + warehouse scope + topology as one authorization boundary. */
@Service
public class WarehouseTopologyService {
  private static final Logger log = LoggerFactory.getLogger(WarehouseTopologyService.class);

  private final WarehouseTopologyRepository repository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;
  private final AuditRepository auditRepository;

  public WarehouseTopologyService(
      WarehouseTopologyRepository repository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver,
      AuditRepository auditRepository
  ) {
    this.repository = repository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
    this.auditRepository = auditRepository;
  }

  public List<WarehouseFacilityResponse> visibleFacilities(AuthUser user) {
    requireReadPermission(user);
    List<FacilityRow> all = repository.facilities(user.tenantId());
    Set<String> allowed = allowedFacilityIds(user);
    return all.stream()
        .filter(FacilityRow::enabled)
        .filter(row -> allowed.contains("all") || allowed.contains(Long.toString(row.id())))
        .map(row -> response(user, row))
        .toList();
  }

  /**
   * Resolves the only transfer directions the current workbench can use. This is intentionally
   * independent from the client: CENTRAL/REGIONAL values and parent relationships never need to
   * be inferred in the browser.
   */
  public TransferContext resolveTransferContext(AuthUser user, Long warehouseId) {
    requireReadPermission(user);
    FacilityRow current = warehouseId == null
        ? defaultVisibleFacility(user)
        : requireVisibleFacility(user, warehouseId, "查看仓间调拨工作台");

    List<FacilityRow> facilities = repository.facilities(user.tenantId());
    Map<Long, FacilityRow> facilitiesById = new HashMap<>();
    for (FacilityRow row : facilities) {
      facilitiesById.put(row.id(), row);
    }

    List<TransferRoute> routes = new ArrayList<>();
    if ("REGIONAL".equals(current.type())) {
      FacilityRow source = current.parentWarehouseId() == null
          ? null
          : facilitiesById.get(current.parentWarehouseId());
      if (isEffectiveTransferRoute(user, source, current)) {
        TransferActions actions = actionsFor(user, source, current, false);
        if (actions.hasAnyAction()) {
          routes.add(new TransferRoute(source, current, "REQUEST_REPLENISHMENT",
              "向上级总仓申请补货", actions));
        }
      }
    } else if ("CENTRAL".equals(current.type())) {
      for (FacilityRow target : facilities) {
        if (!isEffectiveTransferRoute(user, current, target)) {
          continue;
        }
        TransferActions actions = actionsFor(user, current, target, true);
        // Source-side approval / shipping remains a valid central workbench action even when
        // the user cannot create a new proactive allocation for that target warehouse.
        if (actions.hasAnyAction()) {
          routes.add(new TransferRoute(current, target, "PROACTIVE_ALLOCATION",
              "向分仓主动配货", actions));
        }
      }
    }

    String mode = routes.isEmpty() ? "NONE"
        : "CENTRAL".equals(current.type()) ? "PROACTIVE_ALLOCATION" : "REQUEST_REPLENISHMENT";
    String label = switch (mode) {
      case "REQUEST_REPLENISHMENT" -> "向上级总仓申请补货";
      case "PROACTIVE_ALLOCATION" -> "向分仓主动配货";
      default -> "当前仓库没有可执行的调拨路线";
    };
    return new TransferContext(current, mode, label, List.copyOf(routes));
  }

  public FacilityRow requireVisibleFacility(AuthUser user, Long warehouseId, String action) {
    if (warehouseId == null) {
      throw new BusinessException("WAREHOUSE_REQUIRED", "请选择仓库", HttpStatus.BAD_REQUEST);
    }
    FacilityRow row = repository.facility(user.tenantId(), warehouseId)
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "仓库不存在或不属于当前企业", HttpStatus.NOT_FOUND));
    if (!row.enabled()) {
      throw new BusinessException("WAREHOUSE_DISABLED", "仓库已停用，不能继续操作", HttpStatus.CONFLICT);
    }
    if (!canAccessFacility(user, row.id())) {
      deny(user, action, row, "仓库不在当前账号的数据范围内");
    }
    return row;
  }

  public FacilityRow defaultVisibleFacility(AuthUser user) {
    if (isStoreManager(user)) {
      return requireStoreSupplyWarehouse(user, user.storeId(), "查看本店供货仓").warehouse();
    }
    List<WarehouseFacilityResponse> visible = visibleFacilities(user);
    if (visible.isEmpty()) {
      throw new BusinessException("WAREHOUSE_SCOPE_EMPTY", "当前账号未配置可访问仓库", HttpStatus.FORBIDDEN);
    }
    long preferred = visible.stream()
        .filter(row -> "CENTRAL".equals(row.type()))
        .map(WarehouseFacilityResponse::id)
        .findFirst()
        .orElse(visible.getFirst().id());
    return requireVisibleFacility(user, preferred, "进入仓库中心");
  }

  public FacilityRow requirePurchaseWarehouse(AuthUser user, Long warehouseId, String action) {
    accessControl.requireWarehousePurchase(user);
    FacilityRow facility = warehouseId == null
        ? defaultVisibleFacility(user)
        : requireVisibleFacility(user, warehouseId, action);
    if (!"CENTRAL".equals(facility.type()) || !facility.externalPurchaseAllowed()) {
      deny(user, action, facility, "分仓不允许向外部供应商采购");
    }
    return facility;
  }

  public void requireTransferRequest(AuthUser user, FacilityRow source, FacilityRow target) {
    requireTransferRequestAuthorization(user, source, target);
    requireTransferRoute(user, source, target, "申请仓间调拨");
  }

  /**
   * Checks the caller and immutable central-to-direct-regional topology without checking whether
   * the route is currently enabled. Mutating services run this before looking up an idempotent
   * replay, then enforce the enabled route only for a new state change.
   */
  public void requireTransferRequestAuthorization(AuthUser user, FacilityRow source, FacilityRow target) {
    accessControl.requireWarehouseTransferRequest(user);
    requireFacilityAccess(user, target, "申请仓间调拨");
    requireTransferTopology(user, source, target, "申请仓间调拨");
  }

  public void requireTransferApprove(AuthUser user, FacilityRow source, FacilityRow target) {
    requireTransferApproveAuthorization(user, source, target);
    requireTransferRoute(user, source, target, "审批仓间调拨");
  }

  public void requireTransferApproveAuthorization(AuthUser user, FacilityRow source, FacilityRow target) {
    accessControl.requireWarehouseTransferApprove(user);
    requireFacilityAccess(user, source, "审批仓间调拨");
    requireTransferTopology(user, source, target, "审批仓间调拨");
  }

  public void requireTransferShip(AuthUser user, FacilityRow source, FacilityRow target) {
    requireTransferShipAuthorization(user, source, target);
    requireTransferRoute(user, source, target, "仓间调拨发货");
  }

  public void requireTransferShipAuthorization(AuthUser user, FacilityRow source, FacilityRow target) {
    accessControl.requireWarehouseTransferShip(user);
    requireFacilityAccess(user, source, "仓间调拨发货");
    requireTransferTopology(user, source, target, "仓间调拨发货");
  }

  public void requireTransferReceive(AuthUser user, FacilityRow source, FacilityRow target) {
    requireTransferReceiveAuthorization(user, source, target);
    requireTransferRoute(user, source, target, "确认仓间调拨收货");
  }

  public void requireTransferReceiveAuthorization(AuthUser user, FacilityRow source, FacilityRow target) {
    accessControl.requireWarehouseTransferReceive(user);
    requireFacilityAccess(user, target, "确认仓间调拨收货");
    requireTransferTopology(user, source, target, "确认仓间调拨收货");
  }

  public void requireTransferRead(AuthUser user, FacilityRow source, FacilityRow target) {
    requireReadPermission(user);
    if (!canAccessFacility(user, source.id()) && !canAccessFacility(user, target.id())) {
      deny(user, "查看仓间调拨", target, "调拨两端仓库均不在当前账号的数据范围内");
    }
  }

  public void requireRequisitionProcess(AuthUser user, FacilityRow supplyWarehouse, String action) {
    accessControl.requireWarehouseRequisitionProcess(user);
    requireFacilityAccess(user, supplyWarehouse, action);
    if (!supplyWarehouse.storeSupplyAllowed()) {
      deny(user, action, supplyWarehouse, "该仓库不允许向门店供货");
    }
  }

  public StoreSupplyRow requireStoreSupplyWarehouse(
      AuthUser user,
      String storeId,
      String action
  ) {
    if (isStoreManager(user)) {
      businessScopeResolver.resolve(
          user, DataScopeDomains.WAREHOUSE, storeId, null, action);
    }
    StoreSupplyRow store = repository.storeSupplyWarehouse(user.tenantId(), storeId)
        .orElseThrow(() -> new BusinessException(
            "STORE_NOT_FOUND", "门店不存在或不属于当前企业", HttpStatus.BAD_REQUEST));
    if (!isStoreEnabled(store.status())) {
      throw new BusinessException("STORE_DISABLED", "门店未启用，不能提交叫货", HttpStatus.CONFLICT);
    }
    if (store.regionCode() == null || store.regionCode().isBlank() || store.warehouse() == null) {
      throw new BusinessException(
          "STORE_SUPPLY_WAREHOUSE_REQUIRED", "门店未配置区域或供货仓，不能启用或叫货", HttpStatus.CONFLICT);
    }
    FacilityRow facility = store.warehouse();
    if (!facility.enabled() || !facility.storeSupplyAllowed()) {
      throw new BusinessException(
          "STORE_SUPPLY_WAREHOUSE_DISABLED", "门店供货仓已停用或不允许供货", HttpStatus.CONFLICT);
    }
    if (!store.regionCode().equals(facility.regionCode())) {
      throw new BusinessException(
          "STORE_SUPPLY_ROUTE_INVALID", "门店区域与供货仓不匹配，请联系老板修正", HttpStatus.CONFLICT);
    }
    return store;
  }

  public FacilityRow resolveSupplyWarehouse(long tenantId, String regionCode) {
    String region = normalizeRegion(regionCode);
    return repository.supplyWarehouseForRegion(tenantId, region)
        .orElseThrow(() -> new BusinessException(
            "SUPPLY_WAREHOUSE_NOT_FOUND", "该区域没有启用的供货仓", HttpStatus.CONFLICT));
  }

  public String normalizeRegion(String regionCode) {
    String region = regionCode == null ? "" : regionCode.trim().toUpperCase();
    if (!Set.of("JINGZHOU", "SHANDONG").contains(region)) {
      throw new BusinessException(
          "STORE_REGION_REQUIRED", "门店区域必须明确选择荆州或山东", HttpStatus.BAD_REQUEST);
    }
    return region;
  }

  public boolean canAccessFacility(AuthUser user, long warehouseId) {
    if (AccessControlService.isBoss(user)) {
      return true;
    }
    if (isStoreManager(user)) {
      return repository.storeSupplyWarehouse(user.tenantId(), user.storeId())
          .map(StoreSupplyRow::warehouse)
          .map(row -> row != null && row.id() == warehouseId)
          .orElse(false);
    }
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
    if (DataScopeModes.CENTRAL_WAREHOUSE.equals(scope.mode())) {
      return repository.facility(user.tenantId(), warehouseId)
          .map(row -> "CENTRAL".equals(row.type()))
          .orElse(false);
    }
    return scope.allowsWarehouse(Long.toString(warehouseId));
  }

  private Set<String> allowedFacilityIds(AuthUser user) {
    if (AccessControlService.isBoss(user)) {
      return Set.of("all");
    }
    if (isStoreManager(user)) {
      return repository.storeSupplyWarehouse(user.tenantId(), user.storeId())
          .map(StoreSupplyRow::warehouse)
          .map(row -> row == null ? Set.<String>of() : Set.of(Long.toString(row.id())))
          .orElse(Set.of());
    }
    DataScope scope = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
    if (DataScopeModes.CENTRAL_WAREHOUSE.equals(scope.mode())) {
      return repository.facilities(user.tenantId()).stream()
          .filter(row -> "CENTRAL".equals(row.type()))
          .map(row -> Long.toString(row.id()))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    return scope.allowsAllStores() ? Set.of("all") : Set.copyOf(scope.warehouseIds());
  }

  private WarehouseFacilityResponse response(AuthUser user, FacilityRow row) {
    return new WarehouseFacilityResponse(
        row.id(), row.code(), row.name(), row.type(), row.regionCode(), row.parentWarehouseId(),
        row.parentWarehouseName(), row.externalPurchaseAllowed(), row.storeSupplyAllowed(), row.enabled(),
        true,
        permission(user, PermissionCodes.WAREHOUSE_PURCHASE) && row.externalPurchaseAllowed(),
        permission(user, PermissionCodes.WAREHOUSE_TRANSFER_REQUEST) && "REGIONAL".equals(row.type()),
        permission(user, PermissionCodes.WAREHOUSE_TRANSFER_APPROVE) && "CENTRAL".equals(row.type()),
        permission(user, PermissionCodes.WAREHOUSE_TRANSFER_SHIP) && "CENTRAL".equals(row.type()),
        permission(user, PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE) && "REGIONAL".equals(row.type())
    );
  }

  private void requireReadPermission(AuthUser user) {
    if (isStoreManager(user)) {
      accessControl.requireWarehouseStoreRead(user);
      return;
    }
    accessControl.requireWarehouseRead(user);
  }

  private boolean permission(AuthUser user, String code) {
    return accessControl.hasPermission(user, code);
  }

  private boolean isEffectiveTransferRoute(AuthUser user, FacilityRow source, FacilityRow target) {
    return source != null
        && target != null
        && source.enabled()
        && target.enabled()
        && "CENTRAL".equals(source.type())
        && "REGIONAL".equals(target.type())
        && target.parentWarehouseId() != null
        && target.parentWarehouseId() == source.id()
        && repository.routeEnabled(user.tenantId(), source.id(), target.id());
  }

  private TransferActions actionsFor(
      AuthUser user,
      FacilityRow source,
      FacilityRow target,
      boolean centralWorkbench
  ) {
    boolean sourceAccessible = canAccessFacility(user, source.id());
    boolean targetAccessible = canAccessFacility(user, target.id());
    // A regional replenishment request is authorized against its own target warehouse. A central
    // proactive allocation has to be authorized for both endpoints, preventing a source-only
    // account from selecting an out-of-scope regional warehouse in the target dropdown.
    boolean requestScope = centralWorkbench
        ? sourceAccessible && targetAccessible
        : targetAccessible;
    boolean canRequest = permission(user, PermissionCodes.WAREHOUSE_TRANSFER_REQUEST) && requestScope;
    boolean canApprove = centralWorkbench
        && permission(user, PermissionCodes.WAREHOUSE_TRANSFER_APPROVE)
        && sourceAccessible;
    boolean canShip = centralWorkbench
        && permission(user, PermissionCodes.WAREHOUSE_TRANSFER_SHIP)
        && sourceAccessible;
    boolean canReceive = !centralWorkbench
        && permission(user, PermissionCodes.WAREHOUSE_TRANSFER_RECEIVE)
        && targetAccessible;
    return new TransferActions(
        canRequest,
        canRequest,
        canApprove,
        canApprove,
        canShip,
        canReceive,
        canRequest
    );
  }

  private void requireFacilityAccess(AuthUser user, FacilityRow facility, String action) {
    if (!canAccessFacility(user, facility.id())) {
      deny(user, action, facility, "仓库不在当前账号的数据范围内");
    }
  }

  public void requireTransferRoute(
      AuthUser user,
      FacilityRow source,
      FacilityRow target,
      String action
  ) {
    if (!source.enabled() || !target.enabled()
        || !repository.routeEnabled(user.tenantId(), source.id(), target.id())) {
      denyRoute(user, action, target, "该仓间供货路线未启用");
    }
  }

  private void requireTransferTopology(
      AuthUser user, FacilityRow source, FacilityRow target, String action
  ) {
    if (!"CENTRAL".equals(source.type())
        || !"REGIONAL".equals(target.type())
        || target.parentWarehouseId() == null
        || target.parentWarehouseId() != source.id()) {
      deny(user, action, target, "调拨路线不符合总仓向直属分仓供货的拓扑约束");
    }
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private boolean isStoreEnabled(String status) {
    String value = status == null ? "" : status.trim().toUpperCase();
    return "营业中".equals(status == null ? "" : status.trim()) || "ACTIVE".equals(value);
  }

  private void deny(AuthUser user, String action, FacilityRow facility, String reason) {
    try {
      auditRepository.writePermissionDenied(
          user, action, "WAREHOUSE", Long.toString(facility.id()), null, reason);
    } catch (RuntimeException ex) {
      log.warn("Failed to audit warehouse denial for user {}: {}", user.id(), ex.getMessage());
    }
    throw new BusinessException("FORBIDDEN", "当前账号不能操作该仓库", HttpStatus.FORBIDDEN);
  }

  private void denyRoute(AuthUser user, String action, FacilityRow target, String reason) {
    try {
      auditRepository.writePermissionDenied(
          user, action, "WAREHOUSE", Long.toString(target.id()), null, reason);
    } catch (RuntimeException ex) {
      log.warn("Failed to audit warehouse route denial for user {}: {}", user.id(), ex.getMessage());
    }
    throw new BusinessException("WAREHOUSE_ROUTE_FORBIDDEN", "该仓间供货路线未启用", HttpStatus.FORBIDDEN);
  }

  public record TransferContext(
      FacilityRow currentWarehouse,
      String mode,
      String workbenchLabel,
      List<TransferRoute> routes
  ) {
  }

  public record TransferRoute(
      FacilityRow sourceWarehouse,
      FacilityRow targetWarehouse,
      String formAction,
      String workbenchLabel,
      TransferActions actions
  ) {
  }

  public record TransferActions(
      boolean canCreate,
      boolean canSubmit,
      boolean canApprove,
      boolean canReject,
      boolean canShip,
      boolean canReceive,
      boolean canCancel
  ) {
    boolean hasAnyAction() {
      return canCreate || canSubmit || canApprove || canReject || canShip || canReceive || canCancel;
    }
  }
}
