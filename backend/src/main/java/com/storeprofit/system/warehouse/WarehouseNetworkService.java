package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.BatchRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.InventoryRow;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.TransferMaterialRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional warehouse-to-warehouse flow. Transfers never touch finance or sales tables. */
@Service
public class WarehouseNetworkService {
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  private static final DateTimeFormatter TRANSFER_NO_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

  private final WarehouseTopologyRepository repository;
  private final WarehouseTopologyService topology;
  private final WarehouseRepository warehouseRepository;
  private final AccessControlService accessControl;

  public WarehouseNetworkService(
      WarehouseTopologyRepository repository,
      WarehouseTopologyService topology,
      WarehouseRepository warehouseRepository,
      AccessControlService accessControl
  ) {
    this.repository = repository;
    this.topology = topology;
    this.warehouseRepository = warehouseRepository;
    this.accessControl = accessControl;
  }

  public List<WarehouseFacilityResponse> facilities(AuthUser user) {
    return topology.visibleFacilities(user);
  }

  /**
   * Returns a server-authoritative transfer workbench context for one selected warehouse.
   * Availability is intentionally non-locking; create/review/ship still perform transactional
   * topology, idempotency and inventory checks.
   */
  public WarehouseTransferContextResponse transferContext(AuthUser user, Long warehouseId) {
    WarehouseTopologyService.TransferContext context = topology.resolveTransferContext(user, warehouseId);
    List<WarehouseTransferContextResponse.Route> routes = context.routes().stream()
        .map(route -> new WarehouseTransferContextResponse.Route(
            ref(route.sourceWarehouse()),
            ref(route.targetWarehouse()),
            route.formAction(),
            route.workbenchLabel(),
            actions(route.actions()),
            route.actions().canCreate()
                ? transferMaterials(user.tenantId(), route.sourceWarehouse().id())
                : List.of()
        ))
        .toList();
    return new WarehouseTransferContextResponse(
        ref(context.currentWarehouse()),
        context.mode(),
        context.workbenchLabel(),
        routes,
        transferTodos(user, context.currentWarehouse().id(), context.routes())
    );
  }

  public List<WarehouseTransferResponse> transfers(AuthUser user, Long warehouseId) {
    // Transfer responses include inventory cost. Store read permission is intentionally insufficient.
    accessControl.requireWarehouseRead(user);
    if (warehouseId != null) {
      topology.requireVisibleFacility(user, warehouseId, "查看仓间调拨");
    }
    return repository.transfers(user.tenantId()).stream()
        .filter(row -> warehouseId == null
            || row.sourceWarehouseId() == warehouseId
            || row.targetWarehouseId() == warehouseId)
        .filter(row -> topology.canAccessFacility(user, row.sourceWarehouseId())
            || topology.canAccessFacility(user, row.targetWarehouseId()))
        .toList();
  }

