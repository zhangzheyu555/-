package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.warehouse.WarehouseRepository.ReturnSourceMovementRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {
  private static final DateTimeFormatter RETURN_NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");
  private final WarehouseRepository warehouseRepository;

  private record ReturnAvailability(
      BigDecimal receivedQuantity,
      BigDecimal returnedQuantity,
      BigDecimal sourceAvailableReturnQuantity,
      BigDecimal storeInventoryQuantity,
      BigDecimal availableReturnQuantity
  ) {
  }

  public WarehouseService(WarehouseRepository warehouseRepository) {
    this.warehouseRepository = warehouseRepository;
  }

  public WarehouseOverviewResponse overview(AuthUser user) {
    requireWarehouseRead(user);
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId());
    List<WarehouseAlertResponse> alerts = alerts(items);
    List<WarehouseRequisitionResponse> requisitions = requisitionsFor(user);
    String scopedStoreId = scopedStoreId(user);
    WarehouseSummaryResponse summary = new WarehouseSummaryResponse(
        items.size(),
        (int) items.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "OVERSTOCK".equals(item.alertLevel())).count(),
        warehouseRepository.pendingRequisitionCount(user.tenantId()),
        warehouseRepository.pendingReceiptCount(user.tenantId(), scopedStoreId),
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
        warehouseRepository.deliveries(user.tenantId(), scopedStoreId),
        warehouseRepository.movements(user.tenantId(), scopedStoreId, 80),
        warehouseRepository.stockBatches(user.tenantId())
    );
    return "STORE_MANAGER".equals(user.role()) ? storeManagerSafeOverview(response, user.tenantId(), scopedStoreId) : response;
  }

  public List<WarehouseItemResponse> items(AuthUser user) {
    requireWarehouseRead(user);
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId());
    return "STORE_MANAGER".equals(user.role()) ? safeItems(items, user.tenantId(), scopedStoreId(user)) : items;
  }

  public WarehouseItemResponse item(AuthUser user, long itemId) {
    requireWarehouseRead(user);
    WarehouseItemResponse item = warehouseRepository.item(user.tenantId(), itemId)
        .orElseThrow(() -> new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.NOT_FOUND));
    if ("STORE_MANAGER".equals(user.role()) && !item.active()) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在或已停用", HttpStatus.NOT_FOUND);
    }
    return "STORE_MANAGER".equals(user.role()) ? safeItems(List.of(item), user.tenantId(), scopedStoreId(user)).get(0) : item;
  }

  public List<WarehouseItemCategoryResponse> itemCategories(AuthUser user) {
    requireWarehouseRead(user);
    return categoryTree(warehouseRepository.itemCategories(user.tenantId()));
  }

  @Transactional
  public void saveItem(AuthUser user, WarehouseItemRequest request) {
    requireWarehouseManage(user);
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
    requireWarehouseManage(user);
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
    requireWarehouseManage(user);
    if (!warehouseRepository.itemCategoryExists(user.tenantId(), categoryId)) {
      throw new BusinessException("CATEGORY_NOT_FOUND", "商品类别不存在", HttpStatus.BAD_REQUEST);
    }
    boolean enabled = request == null || request.enabled() == null || request.enabled();
    warehouseRepository.setItemCategoryEnabled(user.tenantId(), categoryId, enabled);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), enabled ? "启用商品类别" : "停用商品类别", String.valueOf(categoryId), null, "");
  }

  @Transactional
  public void deleteItemCategory(AuthUser user, long categoryId) {
    requireWarehouseManage(user);
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
    requireWarehouseManage(user);
    if (!warehouseRepository.itemExists(user.tenantId(), itemId)) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在", HttpStatus.BAD_REQUEST);
    }
    boolean enabled = request == null || request.enabled() == null || request.enabled();
    warehouseRepository.setItemEnabled(user.tenantId(), itemId, enabled);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), enabled ? "启用商品" : "停用商品", String.valueOf(itemId), null, "");
  }

  @Transactional
  public void updateAlertSettings(AuthUser user, long itemId, WarehouseAlertSettingsRequest request) {
    requireWarehouseManage(user);
    if (!warehouseRepository.itemExists(user.tenantId(), itemId)) {
      throw new BusinessException("ITEM_NOT_FOUND", "物料不存在", HttpStatus.BAD_REQUEST);
    }
    WarehouseAlertSettingsRequest safeRequest = request == null
        ? new WarehouseAlertSettingsRequest(BigDecimal.ZERO, true, 3)
        : request;
    warehouseRepository.updateAlertSettings(
        user.tenantId(),
        itemId,
        amount(safeRequest.minStockQuantity()),
        safeRequest.alertEnabled() == null || safeRequest.alertEnabled(),
        safeRequest.expiryAlertDays()
    );
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "设置库存预警",
        String.valueOf(itemId),
        null,
        "最低安全库存 " + amount(safeRequest.minStockQuantity()).stripTrailingZeros().toPlainString()
    );
  }

  @Transactional
  public void receiveStock(AuthUser user, WarehouseStockBatchRequest request) {
    requireWarehouseManage(user);
    if (!warehouseRepository.activeItemExists(user.tenantId(), request.itemId())) {
      throw new BusinessException("ITEM_NOT_FOUND", "商品不存在或已停用", HttpStatus.BAD_REQUEST);
    }
    parseDate(request.receivedDate(), "到货日期");
    if (request.expiryDate() != null && !request.expiryDate().isBlank()) {
      parseDate(request.expiryDate(), "到期日期");
    }
    String requestKey = normalizeClientRequestId(request.clientRequestId());
    if (requestKey != null && !warehouseRepository.reserveRequest(user.tenantId(), "WAREHOUSE_STOCK_RECEIVE", requestKey)) {
      return;
    }
    warehouseRepository.upsertBatch(user.tenantId(), request);
    Long batchId = warehouseRepository.batchId(user.tenantId(), request.itemId(), request.batchNo()).orElse(null);
    warehouseRepository.insertMovement(
        user.tenantId(),
        request.itemId(),
        batchId,
        "IN",
        request.quantity(),
        "MANUAL_RECEIVE",
        request.batchNo(),
        null,
        request.note(),
        user.id()
    );
    if (requestKey != null) {
      warehouseRepository.completeReservedRequest(user.tenantId(), "WAREHOUSE_STOCK_RECEIVE", requestKey, String.valueOf(batchId));
    }
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "仓库入库", request.batchNo(), null, request.note());
  }

  public List<WarehouseRequisitionResponse> requisitions(AuthUser user) {
    requireWarehouseRead(user);
    List<WarehouseRequisitionResponse> requisitions = requisitionsFor(user);
    return "STORE_MANAGER".equals(user.role()) ? safeRequisitions(requisitions) : requisitions;
  }

  public List<WarehouseStockMovementResponse> movements(AuthUser user) {
    requireWarehouseRead(user);
    return warehouseRepository.movements(user.tenantId(), scopedStoreId(user), 120);
  }

  public List<WarehouseReturnResponse> returns(AuthUser user) {
    requireReturnRead(user);
    return warehouseRepository.returns(user.tenantId(), scopedStoreId(user));
  }

  public WarehouseReturnResponse returnOrder(AuthUser user, String returnId) {
    requireReturnRead(user);
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    requireReturnScope(user, order.returnStoreId());
    return order;
  }

  @Transactional
  public WarehouseReturnResponse createReturn(AuthUser user, WarehouseReturnRequest request) {
    requireReturnCreate(user);
    String sourceRequisitionId = request.sourceRequisitionId() == null ? "" : request.sourceRequisitionId().trim();
    if (sourceRequisitionId.isBlank()) {
      throw new BusinessException("SOURCE_REQUIRED", "请选择要退货的原叫货单", HttpStatus.BAD_REQUEST);
    }
    WarehouseRequisitionResponse requisition = requireRequisition(user.tenantId(), sourceRequisitionId);
    if (!List.of("SHIPPED", "RECEIVED").contains(requisition.status())) {
      throw new BusinessException("BAD_SOURCE_STATUS", "只能基于已发货或已收货的叫货单发起退货", HttpStatus.CONFLICT);
    }
    if ("STORE_MANAGER".equals(user.role()) && (user.storeId() == null || !user.storeId().equals(requisition.storeId()))) {
      throw new BusinessException("FORBIDDEN", "店长只能基于本门店叫货单发起退货", HttpStatus.FORBIDDEN);
    }
    String requestedStoreId = request.returnStoreId() == null ? "" : request.returnStoreId().trim();
    if (!requestedStoreId.isBlank() && !requestedStoreId.equals(requisition.storeId())) {
      throw new BusinessException("BAD_RETURN_STORE", "退货门店必须与原叫货单门店一致", HttpStatus.BAD_REQUEST);
    }
    String storeId = requisition.storeId();
    if (!warehouseRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "退货门店不存在", HttpStatus.BAD_REQUEST);
    }
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
    String receiveDepartment = request.receiveDepartment() == null || request.receiveDepartment().isBlank()
        ? "仓库"
        : request.receiveDepartment().trim();
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
        BigDecimal returnPrice = amount(line.returnPrice() == null || "STORE_MANAGER".equals(user.role()) ? unitPrice : line.returnPrice());
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
    warehouseRepository.insertReturnOrder(
        user.tenantId(),
        returnNo,
        returnNo,
        requisition.id(),
        deliveryId,
        storeId,
        storeName,
        receiveDepartment,
        "SUBMITTED",
        total,
        returnHandler(user),
        user.displayName(),
        request.reason(),
        request.note(),
        returnDate
    );
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
    saveReturnAttachments(user, returnNo, request.attachments());
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "提交配送退货单", returnNo, storeId, request.note());
    return warehouseRepository.returnOrder(user.tenantId(), returnNo)
        .orElseThrow(() -> new BusinessException("RETURN_SAVE_FAILED", "配送退货单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public WarehouseReturnResponse reviewReturn(AuthUser user, String returnId, WarehouseReturnReviewRequest request) {
    requireReturnReview(user);
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
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
    return warehouseRepository.returnOrder(user.tenantId(), order.id())
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public WarehouseReturnResponse receiveReturn(AuthUser user, String returnId, WarehouseReturnReceiveRequest request) {
    requireReturnReview(user);
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    if (!"APPROVED".equals(order.status())) {
      throw new BusinessException("BAD_RETURN_STATUS", "只有仓库已通过的退货单可以确认收货", HttpStatus.CONFLICT);
    }
    String note = request == null ? null : request.note();
    for (WarehouseReturnLineResponse line : order.lines()) {
      if (line.batchId() == null) {
        throw new BusinessException("RETURN_BATCH_NOT_FOUND", "退货明细缺少原出库批次，不能回库", HttpStatus.CONFLICT);
      }
      warehouseRepository.addBatchQuantity(user.tenantId(), line.batchId(), line.quantity());
      warehouseRepository.insertMovement(
          user.tenantId(),
          line.itemId(),
          line.batchId(),
          "IN",
          line.quantity(),
          "RETURN",
          order.id(),
          order.returnStoreId(),
          note == null || note.isBlank() ? "配送退货回库" : note,
          user.id()
      );
      warehouseRepository.addStoreInventory(
          user.tenantId(),
          order.returnStoreId(),
          line.itemId(),
          line.quantity().negate(),
          "OUT",
          "STORE_RETURN",
          order.id(),
          note == null || note.isBlank() ? "配送退货回库" : note,
          user.id()
      );
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
    return warehouseRepository.returnOrder(user.tenantId(), order.id())
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
  }

  @Transactional
  public WarehousePurchaseOrderResponse createPurchaseOrder(AuthUser user, WarehousePurchaseOrderRequest request) {
    requireWarehouseManage(user);
    String id = "PO" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    BigDecimal total = BigDecimal.ZERO;
    for (WarehousePurchaseOrderLineRequest line : request.lines()) {
      if (!warehouseRepository.itemExists(user.tenantId(), line.itemId())) {
        throw new BusinessException("ITEM_NOT_FOUND", "采购物料不存在", HttpStatus.BAD_REQUEST);
      }
      total = total.add(positive(line.orderedQuantity(), "采购数量").multiply(amount(line.unitCost())));
    }
    warehouseRepository.insertPurchaseOrder(user.tenantId(), id, request.supplierId(), total, request.note(), user.id());
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
    String requestKey = normalizeClientRequestId(request.clientRequestId());
    if (requestKey != null) {
      var previousId = warehouseRepository.reservedRequestBusinessId(user.tenantId(), "STORE_REQUISITION", requestKey);
      if (previousId.isPresent()) {
        return requireRequisition(user.tenantId(), previousId.get());
      }
      if (!warehouseRepository.reserveRequest(user.tenantId(), "STORE_REQUISITION", requestKey)) {
        return warehouseRepository.reservedRequestBusinessId(user.tenantId(), "STORE_REQUISITION", requestKey)
            .map(id -> requireRequisition(user.tenantId(), id))
            .orElseThrow(() -> new BusinessException("REQUEST_IN_PROGRESS", "叫货单正在提交，请稍后刷新", HttpStatus.CONFLICT));
      }
    }
    Map<Long, WarehouseItemResponse> items = warehouseRepository.items(user.tenantId()).stream()
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
    warehouseRepository.insertRequisition(user.tenantId(), id, storeId, total, request.note(), user.id());
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
    return warehouseRepository.requisition(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("REQ_NOT_FOUND", "叫货单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public void review(AuthUser user, String requisitionId, WarehouseRequisitionReviewRequest request) {
    requireWarehouseManage(user);
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    if (!"SUBMITTED".equals(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核叫货单可以审核", HttpStatus.CONFLICT);
    }
    Map<Long, BigDecimal> approvedMap = new HashMap<>();
    if (request.lines() != null) {
      for (WarehouseRequisitionReviewLineRequest line : request.lines()) {
        approvedMap.put(line.itemId(), line.approvedQuantity());
      }
    }
    BigDecimal total = BigDecimal.ZERO;
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal approved = request.approved()
          ? amount(approvedMap.getOrDefault(line.itemId(), line.requestedQuantity()))
          : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
      warehouseRepository.updateApprovedQuantity(user.tenantId(), requisitionId, line.itemId(), approved);
      total = total.add(approved.multiply(line.unitPrice()));
    }
    warehouseRepository.reviewRequisition(user.tenantId(), requisitionId, request.approved(), total, user.id(), request.note());
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), request.approved() ? "审核叫货" : "驳回叫货", requisitionId, requisition.storeId(), request.note());
  }

  @Transactional
  public void ship(AuthUser user, String requisitionId) {
    requireWarehouseManage(user);
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    if (!List.of("SUBMITTED", "APPROVED").contains(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "只有待审核或待配货叫货单可以配货", HttpStatus.CONFLICT);
    }
    for (WarehouseRequisitionLineResponse line : requisition.lines()) {
      BigDecimal quantity = line.approvedQuantity().compareTo(BigDecimal.ZERO) > 0
          ? line.approvedQuantity()
          : line.requestedQuantity();
      deductStock(user, requisition, line.itemId(), quantity);
      warehouseRepository.updateShippedQuantity(user.tenantId(), requisitionId, line.itemId(), quantity);
    }
    warehouseRepository.markShipped(user.tenantId(), requisitionId, user.id());
    createDeliveryForRequisition(user, requireRequisition(user.tenantId(), requisitionId));
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "完成配货", requisitionId, requisition.storeId(), "仓库出库");
    warehouseRepository.insertTodoAction(
        "todo-act-" + UUID.randomUUID(),
        user.tenantId(),
        "warehouse-" + requisitionId,
        "WAREHOUSE_SHIP",
        "仓库已发货，仓库待办已完成",
        user.id(),
        user.displayName(),
        user.role()
    );
  }

  @Transactional
  public void receiveByStore(AuthUser user, String requisitionId, WarehouseReceiptRequest request) {
    requireStoreReceiver(user);
    WarehouseRequisitionResponse requisition = requireRequisitionForUpdate(user.tenantId(), requisitionId);
    if (!"SHIPPED".equals(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "这张叫货单还没有发货，不能确认收货", HttpStatus.CONFLICT);
    }
    if ("STORE_MANAGER".equals(user.role()) && (user.storeId() == null || !user.storeId().equals(requisition.storeId()))) {
      throw new BusinessException("FORBIDDEN", "店长只能确认本门店收货", HttpStatus.FORBIDDEN);
    }
    WarehouseDeliveryResponse delivery = createDeliveryForRequisition(user, requisition);
    String receiptId = "RCV" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    warehouseRepository.insertReceipt(user.tenantId(), receiptId, delivery.id(), requisition.id(), requisition.storeId(), user.id(), request.note());
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
    warehouseRepository.markRequisitionReceived(user.tenantId(), requisitionId, user.id(), request.note());
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "门店确认收货", requisitionId, requisition.storeId(), request.note());
    warehouseRepository.insertTodoAction(
        "todo-act-" + UUID.randomUUID(),
        user.tenantId(),
        "store-receipt-" + requisitionId,
        "STORE_RECEIVE",
        "门店已确认收货，叫货流程完成",
        user.id(),
        user.displayName(),
        user.role()
    );
  }

  private WarehouseDeliveryResponse createDeliveryForRequisition(AuthUser user, WarehouseRequisitionResponse requisition) {
    return warehouseRepository.deliveryByRequisition(user.tenantId(), requisition.id())
        .orElseGet(() -> repairDeliveryForShippedRequisition(user, requisition));
  }

  private WarehouseDeliveryResponse repairDeliveryForShippedRequisition(AuthUser user, WarehouseRequisitionResponse requisition) {
    if (!"SHIPPED".equals(requisition.status())) {
      throw new BusinessException("BAD_STATUS", "这张叫货单还没有发货，不能确认收货", HttpStatus.CONFLICT);
    }
    Map<Long, BigDecimal> movementQuantities = warehouseRepository.shippedQuantitiesFromMovements(user.tenantId(), requisition.id());
    String deliveryId = "DO-" + requisition.id();
    warehouseRepository.insertDelivery(user.tenantId(), deliveryId, requisition.id(), requisition.storeId(), user.id(), "自动补建配送单");
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

  private List<WarehouseRequisitionResponse> requisitionsFor(AuthUser user) {
    return withReturnAvailability(user.tenantId(), warehouseRepository.requisitions(user.tenantId(), scopedStoreId(user)));
  }

  private List<WarehouseRequisitionResponse> withReturnAvailability(long tenantId, List<WarehouseRequisitionResponse> requisitions) {
    return requisitions.stream()
        .map(row -> new WarehouseRequisitionResponse(
            row.id(),
            row.storeId(),
            row.storeName(),
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

  private String scopedStoreId(AuthUser user) {
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null && !user.storeId().isBlank()) {
      return user.storeId();
    }
    return null;
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

  private WarehouseOverviewResponse storeManagerSafeOverview(WarehouseOverviewResponse response, long tenantId, String storeId) {
    List<WarehouseRequisitionResponse> requisitions = safeRequisitions(response.requisitions());
    List<WarehouseDeliveryResponse> deliveries = safeDeliveries(response.deliveries());
    List<WarehouseItemResponse> items = safeItems(response.items(), tenantId, storeId);
    List<Long> visibleItemIds = items.stream().map(WarehouseItemResponse::id).toList();
    List<WarehouseAlertResponse> alerts = response.alerts().stream()
        .filter(alert -> visibleItemIds.contains(alert.itemId()))
        .toList();
    WarehouseSummaryResponse summary = new WarehouseSummaryResponse(
        items.size(),
        (int) items.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
        0,
        (int) requisitions.stream().filter(row -> List.of("SUBMITTED", "APPROVED").contains(row.status())).count(),
        (int) requisitions.stream().filter(row -> "SHIPPED".equals(row.status())).count(),
        0,
        amount(BigDecimal.ZERO)
    );
    return new WarehouseOverviewResponse(
        summary,
        alerts,
        items,
        requisitions,
        List.of(),
        List.of(),
        deliveries,
        response.movements(),
        List.of()
    );
  }

  private List<WarehouseItemResponse> safeItems(List<WarehouseItemResponse> items, long tenantId, String storeId) {
    Map<Long, BigDecimal> storeStocks = warehouseRepository.storeInventoryQuantities(tenantId, storeId);
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
    if ("STORE_MANAGER".equals(user.role())) {
      if (user.storeId() == null || user.storeId().isBlank()) {
        throw new BusinessException("NO_STORE_SCOPE", "店长账号未绑定门店", HttpStatus.FORBIDDEN);
      }
      return user.storeId();
    }
    if (requestedStoreId == null || requestedStoreId.isBlank()) {
      throw new BusinessException("STORE_REQUIRED", "请选择叫货门店", HttpStatus.BAD_REQUEST);
    }
    return requestedStoreId.trim();
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

  private void saveReturnAttachments(AuthUser user, String returnId, List<WarehouseReturnAttachmentRequest> attachments) {
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
          "RETURN_ORDER",
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

  private void requireWarehouseRead(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "WAREHOUSE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权访问仓库中心", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWarehouseAccess(AuthUser user) {
    requireWarehouseRead(user);
  }

  private void requireWarehouseManage(AuthUser user) {
    if (!List.of("ADMIN", "WAREHOUSE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅仓库管理员可维护仓库数据", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreRequisitionCreate(AuthUser user) {
    if (!"STORE_MANAGER".equals(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅门店人员可提交叫货申请", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReturnCreate(AuthUser user) {
    if (!"STORE_MANAGER".equals(user.role())) {
      throw new BusinessException("FORBIDDEN", "当前角色不能新建配送退货单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReturnReview(AuthUser user) {
    if (!List.of("ADMIN", "WAREHOUSE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅仓库管理员可审核配送退货单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReturnRead(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "OWNER", "WAREHOUSE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权查看配送退货单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireReturnScope(AuthUser user, String storeId) {
    if ("STORE_MANAGER".equals(user.role()) && (user.storeId() == null || !user.storeId().equals(storeId))) {
      throw new BusinessException("FORBIDDEN", "店长只能查看本门店配送退货单", HttpStatus.FORBIDDEN);
    }
  }

  private void requireStoreReceiver(AuthUser user) {
    if (!"STORE_MANAGER".equals(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅对应门店可确认收货", HttpStatus.FORBIDDEN);
    }
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
