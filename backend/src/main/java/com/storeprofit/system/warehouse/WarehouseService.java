package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.BusinessScope;
import com.storeprofit.system.platform.authorization.BusinessScopeResolver;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.warehouse.WarehouseRepository.ReturnSourceMovementRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseFacilitySnapshot;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.BatchRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.InventoryRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.StoreSupplyRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {
  private static final DateTimeFormatter RETURN_NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");
  private static final Set<String> REQUISITION_PENDING_STATUSES = Set.of(
      "SUBMITTED",
      "APPROVED",
      "BACKORDERED",
      "WAITING_REPLENISHMENT"
  );
  private static final Set<String> REQUISITION_REVIEWABLE_STATUSES = Set.of(
      "SUBMITTED",
      "BACKORDERED",
      "WAITING_REPLENISHMENT"
  );
  private final WarehouseRepository warehouseRepository;
  private final AccessControlService accessControl;
  private final BusinessScopeResolver businessScopeResolver;
  private final WarehouseTopologyService topologyService;
  private final WarehouseTopologyRepository topologyRepository;

  private record WarehouseReadScope(
      boolean central,
      boolean allStores,
      List<String> storeIds
  ) {
  }

  private record ReturnAvailability(
      BigDecimal receivedQuantity,
      BigDecimal returnedQuantity,
      BigDecimal sourceAvailableReturnQuantity,
      BigDecimal storeInventoryQuantity,
      BigDecimal availableReturnQuantity
  ) {
  }

  @Autowired
  public WarehouseService(
      WarehouseRepository warehouseRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver,
      WarehouseTopologyService topologyService,
      WarehouseTopologyRepository topologyRepository
  ) {
    this.warehouseRepository = warehouseRepository;
    this.accessControl = accessControl;
    this.businessScopeResolver = businessScopeResolver;
    this.topologyService = topologyService;
    this.topologyRepository = topologyRepository;
  }

  public WarehouseService(
      WarehouseRepository warehouseRepository,
      AccessControlService accessControl,
      BusinessScopeResolver businessScopeResolver
  ) {
    this(warehouseRepository, accessControl, businessScopeResolver, null, null);
  }

  public WarehouseService(
      WarehouseRepository warehouseRepository,
      AccessControlService accessControl
  ) {
    this(warehouseRepository, accessControl, null, null, null);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public WarehouseService(WarehouseRepository warehouseRepository) {
    this(warehouseRepository, null, null, null, null);
  }

  public WarehouseOverviewResponse overview(AuthUser user) {
    WarehouseReadScope readScope = requireWarehouseRead(user);
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId());
    if (!readScope.central()) {
      List<WarehouseRequisitionResponse> requisitions = scopedRequisitions(user, readScope);
      List<WarehouseDeliveryResponse> deliveries = scopedDeliveries(user, readScope, 80);
      List<WarehouseStockMovementResponse> movements = scopedMovements(user, readScope, 80);
      List<WarehouseItemResponse> safeItems = safeItems(items, user.tenantId(), readScope.storeIds());
      List<WarehouseAlertResponse> safeAlerts = alerts(safeItems);
      WarehouseSummaryResponse safeSummary = new WarehouseSummaryResponse(
          safeItems.size(),
          (int) safeItems.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
          (int) safeItems.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
          0,
          (int) requisitions.stream().filter(row -> REQUISITION_PENDING_STATUSES.contains(row.status())).count(),
          (int) deliveries.stream().filter(row -> "SHIPPED".equals(row.status())).count(),
          0,
          amount(BigDecimal.ZERO)
      );
      return new WarehouseOverviewResponse(
          safeSummary,
          safeAlerts,
          safeItems,
          safeRequisitions(requisitions),
          List.of(),
          List.of(),
          safeDeliveries(deliveries),
          movements,
          List.of()
      );
    }
    List<WarehouseAlertResponse> alerts = alerts(items);
    List<WarehouseRequisitionResponse> requisitions = scopedRequisitions(user, readScope);
    WarehouseSummaryResponse summary = new WarehouseSummaryResponse(
        items.size(),
        (int) items.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "OVERSTOCK".equals(item.alertLevel())).count(),
        warehouseRepository.pendingRequisitionCount(user.tenantId()),
        warehouseRepository.pendingReceiptCount(user.tenantId(), null),
        warehouseRepository.pendingPurchaseCount(user.tenantId()),
        items.stream().map(WarehouseItemResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)
    );
    WarehouseOverviewResponse response = new WarehouseOverviewResponse(
        summary,
        alerts,
        items,
        requisitions,
        warehouseRepository.suppliers(user.tenantId()),
        warehouseRepository.purchaseOrders(user.tenantId()),
        warehouseRepository.deliveries(user.tenantId(), null),
        warehouseRepository.movements(user.tenantId(), null, 80),
        warehouseRepository.stockBatches(user.tenantId())
    );
    return response;
  }

  /** Warehouse selection is consumed by the V43-aware endpoint; legacy callers keep the no-arg form. */
  public WarehouseOverviewResponse overview(AuthUser user, Long warehouseId) {
    if (warehouseId == null) {
      if (topologyService == null) {
        return overview(user);
      }
      warehouseId = topologyService.defaultVisibleFacility(user).id();
    }
    // The facility-specific implementation is intentionally assembled from warehouse-aware repository
    // queries so one warehouse can never expand the legacy tenant-wide result set.
    FacilityRow facility = null;
    if (topologyService != null) {
      topologyService.visibleFacilities(user);
      facility = topologyService.requireVisibleFacility(user, warehouseId, "查看仓库概览");
    }
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId(), warehouseId);
    if (isStoreManager(user)) {
      items = safeItems(items, user.tenantId(), List.of(user.storeId()));
    }
    List<WarehouseRequisitionResponse> requisitions = warehouseRepository.requisitions(
        user.tenantId(), isStoreManager(user) ? user.storeId() : null, warehouseId);
    List<WarehouseDeliveryResponse> deliveries = warehouseRepository.deliveries(
        user.tenantId(), isStoreManager(user) ? user.storeId() : null, warehouseId);
    List<WarehouseStockMovementResponse> movements = isStoreManager(user)
        ? List.of()
        : warehouseRepository.movements(user.tenantId(), null, warehouseId, 80);
    List<WarehousePurchaseOrderResponse> purchases = isStoreManager(user)
        ? List.of()
        : warehouseRepository.purchaseOrders(user.tenantId(), warehouseId);
    List<WarehouseStockBatchResponse> batches = isStoreManager(user)
        ? List.of()
        : warehouseRepository.stockBatches(user.tenantId(), warehouseId);
    WarehouseSummaryResponse summary = new WarehouseSummaryResponse(
        items.size(),
        (int) items.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "OVERSTOCK".equals(item.alertLevel())).count(),
        warehouseRepository.pendingRequisitionCount(user.tenantId(), warehouseId),
        (int) deliveries.stream().filter(row -> "SHIPPED".equals(row.status())).count(),
        warehouseRepository.pendingPurchaseCount(user.tenantId(), warehouseId),
        items.stream().map(WarehouseItemResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP)
    );
    return new WarehouseOverviewResponse(
        summary, alerts(items), items,
        isStoreManager(user) ? safeRequisitions(requisitions) : requisitions,
        facility != null && facility.externalPurchaseAllowed() && !isStoreManager(user)
            ? warehouseRepository.suppliers(user.tenantId()) : List.of(),
        purchases,
        isStoreManager(user) ? safeDeliveries(deliveries) : deliveries,
        movements,
        batches);
  }

  public List<WarehouseItemResponse> items(AuthUser user) {
    if (topologyService != null) {
      FacilityRow facility = topologyService.defaultVisibleFacility(user);
      List<WarehouseItemResponse> rows = warehouseRepository.items(user.tenantId(), facility.id());
      return isStoreManager(user) ? safeItems(rows, user.tenantId(), List.of(user.storeId())) : rows;
    }
    WarehouseReadScope readScope = requireWarehouseRead(user);
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId());
    return readScope.central() ? items : safeItems(items, user.tenantId(), readScope.storeIds());
  }

  public WarehouseItemResponse item(AuthUser user, long itemId) {
    if (topologyService != null) {
      FacilityRow facility = topologyService.defaultVisibleFacility(user);
      WarehouseItemResponse row = warehouseRepository.item(user.tenantId(), itemId, facility.id())
          .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.NOT_FOUND));
      return isStoreManager(user) ? safeItems(List.of(row), user.tenantId(), List.of(user.storeId())).getFirst() : row;
    }
    WarehouseReadScope readScope = requireWarehouseRead(user);
    WarehouseItemResponse item = warehouseRepository.item(user.tenantId(), itemId)
        .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.NOT_FOUND));
    if (!readScope.central() && !item.active()) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在或已停用", HttpStatus.NOT_FOUND);
    }
    return readScope.central() ? item : safeItems(List.of(item), user.tenantId(), readScope.storeIds()).get(0);
  }

  public List<WarehouseItemCategoryResponse> itemCategories(AuthUser user) {
    if (topologyService != null) {
      topologyService.visibleFacilities(user);
    } else {
      requireWarehouseRead(user);
    }
    return categoryTree(warehouseRepository.itemCategories(user.tenantId()));
  }

  @Transactional
  public void saveItem(AuthUser user, WarehouseItemRequest request) {
    requireWarehouseConfigure(user);
    if (request.id() != null && !warehouseRepository.itemExists(user.tenantId(), request.id())) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.BAD_REQUEST);
    }
    if (request.categoryId() == null && (request.category() == null || request.category().isBlank())) {
      throw new BusinessException("CATEGORY_REQUIRED", "请选择商品类别", HttpStatus.BAD_REQUEST);
    }
    if (request.categoryId() != null && !warehouseRepository.itemCategoryEnabled(user.tenantId(), request.categoryId())) {
      throw new BusinessException("CATEGORY_DISABLED", "商品类别不存在或已停用", HttpStatus.BAD_REQUEST);
    }
    validateItemImage(request.imageUrl());
    long itemId = warehouseRepository.upsertItem(user.tenantId(), request);
    warehouseRepository.replaceItemDepartments(user.tenantId(), itemId, request.departments());
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "保存物料", request.code(), null, request.name());
  }

  @Transactional
  public WarehouseItemCategoryResponse saveItemCategory(AuthUser user, WarehouseItemCategoryRequest request) {
    requireWarehouseConfigure(user);
    String name = request.name() == null ? "" : request.name().trim();
    if (name.isBlank()) {
      throw new BusinessException("CATEGORY_NAME_REQUIRED", "请填写类别名称", HttpStatus.BAD_REQUEST);
    }
    if (request.id() != null && !warehouseRepository.itemCategoryExists(user.tenantId(), request.id())) {
      throw new BusinessException("CATEGORY_NOT_FOUND", "商品类别不存在", HttpStatus.BAD_REQUEST);
    }
    if (request.parentId() != null) {
      if (Objects.equals(request.id(), request.parentId())) {
        throw new BusinessException("BAD_CATEGORY_PARENT", "类别不能选择自己作为上级", HttpStatus.BAD_REQUEST);
      }
      if (!warehouseRepository.itemCategoryExists(user.tenantId(), request.parentId())) {
        throw new BusinessException("CATEGORY_PARENT_NOT_FOUND", "上级类别不存在", HttpStatus.BAD_REQUEST);
      }
      if (request.id() != null && categoryWouldCreateCycle(user.tenantId(), request.id(), request.parentId())) {
        throw new BusinessException("BAD_CATEGORY_PARENT", "类别不能移动到自己的下级分类中", HttpStatus.BAD_REQUEST);
      }
    }
    if (warehouseRepository.itemCategoryNameExists(user.tenantId(), request.parentId(), name, request.id())) {
      throw new BusinessException("CATEGORY_DUPLICATED", "同级类别名称不能重复", HttpStatus.CONFLICT);
    }
    long id = warehouseRepository.upsertItemCategory(user.tenantId(), request);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "保存商品类别", String.valueOf(id), null, name);
    return warehouseRepository.itemCategories(user.tenantId()).stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new BusinessException("CATEGORY_NOT_FOUND", "商品类别保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public void setItemCategoryEnabled(AuthUser user, long categoryId, WarehouseItemEnabledRequest request) {
    requireWarehouseConfigure(user);
    if (!warehouseRepository.itemCategoryExists(user.tenantId(), categoryId)) {
      throw new BusinessException("CATEGORY_NOT_FOUND", "商品类别不存在", HttpStatus.BAD_REQUEST);
    }
    boolean enabled = request == null || request.enabled() == null || request.enabled();
    warehouseRepository.setItemCategoryEnabled(user.tenantId(), categoryId, enabled);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), enabled ? "启用商品类别" : "停用商品类别", String.valueOf(categoryId), null, "");
  }

  @Transactional
  public void deleteItemCategory(AuthUser user, long categoryId) {
    requireWarehouseConfigure(user);
    if (!warehouseRepository.itemCategoryExists(user.tenantId(), categoryId)) {
      throw new BusinessException("CATEGORY_NOT_FOUND", "商品类别不存在", HttpStatus.NOT_FOUND);
    }
    if (warehouseRepository.itemCategoryChildCount(user.tenantId(), categoryId) > 0) {
      throw new BusinessException("CATEGORY_HAS_CHILDREN", "请先删除或调整子类别", HttpStatus.CONFLICT);
    }
    if (warehouseRepository.itemCategoryItemCount(user.tenantId(), categoryId) > 0) {
      throw new BusinessException("CATEGORY_IN_USE", "该类别下仍有物料，不能删除", HttpStatus.CONFLICT);
    }
    warehouseRepository.deleteItemCategory(user.tenantId(), categoryId);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "删除商品类别", String.valueOf(categoryId), null, "");
  }

  @Transactional
  public void setItemEnabled(AuthUser user, long itemId, WarehouseItemEnabledRequest request) {
    requireWarehouseConfigure(user);
    if (!warehouseRepository.itemExists(user.tenantId(), itemId)) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.BAD_REQUEST);
    }
    boolean enabled = request == null || request.enabled() == null || request.enabled();
    warehouseRepository.setItemEnabled(user.tenantId(), itemId, enabled);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), enabled ? "启用商品" : "停用商品", String.valueOf(itemId), null, "");
  }

  @Transactional
  public void updateAlertSettings(AuthUser user, long itemId, WarehouseAlertSettingsRequest request) {
    requireWarehouseConfigure(user);
    if (!warehouseRepository.itemExists(user.tenantId(), itemId)) {
      throw new BusinessException("ITEM_NOT_FOUND", "物料不存在", HttpStatus.BAD_REQUEST);
    }
    WarehouseAlertSettingsRequest safeRequest = request == null
        ? new WarehouseAlertSettingsRequest(BigDecimal.ZERO, true, 3, null)
        : request;
    FacilityRow facility = topologyService == null ? null
        : (safeRequest.warehouseId() == null
            ? topologyService.defaultVisibleFacility(user)
            : topologyService.requireVisibleFacility(user, safeRequest.warehouseId(), "设置库存预警"));
    if (facility == null) {
      warehouseRepository.updateAlertSettings(
          user.tenantId(), itemId, amount(safeRequest.minStockQuantity()),
          safeRequest.alertEnabled() == null || safeRequest.alertEnabled(),
          safeRequest.expiryAlertDays());
    } else {
      warehouseRepository.updateAlertSettings(
          user.tenantId(), facility.id(), itemId, amount(safeRequest.minStockQuantity()),
          safeRequest.alertEnabled() == null || safeRequest.alertEnabled(),
          safeRequest.expiryAlertDays());
    }
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "设置库存预警",
        facility == null ? String.valueOf(itemId) : facility.id() + ":" + itemId,
        null,
        "最低安全库存 " + amount(safeRequest.minStockQuantity()).stripTrailingZeros().toPlainString()
    );
  }

  @Transactional
  public void receiveStock(AuthUser user, WarehouseStockBatchRequest request) {
    if (topologyService != null) {
      throw new BusinessException(
          "DIRECT_STOCK_RECEIVE_DISABLED",
          "请先创建并审批采购单，再通过采购单办理入库",
          HttpStatus.CONFLICT
      );
    }
    FacilityRow facility = topologyService == null
        ? null
        : topologyService.requirePurchaseWarehouse(user, request.warehouseId(), "外部采购入库");
    if (facility == null) {
      requireWarehouseManage(user);
    }
    if (!warehouseRepository.activeItemExists(user.tenantId(), request.itemId())) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在或已停用", HttpStatus.BAD_REQUEST);
    }
    LocalDate receivedDate = parseDate(request.receivedDate(), "到货日期");
    if (request.expiryDate() != null && !request.expiryDate().isBlank()) {
      LocalDate expiryDate = parseDate(request.expiryDate(), "到期日期");
      if (expiryDate.isBefore(receivedDate)) {
        throw new BusinessException("BAD_EXPIRY_DATE", "到期日期不能早于到货日期", HttpStatus.BAD_REQUEST);
      }
    }
    String requestKey = normalizeClientRequestId(request.clientRequestId());
    if (requestKey != null && !warehouseRepository.reserveRequest(user.tenantId(), "WAREHOUSE_STOCK_RECEIVE", requestKey)) {
      return;
    }
    long warehouseId = facility == null ? -1 : facility.id();
    if (facility == null) {
      warehouseRepository.upsertBatch(user.tenantId(), request);
    } else {
      warehouseRepository.upsertBatch(user.tenantId(), warehouseId, request);
      InventoryRow inventory = topologyRepository.lockInventory(user.tenantId(), warehouseId, request.itemId());
      BigDecimal quantity = amount(request.quantity());
      BigDecimal newCost = weightedCost(inventory.onHand(), inventory.unitCost(), quantity, amount(request.unitCost()));
      if (!topologyRepository.updateInventory(user.tenantId(), inventory,
          inventory.onHand().add(quantity), inventory.reserved(), inventory.inTransit(), newCost)) {
        throw new BusinessException("WAREHOUSE_CONCURRENT_UPDATE", "库存已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT);
      }
    }
    Long batchId = facility == null
        ? warehouseRepository.batchId(user.tenantId(), request.itemId(), request.batchNo()).orElse(null)
        : warehouseRepository.batchId(user.tenantId(), warehouseId, request.itemId(), request.batchNo()).orElse(null);
    if (facility == null) {
      warehouseRepository.insertMovement(user.tenantId(), request.itemId(), batchId, "IN",
          request.quantity(), "MANUAL_RECEIVE", request.batchNo(), null, request.note(), user.id());
    } else {
      warehouseRepository.insertMovement(user.tenantId(), warehouseId, request.itemId(), batchId, "IN",
          request.quantity(), "MANUAL_RECEIVE", request.batchNo(), null, request.note(), user.id());
    }
    if (requestKey != null) {
      warehouseRepository.completeReservedRequest(user.tenantId(), "WAREHOUSE_STOCK_RECEIVE", requestKey, String.valueOf(batchId));
    }
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "仓库入库", request.batchNo(), null, request.note());
  }

  public List<WarehouseRequisitionResponse> requisitions(AuthUser user) {
    if (topologyService != null) {
      FacilityRow facility = topologyService.defaultVisibleFacility(user);
      List<WarehouseRequisitionResponse> rows = warehouseRepository.requisitions(
          user.tenantId(), isStoreManager(user) ? user.storeId() : null, facility.id());
      return isStoreManager(user) ? safeRequisitions(rows) : rows;
    }
    WarehouseReadScope readScope = requireWarehouseRead(user);
    List<WarehouseRequisitionResponse> requisitions = scopedRequisitions(user, readScope);
    return readScope.central() ? requisitions : safeRequisitions(requisitions);
  }

  public List<WarehouseStockMovementResponse> movements(AuthUser user) {
    if (topologyService != null) {
      FacilityRow facility = topologyService.defaultVisibleFacility(user);
      return warehouseRepository.movements(user.tenantId(), isStoreManager(user) ? user.storeId() : null,
          facility.id(), 120);
    }
    WarehouseReadScope readScope = requireWarehouseRead(user);
    return scopedMovements(user, readScope, 120);
  }

  public List<WarehouseReturnResponse> returns(AuthUser user) {
    if (topologyService != null) {
      List<WarehouseFacilityResponse> facilities = topologyService.visibleFacilities(user);
      List<WarehouseReturnResponse> rows = facilities.stream()
          .flatMap(facility -> warehouseRepository.returns(user.tenantId(),
              isStoreManager(user) ? user.storeId() : null, facility.id()).stream())
          .collect(Collectors.toMap(WarehouseReturnResponse::id, Function.identity(), (a, b) -> a,
              java.util.LinkedHashMap::new)).values().stream().toList();
      return isStoreManager(user) ? safeReturns(rows) : rows;
    }
    WarehouseReadScope readScope = requireReturnRead(user);
    List<WarehouseReturnResponse> returns = scopedReturns(user, readScope);
    return readScope.central() ? returns : safeReturns(returns);
  }

  public WarehouseReturnResponse returnOrder(AuthUser user, String returnId) {
    if (topologyService != null) {
      WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
          .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
      FacilityRow supply = returnFacility(user, order.id());
      topologyService.visibleFacilities(user);
      topologyService.requireVisibleFacility(user, supply.id(), "查看配送退货单");
      return isStoreManager(user) ? safeReturn(order) : order;
    }
    WarehouseReadScope readScope = requireReturnRead(user);
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    requireStoreInReadScope(user, readScope, order.returnStoreId(), "查看配送退货单");
    return readScope.central() ? order : safeReturn(order);
  }

  @Transactional
  public WarehouseReturnResponse createReturn(AuthUser user, WarehouseReturnRequest request) {
    requireReturnCreate(user);
    String sourceRequisitionId = request.sourceRequisitionId() == null ? "" : request.sourceRequisitionId().trim();
    if (sourceRequisitionId.isBlank()) {
      throw new BusinessException("SOURCE_REQUIRED", "请选择要退货的原叫货单", HttpStatus.BAD_REQUEST);
    }
    WarehouseRequisitionResponse requisition = requireRequisition(user.tenantId(), sourceRequisitionId);
    requireWarehouseStoreScope(user, requisition.storeId(), "基于叫货单发起退货", false);
    if (!List.of("SHIPPED", "RECEIVED").contains(requisition.status())) {
      throw new BusinessException("BAD_SOURCE_STATUS", "只能基于已发货或已收货的叫货单发起退货", HttpStatus.CONFLICT);
    }
    String requestedStoreId = request.returnStoreId() == null ? "" : request.returnStoreId().trim();
    if (!requestedStoreId.isBlank() && !requestedStoreId.equals(requisition.storeId())) {
      throw new BusinessException("BAD_RETURN_STORE", "退货门店必须与原叫货单门店一致", HttpStatus.BAD_REQUEST);
    }
    String storeId = requisition.storeId();
    if (!warehouseRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "退货门店不存在", HttpStatus.BAD_REQUEST);
    }
    // receiveDepartment remains on the wire for backward compatibility, but it
    // must never choose the physical receiving warehouse or its PDF wording.
    WarehouseFacilitySnapshot receiveWarehouse = warehouseRepository
        .receiveWarehouseForRequisition(user.tenantId(), requisition.id())
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "叫货单未关联有效供货仓", HttpStatus.CONFLICT));
    String returnDate = request.returnDate() == null || request.returnDate().isBlank()
        ? LocalDate.now().toString()
        : request.returnDate().trim();
    parseDate(returnDate, "退货日期");
    String storeName = warehouseRepository.storeName(user.tenantId(), storeId).orElse(storeId);
    Map<Long, WarehouseRequisitionLineResponse> sourceLines = requisition.lines().stream()
        .collect(Collectors.toMap(WarehouseRequisitionLineResponse::itemId, line -> line, (left, right) -> left));
    String returnNo = returnNo();
    String deliveryId = warehouseRepository.deliveryByRequisition(user.tenantId(), requisition.id())
        .map(WarehouseDeliveryResponse::id)
        .orElse(null);
    BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    ArrayList<ReturnLineDraft> drafts = new ArrayList<>();
    for (WarehouseReturnLineRequest line : request.lines()) {
      WarehouseRequisitionLineResponse sourceLine = sourceLines.get(line.itemId());
      if (sourceLine == null) {
        throw new BusinessException("RETURN_ITEM_NOT_IN_SOURCE", "退货商品必须来自原叫货单", HttpStatus.BAD_REQUEST);
      }
      BigDecimal quantity = positive(line.quantity(), "退货数量");
      BigDecimal shippedQuantity = amount(sourceLine.shippedQuantity());
      if (shippedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("SOURCE_NOT_SHIPPED", "该商品还没有实际发货，不能退货", HttpStatus.CONFLICT);
      }
      ReturnAvailability availability = returnAvailability(user.tenantId(), storeId, requisition, sourceLine);
      if (quantity.compareTo(availability.sourceAvailableReturnQuantity()) > 0) {
        throw new BusinessException(
            "RETURN_QUANTITY_TOO_LARGE",
            "退货数量不能大于原单可退数量，原单当前最多可退 "
                + availability.sourceAvailableReturnQuantity().stripTrailingZeros().toPlainString()
                + unitText(sourceLine.unit()),
            HttpStatus.BAD_REQUEST
        );
      }
      if (quantity.compareTo(availability.storeInventoryQuantity()) > 0) {
        throw new BusinessException(
            "RETURN_STORE_STOCK_TOO_LOW",
            "退货数量不能大于本店当前库存，本店当前库存只有 "
                + availability.storeInventoryQuantity().stripTrailingZeros().toPlainString()
                + unitText(sourceLine.unit()),
            HttpStatus.BAD_REQUEST
        );
      }
      List<ReturnSourceMovementRow> sourceMovements = warehouseRepository.returnSourceMovements(user.tenantId(), requisition.id(), line.itemId());
      if (sourceMovements.isEmpty()) {
        throw new BusinessException("RETURN_BATCH_NOT_FOUND", "找不到原出库批次，不能创建退货单", HttpStatus.CONFLICT);
      }
      BigDecimal remaining = quantity;
      for (ReturnSourceMovementRow movement : sourceMovements) {
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
          break;
        }
        BigDecimal used = movement.shippedQuantity().min(remaining);
        BigDecimal unitPrice = amount(movement.unitPrice());
        BigDecimal returnPrice = amount(line.returnPrice() == null || !hasCentralCostAccess(user) ? unitPrice : line.returnPrice());
        total = total.add(used.multiply(returnPrice).setScale(2, RoundingMode.HALF_UP));
        drafts.add(new ReturnLineDraft(
            movement.sourceRequisitionLineId(),
            movement.itemId(),
            movement.itemName(),
            movement.spec(),
            movement.batchId(),
            movement.batchNo(),
            used,
            movement.unit(),
            unitPrice,
            returnPrice,
            line.reason(),
            line.note()
        ));
        remaining = remaining.subtract(used).setScale(2, RoundingMode.HALF_UP);
      }
    }
    warehouseRepository.insertReturnOrder(user.tenantId(), receiveWarehouse,
        returnNo, returnNo, requisition.id(), deliveryId, storeId, storeName,
        "SUBMITTED", total, returnHandler(user), user.displayName(), request.reason(),
        request.note(), returnDate);
    for (ReturnLineDraft draft : drafts) {
      warehouseRepository.insertReturnOrderLine(
          user.tenantId(),
          returnNo,
          draft.sourceRequisitionLineId(),
          draft.itemId(),
          draft.itemName(),
          draft.spec(),
          draft.batchId(),
          draft.batchNo(),
          draft.quantity(),
          draft.unit(),
          draft.unitPrice(),
          draft.returnPrice(),
          draft.reason(),
          draft.note()
      );
    }
    saveReturnAttachments(user, storeId, returnNo, request.attachments());
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "提交配送退货单", returnNo, storeId, request.note());
    WarehouseReturnResponse saved = warehouseRepository.returnOrder(user.tenantId(), returnNo)
        .orElseThrow(() -> new BusinessException("RETURN_SAVE_FAILED", "配送退货单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
    return hasCentralCostAccess(user) ? saved : safeReturn(saved);
  }

  @Transactional
  public WarehouseReturnResponse reviewReturn(AuthUser user, String returnId, WarehouseReturnReviewRequest request) {
    if (topologyService == null) {
      requireReturnReview(user);
    }
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    FacilityRow returnWarehouse = topologyService == null ? null : returnFacility(user, order.id());
    if (returnWarehouse == null) {
      requireWarehouseStoreScope(user, order.returnStoreId(), "审核配送退货单", true);
    } else {
      topologyService.requireRequisitionProcess(user, returnWarehouse, "审核配送退货单");
    }
    if (!"SUBMITTED".equals(order.status())) {
      throw new BusinessException("BAD_RETURN_STATUS", "只有已提交的退货单可以审核", HttpStatus.CONFLICT);
    }
    boolean approved = request != null && request.approved();
    String note = request == null ? null : request.note();
    warehouseRepository.reviewReturnOrder(user.tenantId(), order.id(), approved, user.displayName(), note);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), approved ? "审核通过配送退货单" : "驳回配送退货单", order.id(), order.returnStoreId(), note);
    if (!approved) {
      warehouseRepository.insertTodoAction(
          "todo-act-" + UUID.randomUUID(),
          user.tenantId(),
          "warehouse-return-" + order.id(),
          "WAREHOUSE_RETURN_REJECT",
          "仓库已驳回退货单",
          user.id(),
          user.displayName(),
          user.role()
      );
    }
    WarehouseReturnResponse updated = warehouseRepository.returnOrder(user.tenantId(), order.id())
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    return hasCentralCostAccess(user) ? updated : safeReturn(updated);
  }

  @Transactional
  public WarehouseReturnResponse receiveReturn(AuthUser user, String returnId, WarehouseReturnReceiveRequest request) {
    if (topologyService == null) {
      requireReturnReview(user);
    }
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    FacilityRow returnWarehouse = topologyService == null ? null : returnFacility(user, order.id());
    if (returnWarehouse == null) {
      requireWarehouseStoreScope(user, order.returnStoreId(), "确认配送退货入库", true);
    } else {
      topologyService.requireRequisitionProcess(user, returnWarehouse, "确认配送退货入库");
    }
    if (!"APPROVED".equals(order.status())) {
      throw new BusinessException("BAD_RETURN_STATUS", "只有仓库已通过的退货单可以确认收货", HttpStatus.CONFLICT);
    }
    String note = request == null ? null : request.note();
    for (WarehouseReturnLineResponse line : order.lines()) {
      if (line.batchId() == null) {
        throw new BusinessException("RETURN_BATCH_NOT_FOUND", "退货明细缺少原出库批次，不能回库", HttpStatus.CONFLICT);
      }
      String returnNote = note == null || note.isBlank() ? "配送退货回库" : note;
      if (returnWarehouse == null) {
        warehouseRepository.addBatchQuantity(user.tenantId(), line.batchId(), line.quantity());
        warehouseRepository.insertMovement(user.tenantId(), line.itemId(), line.batchId(), "IN",
            line.quantity(), "RETURN", order.id(), order.returnStoreId(), returnNote, user.id());
        warehouseRepository.addStoreInventory(user.tenantId(), order.returnStoreId(), line.itemId(),
            line.quantity().negate(), "OUT", "STORE_RETURN", order.id(), returnNote, user.id());
      } else {
        FacilityRow warehouse = returnWarehouse;
        BatchRow batch = topologyRepository.batchForUpdate(
                user.tenantId(), warehouse.id(), line.batchId())
            .orElseThrow(() -> new BusinessException(
                "RETURN_BATCH_NOT_FOUND", "退货批次不属于本店供货仓", HttpStatus.CONFLICT));
        InventoryRow inventory = topologyRepository.lockInventory(
            user.tenantId(), warehouse.id(), line.itemId());
        if (!topologyRepository.updateBatchQuantity(user.tenantId(), batch,
            batch.quantity().add(line.quantity()), batch.reservedQuantity())) {
          throw warehouseConcurrentUpdate();
        }
        BigDecimal cost = weightedCost(inventory.onHand(), inventory.unitCost(),
            line.quantity(), batch.unitCost());
        if (!topologyRepository.updateInventory(user.tenantId(), inventory,
            inventory.onHand().add(line.quantity()), inventory.reserved(), inventory.inTransit(), cost)) {
          throw warehouseConcurrentUpdate();
        }
        topologyRepository.insertMovement(user.tenantId(), warehouse.id(), line.itemId(), line.batchId(),
            "RETURN_IN", line.quantity(), BigDecimal.ZERO, BigDecimal.ZERO, batch.unitCost(),
            "RETURN", order.id(), order.returnStoreId(), returnNote, user.id());
        if (!warehouseRepository.subtractStoreInventoryIfEnough(user.tenantId(), order.returnStoreId(),
            line.itemId(), line.quantity(), "STORE_RETURN", order.id(), returnNote, user.id())) {
          throw new BusinessException("RETURN_STORE_STOCK_TOO_LOW", "门店当前库存不足，不能确认退货", HttpStatus.CONFLICT);
        }
      }
    }
    warehouseRepository.receiveReturnOrder(user.tenantId(), order.id(), user.displayName(), note);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "确认收到配送退货", order.id(), order.returnStoreId(), note);
    warehouseRepository.insertTodoAction(
        "todo-act-" + UUID.randomUUID(),
        user.tenantId(),
        "warehouse-return-" + order.id(),
        "WAREHOUSE_RETURN_RECEIVE",
        "仓库已确认收到退货，退货流程完成",
        user.id(),
        user.displayName(),
        user.role()
    );
    WarehouseReturnResponse updated = warehouseRepository.returnOrder(user.tenantId(), order.id())
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    return hasCentralCostAccess(user) ? updated : safeReturn(updated);
  }

  @Transactional
  public WarehousePurchaseOrderResponse createPurchaseOrder(AuthUser user, WarehousePurchaseOrderRequest request) {
    FacilityRow facility = topologyService == null
        ? null
        : topologyService.requirePurchaseWarehouse(user, request.warehouseId(), "创建外部采购单");
    if (facility == null) {
      requireWarehouseManage(user);
    }
    if (!warehouseRepository.activeSupplierExists(user.tenantId(), request.supplierId())) {
      throw new BusinessException("SUPPLIER_NOT_FOUND", "供应商不存在、已停用或不属于当前企业", HttpStatus.BAD_REQUEST);
    }
    String purchaseRequestKey = normalizeClientRequestId(request.clientRequestId());
    if (facility != null && purchaseRequestKey == null) {
      throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "创建采购单必须提供请求编号", HttpStatus.BAD_REQUEST);
    }
    if (facility != null) {
      var duplicateId = warehouseRepository.purchaseOrderIdByRequestKey(user.tenantId(), purchaseRequestKey);
      if (duplicateId.isPresent()) {
        var duplicate = warehouseRepository.purchaseOrderForUpdate(user.tenantId(), duplicateId.get())
            .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
        if (duplicate.warehouseId() != facility.id()) {
          throw new BusinessException("IDEMPOTENCY_KEY_CONFLICT", "该请求编号已用于另一仓库采购单", HttpStatus.CONFLICT);
        }
        return warehouseRepository.purchaseOrder(user.tenantId(), duplicateId.get())
            .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
      }
    }
    String id = "PO" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    BigDecimal total = BigDecimal.ZERO;
    for (WarehousePurchaseOrderLineRequest line : request.lines()) {
      if (!warehouseRepository.itemExists(user.tenantId(), line.itemId())) {
        throw new BusinessException("ITEM_NOT_FOUND", "采购物料不存在", HttpStatus.BAD_REQUEST);
      }
      total = total.add(positive(line.orderedQuantity(), "采购数量").multiply(amount(line.unitCost())));
    }
    if (facility == null) {
      warehouseRepository.insertPurchaseOrder(user.tenantId(), id, request.supplierId(), total, request.note(), user.id());
    } else {
      if (!warehouseRepository.insertPurchaseOrder(user.tenantId(), facility.id(), id, request.supplierId(),
          total, request.note(), user.id(), purchaseRequestKey)) {
        return warehouseRepository.purchaseOrderIdByRequestKey(user.tenantId(), purchaseRequestKey)
            .flatMap(existingId -> warehouseRepository.purchaseOrder(user.tenantId(), existingId))
            .orElseThrow(() -> warehouseConcurrentUpdate());
      }
    }
    for (WarehousePurchaseOrderLineRequest line : request.lines()) {
      warehouseRepository.insertPurchaseOrderLine(
          user.tenantId(),
          id,
          line.itemId(),
          positive(line.orderedQuantity(), "采购数量"),
          amount(line.unitCost()),
          line.note()
      );
    }
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "创建采购单", id, null, request.note());
    return warehouseRepository.purchaseOrder(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public WarehouseRequisitionResponse createRequisition(AuthUser user, WarehouseRequisitionRequest request) {
    requireStoreRequisitionCreate(user);
    String storeId = normalizeStoreForSubmit(user, request.storeId());
    if (!warehouseRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在", HttpStatus.BAD_REQUEST);
    }
    StoreSupplyRow storeSupply = topologyService == null
        ? null
        : topologyService.requireStoreSupplyWarehouse(user, storeId, "提交门店叫货");
    String requestKey = normalizeClientRequestId(request.clientRequestId());
    if (requestKey != null) {
      var previousId = warehouseRepository.reservedRequestBusinessId(user.tenantId(), "STORE_REQUISITION", requestKey);
      if (previousId.isPresent()) {
        WarehouseRequisitionResponse existing = requireRequisition(user.tenantId(), previousId.get());
        requireWarehouseStoreScope(user, existing.storeId(), "查看已提交叫货单", false);
        return hasCentralCostAccess(user) ? existing : safeRequisitions(List.of(existing)).get(0);
      }
      if (!warehouseRepository.reserveRequest(user.tenantId(), "STORE_REQUISITION", requestKey)) {
        WarehouseRequisitionResponse existing = warehouseRepository.reservedRequestBusinessId(user.tenantId(), "STORE_REQUISITION", requestKey)
            .map(id -> requireRequisition(user.tenantId(), id))
            .orElseThrow(() -> new BusinessException("REQUEST_IN_PROGRESS", "叫货单正在提交，请稍后刷新", HttpStatus.CONFLICT));
        requireWarehouseStoreScope(user, existing.storeId(), "查看已提交叫货单", false);
        return hasCentralCostAccess(user) ? existing : safeRequisitions(List.of(existing)).get(0);
      }
    }
    Map<Long, WarehouseItemResponse> items = (storeSupply == null
        ? warehouseRepository.items(user.tenantId())
        : warehouseRepository.items(user.tenantId(), storeSupply.warehouse().id())).stream()
        .collect(Collectors.toMap(WarehouseItemResponse::id, Function.identity()));
    String id = "REQ" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    BigDecimal total = BigDecimal.ZERO;
    ArrayList<LineDraft> drafts = new ArrayList<>();
    for (WarehouseRequisitionLineRequest line : request.lines()) {
      WarehouseItemResponse item = items.get(line.itemId());
      if (item == null || !item.active()) {
        throw new BusinessException("ITEM_NOT_FOUND", "叫货物料不存在或已停用", HttpStatus.BAD_REQUEST);
      }
      BigDecimal quantity = positive(line.requestedQuantity(), "叫货数量");
      String warning = warningForOrder(item, quantity);
      total = total.add(quantity.multiply(item.unitPrice()));
      drafts.add(new LineDraft(item, quantity, warning, line.note()));
    }
    if (storeSupply == null) {
      warehouseRepository.insertRequisition(user.tenantId(), id, storeId, total, request.note(), user.id());
    } else {
      warehouseRepository.insertRequisition(user.tenantId(), id, storeId, storeSupply.warehouse().id(),
          total, request.note(), user.id(), requestKey);
    }
    for (LineDraft draft : drafts) {
      warehouseRepository.insertRequisitionLine(
          user.tenantId(),
          id,
          draft.item().id(),
          draft.quantity(),
          draft.item().unitPrice(),
          draft.warning(),
          draft.note()
      );
    }
    if (requestKey != null) {
      warehouseRepository.completeReservedRequest(user.tenantId(), "STORE_REQUISITION", requestKey, id);
    }
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "提交叫货", id, storeId, request.note());
    WarehouseRequisitionResponse saved = warehouseRepository.requisition(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("REQ_NOT_FOUND", "叫货单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
    return hasCentralCostAccess(user) ? saved : safeRequisitions(List.of(saved)).get(0);
  }

  @Transactional
  public void review(AuthUser user, String requisitionId, WarehouseRequisitionReviewRequest request) {
    if (topologyService == null) {
      requireRequisitionReview(user);
    }
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    FacilityRow supplyWarehouse = topologyService == null ? null
        : requisitionFacility(user, requisition.id());
    if (supplyWarehouse == null) {
      requireWarehouseStoreScope(user, requisition.storeId(), "审核叫货单", true);
    } else {
      topologyService.requireRequisitionProcess(user, supplyWarehouse, "审核叫货单");
    }
    if (!REQUISITION_REVIEWABLE_STATUSES.contains(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "只有待处理或待补货叫货单可以处理", HttpStatus.CONFLICT);
    }
    if (request == null) {
      throw new BusinessException("REVIEW_REQUIRED", "请选择叫货处理方式", HttpStatus.BAD_REQUEST);
    }
    Map<Long, BigDecimal> approvedMap = new HashMap<>();
    if (request.lines() != null) {
      for (WarehouseRequisitionReviewLineRequest line : request.lines()) {
        approvedMap.put(line.itemId(), line.approvedQuantity());
      }
    }
    WarehouseRequisitionHandlingMode mode = request.handlingMode() == null
        ? WarehouseRequisitionHandlingMode.FULL
        : request.handlingMode();
    if (!request.approved()) {
      rejectRequisition(user, requisition, request.note());
      return;
    }
    if (List.of(
        WarehouseRequisitionHandlingMode.MARK_BACKORDER,
        WarehouseRequisitionHandlingMode.WAIT_REPLENISHMENT
    ).contains(mode)) {
      markRequisitionBackordered(user, requisition, mode, request.note());
      return;
    }

    BigDecimal total = BigDecimal.ZERO;
    BigDecimal newlyApprovedTotal = BigDecimal.ZERO;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal shipped = amount(line.shippedQuantity());
      BigDecimal requestedTarget = amount(approvedMap.getOrDefault(line.itemId(), line.requestedQuantity()));
      if (requestedTarget.compareTo(line.requestedQuantity()) > 0) {
        throw new BusinessException("APPROVED_QUANTITY_TOO_LARGE", "批准数量不能超过申请数量", HttpStatus.BAD_REQUEST);
      }
      if (requestedTarget.compareTo(shipped) < 0) {
        throw new BusinessException("APPROVED_QUANTITY_TOO_SMALL", "批准数量不能小于已发数量", HttpStatus.BAD_REQUEST);
      }
      BigDecimal quantityToApprove = requestedTarget.subtract(shipped).setScale(2, RoundingMode.HALF_UP);
      if (mode == WarehouseRequisitionHandlingMode.AVAILABLE_ONLY) {
        quantityToApprove = supplyWarehouse == null
            ? quantityToApprove.min(legacyAvailableStock(user.tenantId(), line.itemId()))
            : reserveRequisitionStock(user, supplyWarehouse, requisitionId, line, quantityToApprove, true);
      } else if (supplyWarehouse != null && quantityToApprove.signum() > 0) {
        quantityToApprove = reserveRequisitionStock(
            user, supplyWarehouse, requisitionId, line, quantityToApprove, false);
      }
      BigDecimal approved = shipped.add(quantityToApprove).setScale(2, RoundingMode.HALF_UP);
      warehouseRepository.updateApprovedQuantity(user.tenantId(), requisitionId, line.itemId(), approved);
      total = total.add(approved.multiply(line.unitPrice()));
      newlyApprovedTotal = newlyApprovedTotal.add(quantityToApprove);
    }
    if (mode == WarehouseRequisitionHandlingMode.AVAILABLE_ONLY
        && newlyApprovedTotal.signum() == 0) {
      throw new BusinessException(
          "NO_AVAILABLE_STOCK",
          "当前没有可发库存，请标记缺货或等待补货",
          HttpStatus.CONFLICT
      );
    }
    String nextStatus = newlyApprovedTotal.signum() > 0 ? "APPROVED" : "BACKORDERED";
    String action = newlyApprovedTotal.signum() > 0
        ? (mode == WarehouseRequisitionHandlingMode.AVAILABLE_ONLY ? "按可用库存批准叫货" : "审核叫货")
        : "标记叫货缺货";
    warehouseRepository.reviewRequisition(
        user.tenantId(), requisitionId, nextStatus, total, user.id(), request.note());
    warehouseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), action,
        requisitionId, requisition.storeId(), request.note());
  }

  private void rejectRequisition(
      AuthUser user,
      WarehouseRequisitionResponse requisition,
      String reason
  ) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(
          "REJECTION_REASON_REQUIRED",
          "驳回叫货单必须填写业务原因",
          HttpStatus.BAD_REQUEST
      );
    }
    if (requisition.lines().stream().anyMatch(line -> amount(line.shippedQuantity()).signum() > 0)) {
      throw new BusinessException(
          "REQUISITION_ALREADY_PARTIALLY_SHIPPED",
          "已部分发货的叫货单不能整单驳回",
          HttpStatus.CONFLICT
      );
    }
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      warehouseRepository.updateApprovedQuantity(
          user.tenantId(), requisition.id(), line.itemId(), BigDecimal.ZERO);
    }
    warehouseRepository.reviewRequisition(
        user.tenantId(), requisition.id(), "REJECTED", BigDecimal.ZERO, user.id(), reason);
    warehouseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(), "驳回叫货",
        requisition.id(), requisition.storeId(), reason);
  }

  private void markRequisitionBackordered(
      AuthUser user,
      WarehouseRequisitionResponse requisition,
      WarehouseRequisitionHandlingMode mode,
      String note
  ) {
    BigDecimal total = BigDecimal.ZERO;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal shipped = amount(line.shippedQuantity());
      warehouseRepository.updateApprovedQuantity(
          user.tenantId(), requisition.id(), line.itemId(), shipped);
      total = total.add(shipped.multiply(line.unitPrice()));
    }
    String status = mode == WarehouseRequisitionHandlingMode.WAIT_REPLENISHMENT
        ? "WAITING_REPLENISHMENT"
        : "BACKORDERED";
    String resolvedNote = note == null || note.isBlank()
        ? (mode == WarehouseRequisitionHandlingMode.WAIT_REPLENISHMENT
            ? "等待补货后继续发货"
            : "已标记缺货，待安排补货")
        : note.trim();
    warehouseRepository.reviewRequisition(
        user.tenantId(), requisition.id(), status, total, user.id(), resolvedNote);
    warehouseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(),
        mode == WarehouseRequisitionHandlingMode.WAIT_REPLENISHMENT ? "叫货等待补货" : "标记叫货缺货",
        requisition.id(), requisition.storeId(), resolvedNote);
  }

  private BigDecimal legacyAvailableStock(long tenantId, long itemId) {
    return warehouseRepository.positiveBatchesForUpdate(tenantId, itemId).stream()
        .map(WarehouseStockBatchRow::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  @Transactional
  public void ship(AuthUser user, String requisitionId) {
    if (topologyService == null) {
      requireRequisitionReview(user);
    }
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    FacilityRow supplyWarehouse = topologyService == null ? null
        : requisitionFacility(user, requisition.id());
    if (supplyWarehouse == null) {
      requireWarehouseStoreScope(user, requisition.storeId(), "处理叫货配货", true);
    } else {
      topologyService.requireRequisitionProcess(user, supplyWarehouse, "处理叫货配货");
    }
    if (!(supplyWarehouse == null ? List.of("SUBMITTED", "APPROVED") : List.of("APPROVED")).contains(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "只有已审核的叫货单可以配货", HttpStatus.CONFLICT);
    }
    Map<Long, BigDecimal> shipmentQuantities = new HashMap<>();
    boolean hasBackorder = false;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal shipped = amount(line.shippedQuantity());
      BigDecimal approvedTarget = "SUBMITTED".equals(requisition.status())
          ? amount(line.requestedQuantity())
          : amount(line.approvedQuantity());
      BigDecimal quantity = approvedTarget.subtract(shipped).setScale(2, RoundingMode.HALF_UP);
      if (quantity.signum() <= 0) {
        hasBackorder = hasBackorder || shipped.compareTo(line.requestedQuantity()) < 0;
        continue;
      }
      if (supplyWarehouse == null) {
        deductStock(user, requisition, line.itemId(), quantity);
      } else {
        shipReservedRequisitionStock(user, supplyWarehouse, requisition, line, quantity);
      }
      BigDecimal cumulativeShipped = shipped.add(quantity).setScale(2, RoundingMode.HALF_UP);
      warehouseRepository.updateShippedQuantity(
          user.tenantId(), requisitionId, line.itemId(), cumulativeShipped);
      shipmentQuantities.put(line.itemId(), quantity);
      hasBackorder = hasBackorder || cumulativeShipped.compareTo(line.requestedQuantity()) < 0;
    }
    if (shipmentQuantities.isEmpty()) {
      throw new BusinessException("NOTHING_TO_SHIP", "当前没有已批准且可发出的商品", HttpStatus.CONFLICT);
    }
    String shipmentStatus = hasBackorder ? "PARTIALLY_SHIPPED" : "SHIPPED";
    warehouseRepository.markShipped(user.tenantId(), requisitionId, shipmentStatus, user.id());
    createDeliveryForShipment(
        user,
        requisition,
        supplyWarehouse == null ? null : supplyWarehouse.id(),
        shipmentQuantities,
        hasBackorder
    );
    warehouseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(),
        hasBackorder ? "部分配货" : "完成配货",
        requisitionId, requisition.storeId(),
        hasBackorder ? "已按可用库存发货，剩余数量待补货" : "仓库出库");
    warehouseRepository.insertTodoAction(
        "todo-act-" + UUID.randomUUID(),
        user.tenantId(),
        "warehouse-" + requisitionId,
        "WAREHOUSE_SHIP",
        hasBackorder ? "仓库已部分发货，剩余数量转为缺货待处理" : "仓库已发货，仓库待办已完成",
        user.id(),
        user.displayName(),
        user.role()
    );
  }

  @Transactional
  public void receiveByStore(AuthUser user, String requisitionId, WarehouseReceiptRequest request) {
    requireStoreReceiver(user);
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    requireWarehouseStoreScope(user, requisition.storeId(), "确认门店收货", false);
    if ("RECEIVED".equals(requisition.status())) {
      return;
    }
    if (!List.of("SHIPPED", "PARTIALLY_SHIPPED").contains(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "这张叫货单还没有发货，不能确认收货", HttpStatus.CONFLICT);
    }
    FacilityRow supplyWarehouse = topologyService == null ? null
        : requisitionFacility(user, requisition.id());
    if (supplyWarehouse != null) {
      topologyService.requireVisibleFacility(user, supplyWarehouse.id(), "确认门店收货");
    }
    WarehouseDeliveryResponse delivery = createDeliveryForRequisition(user, requisition,
        supplyWarehouse == null ? null : supplyWarehouse.id());
    String receiptId = "RCV" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    if (supplyWarehouse == null) {
      warehouseRepository.insertReceipt(user.tenantId(), receiptId, delivery.id(), requisition.id(), requisition.storeId(), user.id(), request.note());
    } else {
      warehouseRepository.insertReceipt(user.tenantId(), supplyWarehouse.id(), receiptId,
          delivery.id(), requisition.id(), requisition.storeId(), user.id(), request.note());
    }
    for (WarehouseDeliveryLineResponse line : delivery.lines()) {
      warehouseRepository.insertReceiptLine(user.tenantId(), receiptId, line.itemId(), line.shippedQuantity(), request.note());
      warehouseRepository.updateDeliveryLineReceived(user.tenantId(), delivery.id(), line.itemId(), line.shippedQuantity());
      warehouseRepository.addStoreInventory(
          user.tenantId(),
          requisition.storeId(),
          line.itemId(),
          line.shippedQuantity(),
          "IN",
          "STORE_RECEIPT",
          receiptId,
          request.note(),
          user.id()
      );
    }
    warehouseRepository.markReceived(user.tenantId(), delivery.id(), user.id());
    boolean hasBackorder = requisition.lines().stream()
        .anyMatch(line -> amount(line.shippedQuantity()).compareTo(line.requestedQuantity()) < 0);
    String nextStatus = hasBackorder ? "BACKORDERED" : "RECEIVED";
    warehouseRepository.markRequisitionReceived(
        user.tenantId(), requisitionId, nextStatus, user.id(), request.note());
    warehouseRepository.logAction(
        user.tenantId(), user.id(), user.displayName(),
        hasBackorder ? "门店确认部分收货" : "门店确认收货",
        requisitionId, requisition.storeId(), request.note());
    warehouseRepository.insertTodoAction(
        "todo-act-" + UUID.randomUUID(),
        user.tenantId(),
        "store-receipt-" + requisitionId,
        "STORE_RECEIVE",
        hasBackorder ? "门店已确认本次收货，剩余数量待仓库补发" : "门店已确认收货，叫货流程完成",
        user.id(),
        user.displayName(),
        user.role()
    );
  }

  private WarehouseDeliveryResponse createDeliveryForRequisition(AuthUser user, WarehouseRequisitionResponse requisition) {
    return createDeliveryForRequisition(user, requisition, null);
  }

  @Transactional
  public WarehousePurchaseOrderResponse approvePurchaseOrder(AuthUser user, String purchaseOrderId) {
    var lock = warehouseRepository.purchaseOrderForUpdate(user.tenantId(), purchaseOrderId)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
    FacilityRow facility = topologyService.requirePurchaseWarehouse(
        user, lock.warehouseId(), "审批外部采购单");
    if ("ORDERED".equals(lock.status())) {
      return warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId).orElseThrow();
    }
    if (!"DRAFT".equals(lock.status()) || warehouseRepository.markPurchaseOrdered(
        user.tenantId(), purchaseOrderId) != 1) {
      throw new BusinessException("PO_STATUS_CONFLICT", "只有草稿采购单可以审批", HttpStatus.CONFLICT);
    }
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(),
        "审批外部采购单", purchaseOrderId, null, facility.name());
    return warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public WarehousePurchaseOrderResponse receivePurchaseOrder(
      AuthUser user, String purchaseOrderId, WarehousePurchaseReceiveRequest request
  ) {
    var lock = warehouseRepository.purchaseOrderForUpdate(user.tenantId(), purchaseOrderId)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
    FacilityRow facility = topologyService.requirePurchaseWarehouse(
        user, lock.warehouseId(), "确认外部采购入库");
    if ("RECEIVED".equals(lock.status())) {
      return warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId).orElseThrow();
    }
    if (!"ORDERED".equals(lock.status())) {
      throw new BusinessException("PO_STATUS_CONFLICT", "只有已审批采购单可以入库", HttpStatus.CONFLICT);
    }
    String requestKey = normalizeClientRequestId(request.clientRequestId());
    String requestType = "PURCHASE_RECEIVE:" + purchaseOrderId;
    if (requestKey == null) {
      throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "采购入库必须提供请求编号", HttpStatus.BAD_REQUEST);
    }
    if (!warehouseRepository.reserveRequest(user.tenantId(), requestType, requestKey)) {
      return warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId)
          .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
    }
    WarehousePurchaseOrderResponse order = warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
    Map<Long, WarehousePurchaseOrderLineResponse> orderedLines = order.lines().stream()
        .collect(Collectors.toMap(WarehousePurchaseOrderLineResponse::itemId, Function.identity()));
    if (request.lines().size() != orderedLines.size()) {
      throw new BusinessException("PO_RECEIVE_LINES_INCOMPLETE", "采购入库必须完整核对全部采购明细", HttpStatus.BAD_REQUEST);
    }
    Set<Long> receivedItems = new java.util.HashSet<>();
    for (WarehousePurchaseReceiveLineRequest line : request.lines()) {
      WarehousePurchaseOrderLineResponse ordered = orderedLines.get(line.itemId());
      if (ordered == null || !receivedItems.add(line.itemId())) {
        throw new BusinessException("PO_RECEIVE_LINE_INVALID", "采购入库明细与采购单不一致", HttpStatus.BAD_REQUEST);
      }
      BigDecimal quantity = positive(line.quantity(), "采购入库数量");
      if (quantity.compareTo(ordered.orderedQuantity()) != 0) {
        throw new BusinessException("PO_RECEIVE_QUANTITY_MISMATCH", "采购入库数量必须与已审批数量一致", HttpStatus.BAD_REQUEST);
      }
      LocalDate receivedDate = parseDate(line.receivedDate(), "到货日期");
      if (line.expiryDate() != null && !line.expiryDate().isBlank()
          && parseDate(line.expiryDate(), "到期日期").isBefore(receivedDate)) {
        throw new BusinessException("BAD_EXPIRY_DATE", "到期日期不能早于到货日期", HttpStatus.BAD_REQUEST);
      }
      WarehouseStockBatchRequest batchRequest = new WarehouseStockBatchRequest(
          line.itemId(), line.batchNo(), line.receivedDate(), line.expiryDate(), quantity,
          ordered.unitCost(), line.note(), requestKey, facility.id());
      warehouseRepository.upsertBatch(user.tenantId(), facility.id(), batchRequest);
      Long batchId = warehouseRepository.batchId(
          user.tenantId(), facility.id(), line.itemId(), line.batchNo()).orElse(null);
      InventoryRow inventory = topologyRepository.lockInventory(
          user.tenantId(), facility.id(), line.itemId());
      BigDecimal cost = weightedCost(inventory.onHand(), inventory.unitCost(), quantity, ordered.unitCost());
      if (!topologyRepository.updateInventory(user.tenantId(), inventory,
          inventory.onHand().add(quantity), inventory.reserved(), inventory.inTransit(), cost)) {
        throw warehouseConcurrentUpdate();
      }
      topologyRepository.insertMovement(user.tenantId(), facility.id(), line.itemId(), batchId,
          "PURCHASE_IN", quantity, BigDecimal.ZERO, BigDecimal.ZERO, ordered.unitCost(),
          "PURCHASE_ORDER", purchaseOrderId, null,
          line.note() == null ? "采购单入库" : line.note(), user.id());
      if (warehouseRepository.setPurchaseLineReceived(
          user.tenantId(), purchaseOrderId, line.itemId(), quantity) != 1) {
        throw warehouseConcurrentUpdate();
      }
    }
    if (warehouseRepository.markPurchaseReceived(user.tenantId(), purchaseOrderId, user.id()) != 1) {
      throw warehouseConcurrentUpdate();
    }
    warehouseRepository.completeReservedRequest(user.tenantId(), requestType, requestKey, purchaseOrderId);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(),
        "采购单确认入库", purchaseOrderId, null, request.note());
    return warehouseRepository.purchaseOrder(user.tenantId(), purchaseOrderId)
        .orElseThrow(() -> new BusinessException("PO_NOT_FOUND", "采购单不存在", HttpStatus.NOT_FOUND));
  }

  private WarehouseDeliveryResponse createDeliveryForRequisition(
      AuthUser user, WarehouseRequisitionResponse requisition, Long warehouseId
  ) {
    return warehouseRepository.deliveryByRequisition(user.tenantId(), requisition.id())
        .orElseGet(() -> repairDeliveryForShippedRequisition(user, requisition, warehouseId));
  }

  private WarehouseDeliveryResponse createDeliveryForShipment(
      AuthUser user,
      WarehouseRequisitionResponse requisition,
      Long warehouseId,
      Map<Long, BigDecimal> shipmentQuantities,
      boolean partial
  ) {
    String deliveryId = "DO" + System.currentTimeMillis() + "-"
        + UUID.randomUUID().toString().substring(0, 6);
    String note = partial ? "门店叫货部分发货" : "门店叫货发货";
    if (warehouseId == null) {
      warehouseRepository.insertDelivery(
          user.tenantId(), deliveryId, requisition.id(), requisition.storeId(), user.id(), note);
    } else {
      warehouseRepository.insertDelivery(
          user.tenantId(), warehouseId, deliveryId, requisition.id(),
          requisition.storeId(), user.id(), note);
    }
    int createdLines = 0;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal quantity = amount(shipmentQuantities.get(line.itemId()));
      if (quantity.signum() <= 0) {
        continue;
      }
      warehouseRepository.insertDeliveryLine(
          user.tenantId(), deliveryId, line.id(), line.itemId(), quantity, line.unitPrice());
      createdLines++;
    }
    if (createdLines == 0) {
      throw new BusinessException(
          "REQUISITION_LINES_MISSING",
          "叫货单缺少本次发货明细",
          HttpStatus.CONFLICT
      );
    }
    return warehouseRepository.deliveryByRequisition(user.tenantId(), requisition.id())
        .orElseThrow(() -> new BusinessException(
            "DELIVERY_CREATE_FAILED",
            "配送单创建失败，请刷新后重试",
            HttpStatus.CONFLICT
        ));
  }

  private WarehouseDeliveryResponse repairDeliveryForShippedRequisition(AuthUser user, WarehouseRequisitionResponse requisition) {
    return repairDeliveryForShippedRequisition(user, requisition, null);
  }

  private WarehouseDeliveryResponse repairDeliveryForShippedRequisition(
      AuthUser user, WarehouseRequisitionResponse requisition, Long warehouseId
  ) {
    if (!List.of("SHIPPED", "PARTIALLY_SHIPPED").contains(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "这张叫货单还没有发货，不能确认收货", HttpStatus.CONFLICT);
    }
    Map<Long, BigDecimal> movementQuantities = warehouseRepository.shippedQuantitiesFromMovements(user.tenantId(), requisition.id());
    String deliveryId = "DO-" + requisition.id();
    if (warehouseId == null) {
      warehouseRepository.insertDelivery(user.tenantId(), deliveryId, requisition.id(), requisition.storeId(), user.id(), "自动补建配送单");
    } else {
      warehouseRepository.insertDelivery(user.tenantId(), warehouseId, deliveryId, requisition.id(),
          requisition.storeId(), user.id(), "自动补建配送单");
    }
    int createdLines = 0;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal quantity = amount(line.shippedQuantity());
      if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
        quantity = amount(movementQuantities.get(line.itemId()));
      }
      if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      warehouseRepository.insertDeliveryLine(
          user.tenantId(),
          deliveryId,
          line.id(),
          line.itemId(),
          quantity,
          line.unitPrice()
      );
      createdLines++;
    }
    if (createdLines == 0) {
      throw new BusinessException("REQUISITION_LINES_MISSING", "叫货单缺少商品明细，无法确认收货，请联系仓库管理员", HttpStatus.CONFLICT);
    }
    return warehouseRepository.deliveryByRequisition(user.tenantId(), requisition.id())
        .orElseThrow(() -> new BusinessException("DELIVERY_REPAIR_FAILED", "叫货单配送明细修复失败，请联系仓库管理员", HttpStatus.CONFLICT));
  }

  private BigDecimal reserveRequisitionStock(
      AuthUser user,
      FacilityRow facility,
      String requisitionId,
      WarehouseRequisitionLineResponse line,
      BigDecimal requestedQuantity,
      boolean capToAvailable
  ) {
    InventoryRow inventory = topologyRepository.lockInventory(user.tenantId(), facility.id(), line.itemId());
    BigDecimal quantity = amount(requestedQuantity);
    if (capToAvailable) {
      quantity = quantity.min(inventory.available()).setScale(2, RoundingMode.HALF_UP);
    }
    if (quantity.signum() <= 0) {
      return amount(BigDecimal.ZERO);
    }
    if (inventory.available().compareTo(quantity) < 0) {
      throw new BusinessException("INSUFFICIENT_STOCK", line.itemName() + "可用库存不足", HttpStatus.CONFLICT);
    }
    BigDecimal remaining = amount(quantity);
    for (BatchRow batch : topologyRepository.positiveBatchesForUpdate(user.tenantId(), facility.id(), line.itemId())) {
      BigDecimal available = batch.quantity().subtract(batch.reservedQuantity());
      BigDecimal used = available.min(remaining);
      if (used.signum() > 0 && !topologyRepository.updateBatchQuantity(user.tenantId(), batch,
          batch.quantity(), batch.reservedQuantity().add(used))) {
        throw warehouseConcurrentUpdate();
      }
      remaining = remaining.subtract(used);
      if (remaining.signum() == 0) {
        break;
      }
    }
    if (remaining.signum() > 0) {
      throw new BusinessException("WAREHOUSE_BATCH_STOCK_INCONSISTENT", "批次库存与库存汇总不一致", HttpStatus.CONFLICT);
    }
    if (!topologyRepository.updateInventory(user.tenantId(), inventory, inventory.onHand(),
        inventory.reserved().add(quantity), inventory.inTransit(), inventory.unitCost())) {
      throw warehouseConcurrentUpdate();
    }
    topologyRepository.insertMovement(user.tenantId(), facility.id(), line.itemId(), null, "RESERVE",
        BigDecimal.ZERO, quantity, BigDecimal.ZERO, inventory.unitCost(), "REQUISITION", requisitionId,
        null, "门店叫货审批预占", user.id());
    return quantity;
  }

  private void shipReservedRequisitionStock(
      AuthUser user,
      FacilityRow facility,
      WarehouseRequisitionResponse requisition,
      WarehouseRequisitionLineResponse line,
      BigDecimal quantity
  ) {
    InventoryRow inventory = topologyRepository.lockInventory(user.tenantId(), facility.id(), line.itemId());
    if (inventory.onHand().compareTo(quantity) < 0 || inventory.reserved().compareTo(quantity) < 0) {
      throw new BusinessException("WAREHOUSE_RESERVATION_INVALID", "叫货单预占库存不足", HttpStatus.CONFLICT);
    }
    BigDecimal remaining = amount(quantity);
    for (BatchRow batch : topologyRepository.positiveBatchesForUpdate(user.tenantId(), facility.id(), line.itemId())) {
      BigDecimal used = batch.reservedQuantity().min(remaining);
      if (used.signum() > 0) {
        if (batch.quantity().compareTo(used) < 0 || !topologyRepository.updateBatchQuantity(
            user.tenantId(), batch, batch.quantity().subtract(used), batch.reservedQuantity().subtract(used))) {
          throw warehouseConcurrentUpdate();
        }
        topologyRepository.insertMovement(user.tenantId(), facility.id(), line.itemId(), batch.id(), "OUT",
            used.negate(), used.negate(), BigDecimal.ZERO, batch.unitCost(), "REQUISITION",
            requisition.id(), requisition.storeId(), "门店叫货出库", user.id());
        remaining = remaining.subtract(used);
      }
      if (remaining.signum() == 0) {
        break;
      }
    }
    if (remaining.signum() > 0) {
      throw new BusinessException("WAREHOUSE_RESERVATION_INVALID", "叫货单批次预占库存不足", HttpStatus.CONFLICT);
    }
    BigDecimal remainingCost = remainingBatchCost(user.tenantId(), facility.id(), line.itemId());
    if (!topologyRepository.updateInventory(user.tenantId(), inventory,
        inventory.onHand().subtract(quantity), inventory.reserved().subtract(quantity),
        inventory.inTransit(), remainingCost)) {
      throw warehouseConcurrentUpdate();
    }
  }

  private BigDecimal remainingBatchCost(long tenantId, long warehouseId, long itemId) {
    BigDecimal quantity = BigDecimal.ZERO;
    BigDecimal value = BigDecimal.ZERO;
    for (BatchRow batch : topologyRepository.positiveBatchesForUpdate(tenantId, warehouseId, itemId)) {
      quantity = quantity.add(batch.quantity());
      value = value.add(batch.quantity().multiply(batch.unitCost()));
    }
    return quantity.signum() == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
        : value.divide(quantity, 4, RoundingMode.HALF_UP);
  }

  private BusinessException warehouseConcurrentUpdate() {
    return new BusinessException("WAREHOUSE_CONCURRENT_UPDATE", "库存已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT);
  }

  private void deductStock(AuthUser user, WarehouseRequisitionResponse requisition, long itemId, BigDecimal quantity) {
    BigDecimal remaining = amount(quantity);
    List<WarehouseStockBatchRow> batches = warehouseRepository.positiveBatchesForUpdate(user.tenantId(), itemId);
    BigDecimal available = batches.stream().map(WarehouseStockBatchRow::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (available.compareTo(remaining) < 0) {
      throw new BusinessException("INSUFFICIENT_STOCK", "仓库库存不足，无法配货", HttpStatus.CONFLICT);
    }
    for (WarehouseStockBatchRow batch : batches) {
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      BigDecimal used = batch.quantity().min(remaining);
      BigDecimal after = batch.quantity().subtract(used).setScale(2, RoundingMode.HALF_UP);
      warehouseRepository.updateBatchQuantity(user.tenantId(), batch.id(), after);
      warehouseRepository.insertMovement(
          user.tenantId(),
          itemId,
          batch.id(),
          "OUT",
          used.negate(),
          "REQUISITION",
          requisition.id(),
          requisition.storeId(),
          "门店叫货配货",
          user.id()
      );
      remaining = remaining.subtract(used);
    }
  }

  private List<WarehouseRequisitionResponse> scopedRequisitions(AuthUser user, WarehouseReadScope readScope) {
    List<WarehouseRequisitionResponse> rows = readScope.central() || readScope.allStores()
        ? warehouseRepository.requisitions(user.tenantId(), null)
        : readScope.storeIds().stream()
            .flatMap(storeId -> warehouseRepository.requisitions(user.tenantId(), storeId).stream())
            .distinct()
            .sorted(Comparator.comparing(
                WarehouseRequisitionResponse::submittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
            ))
            .limit(80)
            .toList();
    return withReturnAvailability(user.tenantId(), rows);
  }

  private List<WarehouseDeliveryResponse> scopedDeliveries(
      AuthUser user,
      WarehouseReadScope readScope,
      int limit
  ) {
    if (readScope.central() || readScope.allStores()) {
      return warehouseRepository.deliveries(user.tenantId(), null);
    }
    return readScope.storeIds().stream()
        .flatMap(storeId -> warehouseRepository.deliveries(user.tenantId(), storeId).stream())
        .distinct()
        .sorted(Comparator.comparing(
            WarehouseDeliveryResponse::shippedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ))
        .limit(Math.max(1, limit))
        .toList();
  }

  private List<WarehouseStockMovementResponse> scopedMovements(
      AuthUser user,
      WarehouseReadScope readScope,
      int limit
  ) {
    if (readScope.central()) {
      return warehouseRepository.movements(user.tenantId(), null, limit);
    }
    if (readScope.allStores()) {
      return warehouseRepository.movements(user.tenantId(), null, limit).stream()
          .filter(row -> row.storeId() != null && !row.storeId().isBlank())
          .toList();
    }
    return readScope.storeIds().stream()
        .flatMap(storeId -> warehouseRepository.movements(user.tenantId(), storeId, limit).stream())
        .distinct()
        .sorted(Comparator.comparing(
            WarehouseStockMovementResponse::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ))
        .limit(Math.max(1, limit))
        .toList();
  }

  private List<WarehouseReturnResponse> scopedReturns(AuthUser user, WarehouseReadScope readScope) {
    if (readScope.central() || readScope.allStores()) {
      return warehouseRepository.returns(user.tenantId(), null);
    }
    return readScope.storeIds().stream()
        .flatMap(storeId -> warehouseRepository.returns(user.tenantId(), storeId).stream())
        .distinct()
        .sorted(Comparator.comparing(
            WarehouseReturnResponse::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ))
        .limit(120)
        .toList();
  }

  private List<WarehouseRequisitionResponse> withReturnAvailability(long tenantId, List<WarehouseRequisitionResponse> requisitions) {
    return requisitions.stream()
        .map(row -> new WarehouseRequisitionResponse(
            row.id(),
            row.storeId(),
            row.storeName(),
            row.warehouseId(),
            row.warehouseName(),
            row.status(),
            row.statusLabel(),
            row.totalAmount(),
            row.note(),
            row.submittedBy(),
            row.reviewedBy(),
            row.shippedBy(),
            row.receivedBy(),
            row.submittedAt(),
            row.reviewedAt(),
            row.shippedAt(),
            row.receivedAt(),
            row.lines().stream()
                .map(line -> withReturnAvailability(tenantId, row, line))
                .toList()
        ))
        .toList();
  }

  private WarehouseRequisitionLineResponse withReturnAvailability(
      long tenantId,
      WarehouseRequisitionResponse requisition,
      WarehouseRequisitionLineResponse line
  ) {
    ReturnAvailability availability = returnAvailability(tenantId, requisition.storeId(), requisition, line);
    return new WarehouseRequisitionLineResponse(
        line.id(),
        line.itemId(),
        line.itemName(),
        line.unit(),
        line.requestedQuantity(),
        line.approvedQuantity(),
        line.shippedQuantity(),
        line.unitPrice(),
        line.amount(),
        line.warningText(),
        line.note(),
        availability.receivedQuantity(),
        availability.returnedQuantity(),
        availability.sourceAvailableReturnQuantity(),
        availability.storeInventoryQuantity(),
        availability.availableReturnQuantity()
    );
  }

  private ReturnAvailability returnAvailability(
      long tenantId,
      String storeId,
      WarehouseRequisitionResponse requisition,
      WarehouseRequisitionLineResponse line
  ) {
    BigDecimal shippedQuantity = amount(line.shippedQuantity());
    BigDecimal receivedQuantity = warehouseRepository.receivedQuantityForRequisitionItem(tenantId, requisition.id(), line.itemId());
    BigDecimal sourceQuantity = receivedQuantity.compareTo(BigDecimal.ZERO) > 0 ? receivedQuantity : shippedQuantity;
    BigDecimal returnedQuantity = warehouseRepository.returnedQuantityForRequisitionItem(tenantId, requisition.id(), line.itemId());
    BigDecimal sourceAvailableQuantity = sourceQuantity.subtract(returnedQuantity).setScale(2, RoundingMode.HALF_UP);
    if (sourceAvailableQuantity.compareTo(BigDecimal.ZERO) < 0) {
      sourceAvailableQuantity = amount(BigDecimal.ZERO);
    }
    BigDecimal storeInventoryQuantity = warehouseRepository.storeInventoryQuantity(tenantId, storeId, line.itemId());
    BigDecimal availableReturnQuantity = amount(sourceAvailableQuantity.min(storeInventoryQuantity));
    if (availableReturnQuantity.compareTo(BigDecimal.ZERO) < 0) {
      availableReturnQuantity = amount(BigDecimal.ZERO);
    }
    return new ReturnAvailability(
        amount(receivedQuantity),
        amount(returnedQuantity),
        sourceAvailableQuantity,
        amount(storeInventoryQuantity),
        availableReturnQuantity
    );
  }

  private WarehouseRequisitionResponse requireRequisition(long tenantId, String requisitionId) {
    return warehouseRepository.requisition(tenantId, requisitionId)
        .orElseThrow(() -> new BusinessException("REQ_NOT_FOUND", "叫货单不存在", HttpStatus.NOT_FOUND));
  }

  private WarehouseRequisitionResponse requireRequisitionForUpdate(long tenantId, String requisitionId) {
    return warehouseRepository.requisitionForUpdate(tenantId, requisitionId)
        .orElseThrow(() -> new BusinessException("REQ_NOT_FOUND", "叫货单不存在", HttpStatus.NOT_FOUND));
  }

  private List<WarehouseAlertResponse> alerts(List<WarehouseItemResponse> items) {
    return items.stream()
        .filter(item -> !"OK".equals(item.alertLevel()))
        .map(item -> new WarehouseAlertResponse(
            "LOW".equals(item.alertLevel()) ? "WARN" : "RISK",
            item.alertLevel(),
            item.id(),
            item.name(),
            item.alertText()
        ))
        .toList();
  }

  private List<WarehouseItemResponse> safeItems(List<WarehouseItemResponse> items, long tenantId, List<String> storeIds) {
    Map<Long, BigDecimal> storeStocks = new HashMap<>();
    for (String storeId : storeIds) {
      warehouseRepository.storeInventoryQuantities(tenantId, storeId)
          .forEach((itemId, quantity) -> storeStocks.merge(itemId, amount(quantity), BigDecimal::add));
    }
    return items.stream()
        .filter(WarehouseItemResponse::active)
        .map(item -> {
          BigDecimal storeStock = amount(storeStocks.get(item.id()));
          BigDecimal warehouseAvailable = amount(item.warehouseAvailableQuantity() == null ? item.stockQuantity() : item.warehouseAvailableQuantity());
          String storeStatus = storeStockStatus(storeStock, item.minStockQuantity());
          String storeAlertLevel = "暂无库存".equals(storeStatus) ? "OUT" : ("需要补货".equals(storeStatus) ? "LOW" : "NORMAL");
          return new WarehouseItemResponse(
              item.id(),
              item.code(),
              item.name(),
              item.categoryId(),
              item.categoryName(),
              item.category(),
              item.imageUrl(),
              item.unit(),
              item.purchaseUnit(),
              item.stockUnit(),
              item.ingredientUnit(),
              item.unitConversionText(),
              item.spec(),
              item.warehouseLocation(),
              amount(BigDecimal.ZERO),
              item.shelfLifeDays(),
              item.cupsPerUnit(),
              item.dailyUsageEstimate(),
              item.minStockDays(),
              item.maxStockDays(),
              item.minStockQuantity(),
              item.alertEnabled(),
              item.expiryAlertDays(),
              item.active(),
              storeStock,
              storeStock,
              warehouseAvailable,
              amount(BigDecimal.ZERO),
              item.daysAvailable(),
              item.nearestExpiryDate(),
              storeStatus,
              storeAlertLevel,
              "本店库存 " + qty(storeStock) + unitText(item.unit()) + "，公司仓库可配送 " + qty(warehouseAvailable) + unitText(item.unit()),
              item.itemDescription(),
              item.sortOrder(),
              item.itemAttributes(),
              List.of()
          );
        })
        .toList();
  }

  private List<WarehouseRequisitionResponse> safeRequisitions(List<WarehouseRequisitionResponse> requisitions) {
    return requisitions.stream()
        .map(row -> new WarehouseRequisitionResponse(
            row.id(),
            row.storeId(),
            row.storeName(),
            row.warehouseId(),
            row.warehouseName(),
            row.status(),
            row.statusLabel(),
            amount(BigDecimal.ZERO),
            row.note(),
            row.submittedBy(),
            row.reviewedBy(),
            row.shippedBy(),
            row.receivedBy(),
            row.submittedAt(),
            row.reviewedAt(),
            row.shippedAt(),
            row.receivedAt(),
            row.lines().stream()
                .map(line -> new WarehouseRequisitionLineResponse(
                    line.id(),
                    line.itemId(),
                    line.itemName(),
                    line.unit(),
                    line.requestedQuantity(),
                    line.approvedQuantity(),
                    line.shippedQuantity(),
                    amount(BigDecimal.ZERO),
                    amount(BigDecimal.ZERO),
                    line.warningText(),
                    line.note(),
                    line.receivedQuantity(),
                    line.returnedQuantity(),
                    line.sourceAvailableReturnQuantity(),
                    line.storeInventoryQuantity(),
                    line.availableReturnQuantity()
                ))
                .toList()
        ))
        .toList();
  }

  private List<WarehouseDeliveryResponse> safeDeliveries(List<WarehouseDeliveryResponse> deliveries) {
    return deliveries.stream()
        .map(row -> new WarehouseDeliveryResponse(
            row.id(),
            row.requisitionId(),
            row.storeId(),
            row.storeName(),
            row.status(),
            row.statusLabel(),
            row.shippedBy(),
            row.receivedBy(),
            row.shippedAt(),
            row.receivedAt(),
            row.lines().stream()
                .map(line -> new WarehouseDeliveryLineResponse(
                    line.id(),
                    line.itemId(),
                    line.itemName(),
                    line.unit(),
                    line.shippedQuantity(),
                    line.receivedQuantity(),
                    amount(BigDecimal.ZERO),
                    amount(BigDecimal.ZERO)
                ))
                .toList()
        ))
        .toList();
  }

  private List<WarehouseReturnResponse> safeReturns(List<WarehouseReturnResponse> returns) {
    return returns.stream().map(this::safeReturn).toList();
  }

  private WarehouseReturnResponse safeReturn(WarehouseReturnResponse row) {
    return new WarehouseReturnResponse(
        row.id(),
        row.returnNo(),
        row.sourceRequisitionId(),
        row.sourceDeliveryId(),
        row.returnStoreId(),
        row.returnStoreName(),
        row.receiveWarehouseId(),
        row.receiveWarehouseName(),
        row.receiveDepartment(),
        row.status(),
        row.statusLabel(),
        amount(BigDecimal.ZERO),
        row.handledBy(),
        row.createdBy(),
        row.updatedBy(),
        row.reviewedBy(),
        row.checkedBy(),
        row.reason(),
        row.note(),
        row.reviewNote(),
        row.receivedNote(),
        row.returnDate(),
        row.reviewedAt(),
        row.receivedAt(),
        row.createdAt(),
        row.updatedAt(),
        row.lineCount(),
        row.attachmentCount(),
        row.lines().stream()
            .map(line -> new WarehouseReturnLineResponse(
                line.id(),
                line.itemId(),
                line.itemName(),
                line.spec(),
                line.batchId(),
                line.batchNo(),
                line.sourceRequisitionLineId(),
                line.quantity(),
                line.unit(),
                amount(BigDecimal.ZERO),
                amount(BigDecimal.ZERO),
                amount(BigDecimal.ZERO),
                line.reason(),
                line.note()
            ))
            .toList()
    );
  }

  private List<WarehouseItemCategoryResponse> categoryTree(List<WarehouseItemCategoryResponse> categories) {
    return categoryChildren(categories, null);
  }

  private List<WarehouseItemCategoryResponse> categoryChildren(
      List<WarehouseItemCategoryResponse> categories,
      Long parentId
  ) {
    return categories.stream()
        .filter(row -> Objects.equals(row.parentId(), parentId))
        .map(row -> new WarehouseItemCategoryResponse(
            row.id(),
            row.name(),
            row.parentId(),
            row.sortOrder(),
            row.enabled(),
            categoryChildren(categories, row.id())
        ))
        .toList();
  }

  private String warningForOrder(WarehouseItemResponse item, BigDecimal quantity) {
    if (quantity.compareTo(item.stockQuantity()) > 0) {
      return "仓库当前库存不足，最多可配 " + item.stockQuantity().stripTrailingZeros().toPlainString() + item.unit();
    }
    return null;
  }

  private String storeStockStatus(BigDecimal storeStock, BigDecimal minStockQuantity) {
    BigDecimal stock = amount(storeStock);
    BigDecimal min = amount(minStockQuantity);
    if (stock.compareTo(BigDecimal.ZERO) <= 0) {
      return "暂无库存";
    }
    if (min.compareTo(BigDecimal.ZERO) > 0 && stock.compareTo(min) < 0) {
      return "需要补货";
    }
    return "库存充足";
  }

  private String qty(BigDecimal value) {
    return amount(value).stripTrailingZeros().toPlainString();
  }

  private String unitText(String unit) {
    return unit == null || unit.isBlank() ? "" : unit.trim();
  }

  private String normalizeStoreForSubmit(AuthUser user, String requestedStoreId) {
    if (businessScopeResolver != null && isStoreManager(user)) {
      return businessScopeResolver.resolve(
          user,
          DataScopeDomains.WAREHOUSE,
          requestedStoreId,
          null,
          "为门店提交叫货单"
      ).storeId();
    }
    String storeId = requestedStoreId == null ? "" : requestedStoreId.trim();
    DataScope dataScope = warehouseDataScope(user);
    if (storeId.isBlank() && dataScope.storeIds().size() == 1) {
      storeId = dataScope.storeIds().get(0);
    }
    if (storeId.isBlank()) {
      throw new BusinessException("STORE_REQUIRED", "请选择叫货门店", HttpStatus.BAD_REQUEST);
    }
    requireWarehouseStoreScope(user, storeId, "为门店提交叫货单", false);
    return storeId;
  }

  private boolean categoryWouldCreateCycle(long tenantId, long categoryId, long parentId) {
    Map<Long, Long> parents = new HashMap<>();
    for (WarehouseItemCategoryResponse category : warehouseRepository.itemCategories(tenantId)) {
      parents.put(category.id(), category.parentId());
    }
    Long current = parentId;
    while (current != null) {
      if (current.equals(categoryId)) {
        return true;
      }
      current = parents.get(current);
    }
    return false;
  }

  private String normalizeClientRequestId(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String requestId = value.trim();
    if (requestId.length() > 80 || !requestId.matches("[A-Za-z0-9_-]+")) {
      throw new BusinessException("BAD_REQUEST_ID", "请求标识不正确，请刷新后重试", HttpStatus.BAD_REQUEST);
    }
    return requestId;
  }

  private FacilityRow requisitionFacility(AuthUser user, String requisitionId) {
    long warehouseId = warehouseRepository.requisitionWarehouseId(user.tenantId(), requisitionId)
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "叫货单未关联有效供货仓", HttpStatus.CONFLICT));
    return topologyRepository.facility(user.tenantId(), warehouseId)
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "叫货单供货仓不存在", HttpStatus.CONFLICT));
  }

  private FacilityRow returnFacility(AuthUser user, String returnId) {
    long warehouseId = warehouseRepository.returnWarehouseId(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "退货单未关联有效供货仓", HttpStatus.CONFLICT));
    return topologyRepository.facility(user.tenantId(), warehouseId)
        .orElseThrow(() -> new BusinessException(
            "WAREHOUSE_NOT_FOUND", "退货单供货仓不存在", HttpStatus.CONFLICT));
  }

  private void validateItemImage(String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    String image = value.trim();
    if (image.startsWith("data:")) {
      int marker = image.indexOf(";base64,");
      if (marker <= "data:image/".length()) {
        throw new BusinessException("BAD_ITEM_IMAGE", "物料图片格式不正确", HttpStatus.BAD_REQUEST);
      }
      String mediaType = image.substring(5, marker).toLowerCase();
      if (!List.of("image/jpeg", "image/jpg", "image/png", "image/webp").contains(mediaType)) {
        throw new BusinessException("BAD_ITEM_IMAGE", "物料图片仅支持 JPG、PNG 或 WEBP", HttpStatus.BAD_REQUEST);
      }
      try {
        byte[] bytes = Base64.getDecoder().decode(image.substring(marker + 8));
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) {
          throw new BusinessException("ITEM_IMAGE_TOO_LARGE", "物料图片不能超过 2MB", HttpStatus.PAYLOAD_TOO_LARGE);
        }
      } catch (IllegalArgumentException ex) {
        throw new BusinessException("BAD_ITEM_IMAGE", "物料图片格式不正确", HttpStatus.BAD_REQUEST);
      }
      return;
    }
    if (!image.startsWith("https://") && !image.startsWith("http://") && !image.startsWith("/api/")) {
      throw new BusinessException("BAD_ITEM_IMAGE", "物料图片地址不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private void saveReturnAttachments(AuthUser user, String storeId, String returnId, List<WarehouseReturnAttachmentRequest> attachments) {
    if (attachments == null || attachments.isEmpty()) {
      return;
    }
    for (WarehouseReturnAttachmentRequest attachment : attachments) {
      if (attachment == null || attachment.dataBase64() == null || attachment.dataBase64().isBlank()) {
        continue;
      }
      byte[] content = decodeAttachment(attachment.dataBase64());
      if (content.length == 0) {
        continue;
      }
      String fileName = attachment.fileName() == null || attachment.fileName().isBlank()
          ? "退货附件"
          : attachment.fileName().trim();
      String contentType = attachment.contentType() == null || attachment.contentType().isBlank()
          ? "application/octet-stream"
          : attachment.contentType().trim();
      warehouseRepository.insertWarehouseAttachment(
          user.tenantId(),
          storeId,
          "WAREHOUSE_RETURN",
          returnId,
          fileName,
          contentType,
          content.length,
          content,
          user.id()
      );
    }
  }

  private byte[] decodeAttachment(String dataBase64) {
    String value = dataBase64.trim();
    int commaIndex = value.indexOf(',');
    if (commaIndex >= 0) {
      value = value.substring(commaIndex + 1);
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException("BAD_ATTACHMENT", "退货附件格式不正确", HttpStatus.BAD_REQUEST);
    }
  }

  private WarehouseReadScope requireWarehouseRead(AuthUser user) {
    BusinessScope managerScope = resolveManagerScope(user, null, "访问仓库中心");
    if (accessControl != null) {
      boolean warehousePermission = accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_READ);
      boolean centralPermission = accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ);
      if (warehousePermission) {
        accessControl.requireWarehouseRead(user);
      } else if (centralPermission) {
        accessControl.requireWarehouseCentralRead(user);
      } else {
        accessControl.requireWarehouseStoreRead(user);
      }
      DataScope dataScope = warehouseDataScope(user);
      if (managerScope != null) {
        dataScope = managerScope.dataScope();
      }
      if (dataScope.allowsAllStores()) {
        return new WarehouseReadScope(warehousePermission || centralPermission, true, List.of());
      }
      if ((warehousePermission || centralPermission)
          && (DataScopeModes.CENTRAL_WAREHOUSE.equals(dataScope.mode())
          || DataScopeModes.WAREHOUSE_LIST.equals(dataScope.mode()))) {
        return new WarehouseReadScope(true, false, List.of());
      }
      if (!dataScope.storeIds().isEmpty()) {
        return new WarehouseReadScope(false, false, dataScope.storeIds());
      }
      accessControl.requireStoreAccess(
          user,
          DataScopeDomains.WAREHOUSE,
          "__NO_WAREHOUSE_SCOPE__",
          "访问仓库中心"
      );
      throw new BusinessException("FORBIDDEN", "当前账号未配置仓库数据范围", HttpStatus.FORBIDDEN);
    }
    if (hasLegacyPermission(user, PermissionCodes.WAREHOUSE_READ)
        || hasLegacyPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ)) {
      return new WarehouseReadScope(true, true, List.of());
    }
    requireLegacyPermission(user, PermissionCodes.WAREHOUSE_STORE_READ, "无权访问仓库中心");
    DataScope dataScope = warehouseDataScope(user);
    if (dataScope.storeIds().isEmpty()) {
      throw new BusinessException("FORBIDDEN", "当前账号未配置仓库门店范围", HttpStatus.FORBIDDEN);
    }
    return new WarehouseReadScope(false, false, dataScope.storeIds());
  }

  private void requireWarehouseManage(AuthUser user) {
    rejectStoreManagerCentralAction(user, "店长不能维护总仓物料、库存或采购数据");
    if (accessControl != null) {
      accessControl.requireWarehouseCentralManage(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_CENTRAL_MANAGE,
        "仅老板或仓库管理员可维护仓库数据"
    );
  }

  private void requireWarehouseConfigure(AuthUser user) {
    rejectStoreManagerCentralAction(user, "店长不能维护仓库配置");
    if (accessControl != null) {
      if (topologyService != null) {
        accessControl.requireWarehouseConfigure(user);
      } else {
        accessControl.requireWarehouseCentralManage(user);
      }
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_CENTRAL_MANAGE,
        "仅老板或获授权仓库管理员可维护仓库配置"
    );
  }

  private void requireStoreRequisitionCreate(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireWarehouseRequisitionCreate(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_REQUISITION_CREATE,
        "仅老板或门店人员可提交叫货申请"
    );
  }

  private void requireReturnCreate(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireWarehouseRequisitionCreate(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_REQUISITION_CREATE,
        "当前角色不能新建配送退货单"
    );
  }

  private void requireReturnReview(AuthUser user) {
    rejectStoreManagerCentralAction(user, "店长不能审核或接收配送退货单");
    if (accessControl != null) {
      accessControl.requireWarehouseCentralManage(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_CENTRAL_MANAGE,
        "仅老板或仓库管理员可审核配送退货单"
    );
  }

  private WarehouseReadScope requireReturnRead(AuthUser user) {
    return requireWarehouseRead(user);
  }

  private void requireStoreInReadScope(
      AuthUser user,
      WarehouseReadScope readScope,
      String storeId,
      String action
  ) {
    if (readScope.central() || readScope.allStores() || readScope.storeIds().contains(storeId)) {
      return;
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.WAREHOUSE, storeId, action);
    }
    throw new BusinessException("FORBIDDEN", "当前账号不能访问该门店仓库数据", HttpStatus.FORBIDDEN);
  }

  private void requireStoreReceiver(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireWarehouseRequisitionReceive(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_REQUISITION_RECEIVE,
        "仅老板或对应门店可确认收货"
    );
  }

  private void requireRequisitionReview(AuthUser user) {
    rejectStoreManagerCentralAction(user, "店长不能审核叫货单或执行仓库发货");
    if (accessControl != null) {
      accessControl.requireWarehouseRequisitionReview(user);
      return;
    }
    requireLegacyPermission(
        user,
        PermissionCodes.WAREHOUSE_REQUISITION_REVIEW,
        "仅老板或仓库管理员可处理叫货申请"
    );
  }

  private void requireWarehouseStoreScope(
      AuthUser user,
      String storeId,
      String action,
      boolean allowCentralWarehouse
  ) {
    if (resolveManagerScope(user, storeId, action) != null) {
      return;
    }
    DataScope dataScope = warehouseDataScope(user);
    if (dataScope.allowsAllStores()
        || (allowCentralWarehouse && DataScopeModes.CENTRAL_WAREHOUSE.equals(dataScope.mode()))) {
      return;
    }
    if (accessControl != null) {
      accessControl.requireStoreAccess(user, DataScopeDomains.WAREHOUSE, storeId, action);
      return;
    }
    if (dataScope.allowsStore(storeId)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "当前账号不能操作该门店仓库数据", HttpStatus.FORBIDDEN);
  }

  private boolean hasCentralCostAccess(AuthUser user) {
    boolean centralPermission = accessControl != null
        ? accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ)
        : hasLegacyPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ);
    if (!centralPermission) {
      return false;
    }
    DataScope dataScope = warehouseDataScope(user);
    return dataScope.allowsAllStores() || DataScopeModes.CENTRAL_WAREHOUSE.equals(dataScope.mode());
  }

  private DataScope warehouseDataScope(AuthUser user) {
    if (accessControl != null) {
      DataScope configured = accessControl.dataScope(user, DataScopeDomains.WAREHOUSE);
      if (configured != null) {
        return configured;
      }
    }
    if (AccessControlService.isBoss(user)) {
      return DataScope.all();
    }
    if (hasLegacyPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ)) {
      return new DataScope(DataScopeModes.CENTRAL_WAREHOUSE, List.of());
    }
    if (user != null && user.storeId() != null && !user.storeId().isBlank()) {
      return new DataScope(DataScopeModes.OWN_STORE, List.of(user.storeId().trim()));
    }
    return DataScope.none();
  }

  private BusinessScope resolveManagerScope(AuthUser user, String storeId, String action) {
    if (businessScopeResolver == null || !isStoreManager(user)) {
      return null;
    }
    return businessScopeResolver.resolve(
        user, DataScopeDomains.WAREHOUSE, storeId, null, action);
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private void rejectStoreManagerCentralAction(AuthUser user, String message) {
    if (isStoreManager(user)) {
      throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }
  }

  private boolean hasLegacyPermission(AuthUser user, String permissionCode) {
    return AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(permissionCode);
  }

  private void requireLegacyPermission(AuthUser user, String permissionCode, String message) {
    if (hasLegacyPermission(user, permissionCode)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
  }

  private LocalDate parseDate(String value, String label) {
    try {
      return LocalDate.parse(value);
    } catch (Exception ex) {
      throw new BusinessException("BAD_DATE", label + "必须是 YYYY-MM-DD", HttpStatus.BAD_REQUEST);
    }
  }

  private BigDecimal positive(BigDecimal value, String label) {
    BigDecimal amount = amount(value);
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_QUANTITY", label + "必须大于 0", HttpStatus.BAD_REQUEST);
    }
    return amount;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal weightedCost(
      BigDecimal oldQuantity, BigDecimal oldCost, BigDecimal addedQuantity, BigDecimal addedCost
  ) {
    BigDecimal total = amount(oldQuantity).add(amount(addedQuantity));
    if (total.signum() == 0) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return amount(oldQuantity).multiply(oldCost == null ? BigDecimal.ZERO : oldCost)
        .add(amount(addedQuantity).multiply(addedCost == null ? BigDecimal.ZERO : addedCost))
        .divide(total, 4, RoundingMode.HALF_UP);
  }

  private String returnNo() {
    return "PSTH" + LocalDateTime.now().format(RETURN_NO_TIME) + UUID.randomUUID().toString().replace("-", "").substring(0, 3).toUpperCase();
  }

  private String returnHandler(AuthUser user) {
    String name = user == null || user.displayName() == null || user.displayName().isBlank() ? "仓库" : user.displayName();
    return "创:" + name + ",改:" + name + ",审:" + name + ",核对:" + name;
  }

  private record LineDraft(
      WarehouseItemResponse item,
      BigDecimal quantity,
      String warning,
      String note
  ) {
  }

  private record ReturnLineDraft(
      Long sourceRequisitionLineId,
      long itemId,
      String itemName,
      String spec,
      Long batchId,
      String batchNo,
      BigDecimal quantity,
      String unit,
      BigDecimal unitPrice,
      BigDecimal returnPrice,
      String reason,
      String note
  ) {
  }
}