  public WarehouseTransferResponse transfer(AuthUser user, String transferId) {
    accessControl.requireWarehouseRead(user);
    WarehouseTransferResponse row = requireTransfer(user.tenantId(), transferId, false);
    FacilityRow source = requireFacility(user.tenantId(), row.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), row.targetWarehouseId());
    topology.requireTransferRead(user, source, target);
    return row;
  }

  @Transactional
  public WarehouseTransferResponse create(AuthUser user, WarehouseTransferCreateRequest request) {
    String clientRequestId = requiredKey(request.clientRequestId());
    FacilityRow source = requireFacility(user.tenantId(), request.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), request.targetWarehouseId());
    topology.requireTransferRequestAuthorization(user, source, target);

    var duplicate = repository.transferByCreateKey(user.tenantId(), clientRequestId);
    if (duplicate.isPresent()) {
      WarehouseTransferResponse existing = duplicate.get();
      if (existing.sourceWarehouseId() != source.id()
          || existing.targetWarehouseId() != target.id()) {
        throw conflict("IDEMPOTENCY_KEY_CONFLICT", "该请求编号已用于另一张调拨单");
      }
      return existing;
    }
    topology.requireTransferRoute(user, source, target, "创建仓间调拨");

    if (request.lines() == null || request.lines().isEmpty()) {
      throw badRequest("TRANSFER_LINES_REQUIRED", "请至少添加一项调拨物料");
    }
    Set<Long> itemIds = new HashSet<>();
    for (WarehouseTransferLineRequest line : request.lines()) {
      if (line.itemId() == null || !warehouseRepository.activeItemExists(user.tenantId(), line.itemId())) {
        throw badRequest("ITEM_DISABLED", "调拨物料不存在或已停用");
      }
      if (!itemIds.add(line.itemId())) {
        throw badRequest("TRANSFER_ITEM_DUPLICATE", "同一调拨单不能重复添加相同物料");
      }
      positive(line.quantity(), "调拨数量");
    }

    String id = UUID.randomUUID().toString();
    String transferNo = "DB" + LocalDateTime.now().format(TRANSFER_NO_TIME)
        + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    if (!repository.insertTransfer(user.tenantId(), id, transferNo, source.id(), target.id(),
        clientRequestId, request.note(), user.id())) {
      return repository.transferByCreateKey(user.tenantId(), clientRequestId)
          .orElseThrow(this::concurrentConflict);
    }
    for (WarehouseTransferLineRequest line : request.lines()) {
      repository.insertTransferLine(user.tenantId(), id, line.itemId(),
          positive(line.quantity(), "调拨数量"), line.note());
    }
    log(user, "创建仓间调拨", id, source.name() + " → " + target.name());
    return requireTransfer(user.tenantId(), id, false);
  }

  @Transactional
  public WarehouseTransferResponse submit(AuthUser user, String transferId, WarehouseTransferActionRequest request) {
    WarehouseTransferResponse current = requireTransfer(user.tenantId(), transferId, true);
    FacilityRow source = requireFacility(user.tenantId(), current.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), current.targetWarehouseId());
    topology.requireTransferRequestAuthorization(user, source, target);
    String key = actionKey(transferId, "SUBMIT", request == null ? null : request.clientRequestId());
    if (isRepeatedAction(user.tenantId(), transferId, "SUBMIT", key)) {
      return requireTransfer(user.tenantId(), transferId, false);
    }
    topology.requireTransferRoute(user, source, target, "提交仓间调拨");
    if (!"DRAFT".equals(current.status())) {
      throw statusConflict("只有草稿调拨单可以提交");
    }
    changed(repository.markSubmitted(user.tenantId(), transferId, current.version()));
    repository.insertAction(user.tenantId(), transferId, "SUBMIT", key, current.version() + 1, user.id());
    log(user, "提交仓间调拨", transferId, request == null ? null : request.note());
    return requireTransfer(user.tenantId(), transferId, false);
  }

  @Transactional
  public WarehouseTransferResponse review(AuthUser user, String transferId, WarehouseTransferReviewRequest request) {
    WarehouseTransferResponse current = requireTransfer(user.tenantId(), transferId, true);
    FacilityRow source = requireFacility(user.tenantId(), current.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), current.targetWarehouseId());
    topology.requireTransferApproveAuthorization(user, source, target);
    boolean approved = Boolean.TRUE.equals(request.approved());
    String action = approved ? "APPROVE" : "REJECT";
    String key = actionKey(transferId, action, null);
    if (isRepeatedAction(user.tenantId(), transferId, action, key)) {
      return requireTransfer(user.tenantId(), transferId, false);
    }
    topology.requireTransferRoute(user, source, target, "审批仓间调拨");
    if (!"SUBMITTED".equals(current.status())) {
      throw statusConflict("只有已提交的调拨单可以审批");
    }

    BigDecimal total = ZERO;
    if (approved) {
      // Item order makes lock acquisition deterministic and prevents deadlock between concurrent orders.
      List<WarehouseTransferLineResponse> lines = current.lines().stream()
          .sorted(java.util.Comparator.comparingLong(WarehouseTransferLineResponse::itemId)).toList();
      Map<Long, BigDecimal> costs = new HashMap<>();
      for (WarehouseTransferLineResponse line : lines) {
        BigDecimal quantity = positive(line.requestedQuantity(), "审批数量");
        InventoryRow inventory = repository.lockInventory(user.tenantId(), source.id(), line.itemId());
        if (inventory.available().compareTo(quantity) < 0) {
          throw conflict("WAREHOUSE_STOCK_INSUFFICIENT", line.itemName() + "可用库存不足");
        }
        BigDecimal cost = reserveBatches(user.tenantId(), source.id(), line, quantity);
        if (!repository.updateInventory(user.tenantId(), inventory, inventory.onHand(),
            inventory.reserved().add(quantity), inventory.inTransit(), inventory.unitCost())) {
          throw concurrentConflict();
        }
        repository.insertMovement(user.tenantId(), source.id(), line.itemId(), null, "RESERVE",
            ZERO, quantity, ZERO, inventory.unitCost(), "WAREHOUSE_TRANSFER", transferId,
            null, "仓间调拨预占库存", user.id());
        costs.put(line.itemId(), cost);
      }
      for (WarehouseTransferLineResponse line : current.lines()) {
        BigDecimal quantity = amount(line.requestedQuantity());
        BigDecimal cost = costs.get(line.itemId());
        repository.updateTransferLineApproval(user.tenantId(), line.id(), quantity, cost);
        total = total.add(quantity.multiply(cost)).setScale(2, RoundingMode.HALF_UP);
      }
    }
    changed(repository.markReviewed(user.tenantId(), transferId, current.version(), approved,
        total, user.id(), request.note()));
    repository.insertAction(user.tenantId(), transferId, action, key, current.version() + 1, user.id());
    log(user, approved ? "批准仓间调拨" : "驳回仓间调拨", transferId, request.note());
    return requireTransfer(user.tenantId(), transferId, false);
  }

  @Transactional
  public WarehouseTransferResponse ship(AuthUser user, String transferId, WarehouseTransferActionRequest request) {
    WarehouseTransferResponse current = requireTransfer(user.tenantId(), transferId, true);
    FacilityRow source = requireFacility(user.tenantId(), current.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), current.targetWarehouseId());
    topology.requireTransferShipAuthorization(user, source, target);
    String key = actionKey(transferId, "SHIP", request == null ? null : request.clientRequestId());
    if (isRepeatedAction(user.tenantId(), transferId, "SHIP", key)) {
      return requireTransfer(user.tenantId(), transferId, false);
    }
    topology.requireTransferRoute(user, source, target, "仓间调拨发货");
    if (!"APPROVED".equals(current.status())) {
      throw statusConflict("只有已批准的调拨单可以发货");
    }

    List<WarehouseTransferLineResponse> lines = current.lines().stream()
        .sorted(java.util.Comparator.comparingLong(WarehouseTransferLineResponse::itemId)).toList();
    for (WarehouseTransferLineResponse line : lines) {
      BigDecimal quantity = positive(line.approvedQuantity(), "发货数量");
      InventoryRow sourceInventory = repository.lockInventory(user.tenantId(), source.id(), line.itemId());
      InventoryRow targetInventory = repository.lockInventory(user.tenantId(), target.id(), line.itemId());
      if (sourceInventory.onHand().compareTo(quantity) < 0
          || sourceInventory.reserved().compareTo(quantity) < 0) {
        throw conflict("WAREHOUSE_RESERVATION_INVALID", line.itemName() + "库存预占不足，不能发货");
      }
      BigDecimal actualCost = consumeReservedBatches(user.tenantId(), source.id(), line, quantity);
      BigDecimal remainingCost = remainingBatchCost(user.tenantId(), source.id(), line.itemId());
      if (!repository.updateInventory(user.tenantId(), sourceInventory,
          sourceInventory.onHand().subtract(quantity), sourceInventory.reserved().subtract(quantity),
          sourceInventory.inTransit(), remainingCost)) {
        throw concurrentConflict();
      }
      BigDecimal carrying = targetInventory.onHand().add(targetInventory.inTransit());
      BigDecimal targetCost = weightedCost(carrying, targetInventory.unitCost(), quantity, actualCost);
      if (!repository.updateInventory(user.tenantId(), targetInventory, targetInventory.onHand(),
          targetInventory.reserved(), targetInventory.inTransit().add(quantity), targetCost)) {
        throw concurrentConflict();
      }
      repository.updateTransferLineShipped(user.tenantId(), line.id(), quantity, actualCost);
      repository.insertMovement(user.tenantId(), source.id(), line.itemId(), null, "TRANSFER_OUT",
          quantity.negate(), quantity.negate(), ZERO, actualCost, "WAREHOUSE_TRANSFER", transferId,
          null, source.name() + "调拨发往" + target.name(), user.id());
      repository.insertMovement(user.tenantId(), target.id(), line.itemId(), null, "TRANSFER_IN_TRANSIT",
          ZERO, ZERO, quantity, actualCost, "WAREHOUSE_TRANSFER", transferId,
          null, source.name() + "调拨在途", user.id());
    }
    changed(repository.markShipped(user.tenantId(), transferId, current.version(), user.id()));
    repository.insertAction(user.tenantId(), transferId, "SHIP", key, current.version() + 1, user.id());
    log(user, "仓间调拨发货", transferId, request == null ? null : request.note());
    return requireTransfer(user.tenantId(), transferId, false);
  }

  @Transactional
  public WarehouseTransferResponse receive(AuthUser user, String transferId, WarehouseTransferReceiveRequest request) {
    WarehouseTransferResponse current = requireTransfer(user.tenantId(), transferId, true);
    FacilityRow source = requireFacility(user.tenantId(), current.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), current.targetWarehouseId());
    topology.requireTransferReceiveAuthorization(user, source, target);
    if ("RECEIVED".equals(current.status())) {
      return current;
    }
    if (!List.of("SHIPPED", "PARTIALLY_RECEIVED").contains(current.status())) {
      throw statusConflict("只有运输中的调拨单可以确认收货");
    }
    String suppliedKey = request == null ? null : request.clientRequestId();
    String key = actionKey(transferId, "RECEIVE", suppliedKey);
    if (isRepeatedAction(user.tenantId(), transferId, "RECEIVE", key)) {
      return requireTransfer(user.tenantId(), transferId, false);
    }
    topology.requireTransferRoute(user, source, target, "确认仓间调拨收货");
    Map<Long, BigDecimal> requested = receiveQuantities(current, request);
    boolean partial = requested.entrySet().stream().anyMatch(entry -> {
      WarehouseTransferLineResponse line = line(current, entry.getKey());
      return entry.getValue().compareTo(line.inTransitQuantity()) < 0;
    });
    if (partial && (suppliedKey == null || suppliedKey.isBlank())) {
      throw badRequest("IDEMPOTENCY_KEY_REQUIRED", "部分收货必须提供请求编号");
    }

    Map<Long, BigDecimal> remainingAfter = new HashMap<>();
    for (WarehouseTransferLineResponse line : current.lines()) {
      remainingAfter.put(line.itemId(), line.inTransitQuantity());
    }
    List<Long> itemIds = new ArrayList<>(requested.keySet());
    itemIds.sort(Long::compareTo);
    for (Long itemId : itemIds) {
      WarehouseTransferLineResponse line = line(current, itemId);
      BigDecimal quantity = positive(requested.get(itemId), "收货数量");
      if (quantity.compareTo(line.inTransitQuantity()) > 0) {
        throw conflict("TRANSFER_RECEIVE_EXCESS", line.itemName() + "收货数量超过在途数量");
      }
      InventoryRow targetInventory = repository.lockInventory(user.tenantId(), target.id(), itemId);
      if (targetInventory.inTransit().compareTo(quantity) < 0) {
        throw conflict("IN_TRANSIT_STOCK_INVALID", line.itemName() + "在途库存不足");
      }
      if (!repository.updateInventory(user.tenantId(), targetInventory,
          targetInventory.onHand().add(quantity), targetInventory.reserved(),
          targetInventory.inTransit().subtract(quantity), targetInventory.unitCost())) {
        throw concurrentConflict();
      }
      repository.upsertReceivedBatch(user.tenantId(), target.id(), itemId,
          "TR-" + current.transferNo() + "-" + itemId, quantity, line.unitCost(),
          source.name() + "调拨入库");
      if (repository.addTransferLineReceived(user.tenantId(), line.id(), quantity) != 1) {
        throw concurrentConflict();
      }
      remainingAfter.put(itemId, line.inTransitQuantity().subtract(quantity));
      repository.insertMovement(user.tenantId(), target.id(), itemId, null, "TRANSFER_RECEIVE",
          quantity, ZERO, quantity.negate(), line.unitCost(), "WAREHOUSE_TRANSFER", transferId,
          null, target.name() + "确认调拨收货", user.id());
    }
    boolean complete = remainingAfter.values().stream().allMatch(value -> value.signum() == 0);
    changed(repository.markReceived(user.tenantId(), transferId, current.version(), complete, user.id()));
    repository.insertAction(user.tenantId(), transferId, "RECEIVE", key, current.version() + 1, user.id());
    log(user, complete ? "完成仓间调拨收货" : "部分确认仓间调拨收货", transferId,
        request == null ? null : request.note());
    return requireTransfer(user.tenantId(), transferId, false);
  }

  @Transactional
  public WarehouseTransferResponse cancel(AuthUser user, String transferId, WarehouseTransferActionRequest request) {
    WarehouseTransferResponse current = requireTransfer(user.tenantId(), transferId, true);
    FacilityRow source = requireFacility(user.tenantId(), current.sourceWarehouseId());
    FacilityRow target = requireFacility(user.tenantId(), current.targetWarehouseId());
    topology.requireTransferRequestAuthorization(user, source, target);
    if ("CANCELLED".equals(current.status())) {
      return current;
    }
    String key = actionKey(transferId, "CANCEL", request == null ? null : request.clientRequestId());
    if (isRepeatedAction(user.tenantId(), transferId, "CANCEL", key)) {
      return requireTransfer(user.tenantId(), transferId, false);
    }
    topology.requireTransferRoute(user, source, target, "取消仓间调拨");
    if (!List.of("DRAFT", "SUBMITTED", "APPROVED").contains(current.status())) {
      throw statusConflict("当前调拨状态不能取消");
    }
    if ("APPROVED".equals(current.status())) {
      for (WarehouseTransferLineResponse line : current.lines().stream()
          .sorted(java.util.Comparator.comparingLong(WarehouseTransferLineResponse::itemId)).toList()) {
        BigDecimal quantity = positive(line.reservedQuantity(), "预占数量");
        InventoryRow inventory = repository.lockInventory(user.tenantId(), source.id(), line.itemId());
        if (inventory.reserved().compareTo(quantity) < 0) {
          throw conflict("WAREHOUSE_RESERVATION_INVALID", line.itemName() + "预占库存不一致");
        }
        releaseBatchReservations(user.tenantId(), source.id(), line, quantity);
        if (!repository.updateInventory(user.tenantId(), inventory, inventory.onHand(),
            inventory.reserved().subtract(quantity), inventory.inTransit(), inventory.unitCost())) {
          throw concurrentConflict();
        }
        repository.insertMovement(user.tenantId(), source.id(), line.itemId(), null, "RELEASE_RESERVE",
            ZERO, quantity.negate(), ZERO, inventory.unitCost(), "WAREHOUSE_TRANSFER", transferId,
            null, "取消仓间调拨释放预占", user.id());
      }
    }
    changed(repository.markCancelled(user.tenantId(), transferId, current.version(), user.id(),
        request == null ? null : request.note()));
    repository.insertAction(user.tenantId(), transferId, "CANCEL", key, current.version() + 1, user.id());
    log(user, "取消仓间调拨", transferId, request == null ? null : request.note());
    return requireTransfer(user.tenantId(), transferId, false);
  }

  private BigDecimal reserveBatches(
      long tenantId, long warehouseId, WarehouseTransferLineResponse line, BigDecimal quantity
  ) {
    BigDecimal remaining = quantity;
    BigDecimal value = ZERO;
    for (BatchRow batch : repository.positiveBatchesForUpdate(tenantId, warehouseId, line.itemId())) {
      BigDecimal available = batch.quantity().subtract(batch.reservedQuantity());
      BigDecimal used = available.min(remaining).max(ZERO);
      if (used.signum() > 0) {
        if (!repository.updateBatchQuantity(tenantId, batch, batch.quantity(),
            batch.reservedQuantity().add(used))) {
          throw concurrentConflict();
        }
        value = value.add(used.multiply(batch.unitCost()));
        remaining = remaining.subtract(used);
      }
      if (remaining.signum() == 0) {
        break;
      }
    }
    if (remaining.signum() > 0) {
      throw conflict("WAREHOUSE_BATCH_STOCK_INCONSISTENT", line.itemName() + "批次库存与库存汇总不一致");
    }
    return value.divide(quantity, 4, RoundingMode.HALF_UP);
  }

  private WarehouseTransferContextResponse.WarehouseRef ref(FacilityRow facility) {
    return new WarehouseTransferContextResponse.WarehouseRef(
        facility.id(), facility.code(), facility.name());
  }

  private WarehouseTransferContextResponse.Actions actions(
      WarehouseTopologyService.TransferActions actions
  ) {
    return new WarehouseTransferContextResponse.Actions(
        actions.canCreate(),
        actions.canSubmit(),
        actions.canApprove(),
        actions.canReject(),
        actions.canShip(),
        actions.canReceive(),
        actions.canCancel()
    );
  }

  private List<WarehouseTransferContextResponse.Material> transferMaterials(
      long tenantId,
      long sourceWarehouseId
  ) {
    return repository.transferMaterials(tenantId, sourceWarehouseId).stream()
        .map(this::material)
        .toList();
  }

  private WarehouseTransferContextResponse.Material material(TransferMaterialRow row) {
    String shortageMessage = row.availableQuantity().signum() <= 0
        ? "当前可发数量为 0，库存不足"
        : null;
    return new WarehouseTransferContextResponse.Material(
        row.itemId(), row.itemName(), row.itemCode(), row.unit(), row.availableQuantity(), shortageMessage);
  }

  private WarehouseTransferContextResponse.Todos transferTodos(
      AuthUser user,
      long currentWarehouseId,
      List<WarehouseTopologyService.TransferRoute> effectiveRoutes
  ) {
    int draft = 0;
    int pendingApproval = 0;
    int pendingShipment = 0;
    int pendingReceipt = 0;
    int completed = 0;
    for (WarehouseTransferResponse transfer : repository.transfers(user.tenantId())) {
      boolean source = transfer.sourceWarehouseId() == currentWarehouseId;
      boolean target = transfer.targetWarehouseId() == currentWarehouseId;
      if (!source && !target) {
        continue;
      }
      WarehouseTopologyService.TransferRoute route = effectiveRoutes.stream()
          .filter(candidate -> candidate.sourceWarehouse().id() == transfer.sourceWarehouseId()
              && candidate.targetWarehouse().id() == transfer.targetWarehouseId())
          .findFirst()
          .orElse(null);
      switch (transfer.status()) {
        case "DRAFT" -> {
          if (route != null && route.actions().canSubmit()) {
            draft++;
          }
        }
        case "SUBMITTED" -> {
          if (source && route != null && route.actions().canApprove()) {
            pendingApproval++;
          }
        }
        case "APPROVED" -> {
          if (source && route != null && route.actions().canShip()) {
            pendingShipment++;
          }
        }
        case "SHIPPED", "PARTIALLY_RECEIVED" -> {
          if (target && route != null && route.actions().canReceive()) {
            pendingReceipt++;
          }
        }
        case "RECEIVED" -> completed++;
        default -> {
          // Rejected and cancelled transfers are history, not current workbench todos.
        }
      }
    }
    return new WarehouseTransferContextResponse.Todos(
        draft, pendingApproval, pendingShipment, pendingReceipt, completed);
  }

  private BigDecimal consumeReservedBatches(
      long tenantId, long warehouseId, WarehouseTransferLineResponse line, BigDecimal quantity
  ) {
    BigDecimal remaining = quantity;
    BigDecimal value = ZERO;
    for (BatchRow batch : repository.positiveBatchesForUpdate(tenantId, warehouseId, line.itemId())) {
      BigDecimal used = batch.reservedQuantity().min(remaining);
      if (used.signum() > 0) {
        if (batch.quantity().compareTo(used) < 0 || !repository.updateBatchQuantity(tenantId, batch,
            batch.quantity().subtract(used), batch.reservedQuantity().subtract(used))) {
          throw concurrentConflict();
        }
        value = value.add(used.multiply(batch.unitCost()));
        remaining = remaining.subtract(used);
      }
      if (remaining.signum() == 0) {
        break;
      }
    }
    if (remaining.signum() > 0) {
      throw conflict("WAREHOUSE_RESERVATION_INVALID", line.itemName() + "批次预占库存不足");
    }
    return value.divide(quantity, 4, RoundingMode.HALF_UP);
  }

  private void releaseBatchReservations(
      long tenantId, long warehouseId, WarehouseTransferLineResponse line, BigDecimal quantity
  ) {
    BigDecimal remaining = quantity;
    for (BatchRow batch : repository.positiveBatchesForUpdate(tenantId, warehouseId, line.itemId())) {
      BigDecimal released = batch.reservedQuantity().min(remaining);
      if (released.signum() > 0 && !repository.updateBatchQuantity(tenantId, batch, batch.quantity(),
          batch.reservedQuantity().subtract(released))) {
        throw concurrentConflict();
      }
      remaining = remaining.subtract(released);
      if (remaining.signum() == 0) {
        break;
      }
    }
    if (remaining.signum() > 0) {
      throw conflict("WAREHOUSE_RESERVATION_INVALID", line.itemName() + "批次预占库存不足");
    }
  }

  private BigDecimal remainingBatchCost(long tenantId, long warehouseId, long itemId) {
    BigDecimal quantity = ZERO;
    BigDecimal value = ZERO;
    for (BatchRow batch : repository.positiveBatchesForUpdate(tenantId, warehouseId, itemId)) {
      quantity = quantity.add(batch.quantity());
      value = value.add(batch.quantity().multiply(batch.unitCost()));
    }
    return quantity.signum() == 0 ? ZERO : value.divide(quantity, 4, RoundingMode.HALF_UP);
  }

  private Map<Long, BigDecimal> receiveQuantities(
      WarehouseTransferResponse current, WarehouseTransferReceiveRequest request
  ) {
    Map<Long, BigDecimal> result = new HashMap<>();
    if (request == null || request.lines() == null || request.lines().isEmpty()) {
      for (WarehouseTransferLineResponse line : current.lines()) {
        if (line.inTransitQuantity().signum() > 0) {
          result.put(line.itemId(), line.inTransitQuantity());
        }
      }
      return result;
    }
    for (WarehouseTransferReceiveLineRequest row : request.lines()) {
      if (row.itemId() == null || result.putIfAbsent(row.itemId(),
          positive(row.receivedQuantity(), "收货数量")) != null) {
        throw badRequest("TRANSFER_RECEIVE_LINE_INVALID", "收货明细存在重复或无效物料");
      }
      line(current, row.itemId());
    }
    return result;
  }

  private WarehouseTransferLineResponse line(WarehouseTransferResponse transfer, long itemId) {
    return transfer.lines().stream().filter(row -> row.itemId() == itemId).findFirst()
        .orElseThrow(() -> badRequest("TRANSFER_ITEM_NOT_FOUND", "调拨单中不存在该物料"));
  }

  private WarehouseTransferResponse requireTransfer(long tenantId, String transferId, boolean lock) {
    return (lock ? repository.transferForUpdate(tenantId, transferId) : repository.transfer(tenantId, transferId))
        .orElseThrow(() -> new BusinessException(
            "TRANSFER_NOT_FOUND", "调拨单不存在", HttpStatus.NOT_FOUND));
  }

  private FacilityRow requireFacility(long tenantId, Long warehouseId) {
    if (warehouseId == null) {
      throw badRequest("WAREHOUSE_REQUIRED", "请选择仓库");
    }
    return repository.facility(tenantId, warehouseId).orElseThrow(() ->
        new BusinessException("WAREHOUSE_NOT_FOUND", "仓库不存在", HttpStatus.NOT_FOUND));
  }

  private boolean isRepeatedAction(long tenantId, String transferId, String action, String key) {
    var existing = repository.actionTransferId(tenantId, action, key);
    if (existing.isEmpty()) {
      return false;
    }
    if (!existing.get().equals(transferId)) {
      throw conflict("IDEMPOTENCY_KEY_CONFLICT", "该请求编号已用于另一张调拨单");
    }
    return true;
  }

  private String actionKey(String transferId, String action, String supplied) {
    return supplied == null || supplied.isBlank()
        ? transferId + ":" + action
        : supplied.trim();
  }

  private String requiredKey(String value) {
    if (value == null || value.isBlank()) {
      throw badRequest("IDEMPOTENCY_KEY_REQUIRED", "请提供请求编号，避免重复创建调拨单");
    }
    return value.trim();
  }

  private BigDecimal weightedCost(
      BigDecimal oldQuantity, BigDecimal oldCost, BigDecimal addedQuantity, BigDecimal addedCost
  ) {
    BigDecimal total = oldQuantity.add(addedQuantity);
    if (total.signum() == 0) {
      return ZERO;
    }
    return oldQuantity.multiply(oldCost).add(addedQuantity.multiply(addedCost))
        .divide(total, 4, RoundingMode.HALF_UP);
  }

  private void changed(int count) {
    if (count != 1) {
      throw concurrentConflict();
    }
  }

  private BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw badRequest("POSITIVE_VALUE_REQUIRED", label + "必须大于0");
    }
    return amount(value);
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private void log(AuthUser user, String action, String targetId, String reason) {
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), action, targetId, null, reason);
  }

  private BusinessException badRequest(String code, String message) {
    return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private BusinessException statusConflict(String message) {
    return conflict("TRANSFER_STATUS_CONFLICT", message);
  }

  private BusinessException concurrentConflict() {
    return conflict("WAREHOUSE_CONCURRENT_UPDATE", "库存已被其他操作更新，请刷新后重试");
  }
}
