package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {
  private final WarehouseRepository warehouseRepository;

  public WarehouseService(WarehouseRepository warehouseRepository) {
    this.warehouseRepository = warehouseRepository;
  }

  public WarehouseOverviewResponse overview(AuthUser user) {
    requireWarehouseAccess(user);
    List<WarehouseItemResponse> items = warehouseRepository.items(user.tenantId());
    List<WarehouseAlertResponse> alerts = alerts(items);
    List<WarehouseRequisitionResponse> requisitions = requisitionsFor(user);
    WarehouseSummaryResponse summary = new WarehouseSummaryResponse(
        items.size(),
        (int) items.stream().filter(item -> "LOW".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "EXPIRING".equals(item.alertLevel())).count(),
        (int) items.stream().filter(item -> "OVERSTOCK".equals(item.alertLevel())).count(),
        warehouseRepository.pendingRequisitionCount(user.tenantId()),
        items.stream().map(WarehouseItemResponse::stockValue).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)
    );
    return new WarehouseOverviewResponse(summary, alerts, items, requisitions);
  }

  public List<WarehouseItemResponse> items(AuthUser user) {
    requireWarehouseAccess(user);
    return warehouseRepository.items(user.tenantId());
  }

  @Transactional
  public void saveItem(AuthUser user, WarehouseItemRequest request) {
    requireWarehouseManage(user);
    warehouseRepository.upsertItem(user.tenantId(), request);
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "保存物料", request.code(), null, request.name());
  }

  @Transactional
  public void receiveStock(AuthUser user, WarehouseStockBatchRequest request) {
    requireWarehouseManage(user);
    if (!warehouseRepository.itemExists(user.tenantId(), request.itemId())) {
      throw new BusinessException("ITEM_NOT_FOUND", "物料不存在", HttpStatus.BAD_REQUEST);
    }
    parseDate(request.receivedDate(), "到货日期");
    if (request.expiryDate() != null && !request.expiryDate().isBlank()) {
      parseDate(request.expiryDate(), "到期日期");
    }
    warehouseRepository.upsertBatch(user.tenantId(), request);
    warehouseRepository.insertMovement(
        user.tenantId(),
        request.itemId(),
        null,
        "IN",
        request.quantity(),
        "MANUAL_RECEIVE",
        request.batchNo(),
        null,
        request.note(),
        user.id()
    );
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "仓库入库", request.batchNo(), null, request.note());
  }

  public List<WarehouseRequisitionResponse> requisitions(AuthUser user) {
    requireWarehouseAccess(user);
    return requisitionsFor(user);
  }

  @Transactional
  public WarehouseRequisitionResponse createRequisition(AuthUser user, WarehouseRequisitionRequest request) {
    requireWarehouseAccess(user);
    String storeId = normalizeStoreForSubmit(user, request.storeId());
    if (!warehouseRepository.storeExists(user.tenantId(), storeId)) {
      throw new BusinessException("STORE_NOT_FOUND", "门店不存在", HttpStatus.BAD_REQUEST);
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
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "提交叫货", id, storeId, request.note());
    return warehouseRepository.requisition(user.tenantId(), id)
        .orElseThrow(() -> new BusinessException("REQ_NOT_FOUND", "叫货单保存失败", HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Transactional
  public void review(AuthUser user, String requisitionId, WarehouseRequisitionReviewRequest request) {
    requireWarehouseManage(user);
    WarehouseRequisitionResponse requisition = requireRequisition(user.tenantId(), requisitionId);
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
    WarehouseRequisitionResponse requisition = requireRequisition(user.tenantId(), requisitionId);
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
    warehouseRepository.logAction(user.tenantId(), user.id(), user.displayName(), "完成配货", requisitionId, requisition.storeId(), "仓库出库");
  }

  private void deductStock(AuthUser user, WarehouseRequisitionResponse requisition, long itemId, BigDecimal quantity) {
    BigDecimal remaining = amount(quantity);
    List<WarehouseStockBatchRow> batches = warehouseRepository.positiveBatches(user.tenantId(), itemId);
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
    if ("STORE_MANAGER".equals(user.role()) && user.storeId() != null && !user.storeId().isBlank()) {
      return warehouseRepository.requisitions(user.tenantId(), user.storeId());
    }
    return warehouseRepository.requisitions(user.tenantId(), null);
  }

  private WarehouseRequisitionResponse requireRequisition(long tenantId, String requisitionId) {
    return warehouseRepository.requisition(tenantId, requisitionId)
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

  private String warningForOrder(WarehouseItemResponse item, BigDecimal quantity) {
    if (quantity.compareTo(item.stockQuantity()) > 0) {
      return "仓库当前库存不足，最多可配 " + item.stockQuantity().stripTrailingZeros().toPlainString() + item.unit();
    }
    if (item.dailyUsageEstimate().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal days = quantity.divide(item.dailyUsageEstimate(), 1, RoundingMode.HALF_UP);
      if (days.compareTo(BigDecimal.valueOf(item.maxStockDays())) > 0) {
        return "叫货量约可用 " + days.stripTrailingZeros().toPlainString() + " 天，超过建议上限";
      }
    }
    return null;
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

  private void requireWarehouseAccess(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "WAREHOUSE", "STORE_MANAGER").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "无权访问仓库中心", HttpStatus.FORBIDDEN);
    }
  }

  private void requireWarehouseManage(AuthUser user) {
    if (!List.of("ADMIN", "BOSS", "WAREHOUSE").contains(user.role())) {
      throw new BusinessException("FORBIDDEN", "仅老板和仓库管理员可维护仓库数据", HttpStatus.FORBIDDEN);
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

  private record LineDraft(
      WarehouseItemResponse item,
      BigDecimal quantity,
      String warning,
      String note
  ) {
  }
}
