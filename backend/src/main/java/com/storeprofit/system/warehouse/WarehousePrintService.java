package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.AuthorizationService;
import com.storeprofit.system.platform.authorization.DataScope;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.platform.authorization.DataScopeModes;
import com.storeprofit.system.platform.authorization.PermissionCodes;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseMovementPrintRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseReceiptPrintRow;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehousePrintService {
  private final WarehouseRepository warehouseRepository;
  private final WarehousePdfRenderer pdfRenderer;
  private final AccessControlService accessControl;
  private final WarehouseTopologyService topologyService;

  @Autowired
  public WarehousePrintService(
      WarehouseRepository warehouseRepository,
      WarehousePdfRenderer pdfRenderer,
      AccessControlService accessControl,
      WarehouseTopologyService topologyService
  ) {
    this.warehouseRepository = warehouseRepository;
    this.pdfRenderer = pdfRenderer;
    this.accessControl = accessControl;
    this.topologyService = topologyService;
  }

  public WarehousePrintService(
      WarehouseRepository warehouseRepository,
      WarehousePdfRenderer pdfRenderer,
      AccessControlService accessControl
  ) {
    this(warehouseRepository, pdfRenderer, accessControl, null);
  }

  /** Compatibility constructor retained for isolated service tests. */
  public WarehousePrintService(WarehouseRepository warehouseRepository, WarehousePdfRenderer pdfRenderer) {
    this(warehouseRepository, pdfRenderer, null, null);
  }

  @Transactional
  public WarehousePrintDocument receiptPdf(AuthUser user, long batchId) {
    if (topologyService == null) {
      requireReceiptAccess(user);
    } else {
      if (isStoreManager(user)) {
        throw new BusinessException("FORBIDDEN", "店长不能下载包含采购成本的仓库入库单", HttpStatus.FORBIDDEN);
      }
      // Finance may inspect a scoped purchase inbound document, but it must never receive the
      // purchase/write capability merely to download that document.
      accessControl.requireWarehouseRead(user);
      requirePrintFacility(user, warehouseRepository.batchWarehouseId(user.tenantId(), batchId), "下载仓库入库单");
    }
    WarehouseReceiptPrintRow row = warehouseRepository.receiptPrintRow(user.tenantId(), batchId)
        .orElseThrow(() -> new BusinessException("RECEIPT_NOT_FOUND", "入库批次不存在", HttpStatus.NOT_FOUND));
    byte[] bytes = pdfRenderer.receipt(row);
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "下载入库单",
        "batch-" + batchId,
        null,
        row.itemName() + " / " + row.batchNo()
    );
    return new WarehousePrintDocument(receiptFilename(row), bytes);
  }

  @Transactional
  public WarehousePrintDocument deliveryPdf(AuthUser user, String requisitionId) {
    WarehouseDeliveryPrintHeader header = warehouseRepository.deliveryPrintHeader(user.tenantId(), requisitionId)
        .orElseThrow(() -> new BusinessException("DELIVERY_NOT_FOUND", "出库单不存在", HttpStatus.NOT_FOUND));
    boolean central = topologyService == null
        ? requireDeliveryAccess(user, header.storeId())
        : requirePrintStoreDocument(user,
            warehouseRepository.deliveryWarehouseId(user.tenantId(), requisitionId),
            header.storeId(), "下载该出库单");
    List<WarehouseDeliveryPrintLine> lines = warehouseRepository.deliveryPrintLines(user.tenantId(), requisitionId);
    if (!central) {
      lines = safeDeliveryLines(lines);
    }
    byte[] bytes = pdfRenderer.delivery(header, lines);
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "下载出库单",
        requisitionId,
        header.storeId(),
        header.storeName()
    );
    return new WarehousePrintDocument(deliveryFilename(header), bytes);
  }

  @Transactional
  public WarehousePrintDocument movementPdf(AuthUser user, long movementId) {
    WarehouseMovementPrintRow row = warehouseRepository.movementPrintRow(user.tenantId(), movementId)
        .orElseThrow(() -> new BusinessException("MOVEMENT_NOT_FOUND", "库存流水不存在", HttpStatus.NOT_FOUND));
    if ("OUT".equals(row.movementType()) && "REQUISITION".equals(row.sourceType()) && row.sourceId() != null && !row.sourceId().isBlank()) {
      return deliveryPdf(user, row.sourceId());
    }
    if (topologyService == null) {
      requireMovementAccess(user, row);
    } else {
      if (isStoreManager(user)) {
        throw new BusinessException("FORBIDDEN", "店长不能下载仓库库存流水单", HttpStatus.FORBIDDEN);
      }
      requirePrintFacility(user, warehouseRepository.movementWarehouseId(user.tenantId(), movementId), "下载库存流水单");
    }
    byte[] bytes = pdfRenderer.movement(row);
    String action = "IN".equals(row.movementType()) ? "下载入库单" : "下载库存流水单";
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        action,
        "movement-" + movementId,
        row.storeId(),
        row.itemName() + " / " + row.sourceId()
    );
    return new WarehousePrintDocument(movementFilename(row), bytes);
  }

  @Transactional
  public WarehousePrintDocument returnPdf(AuthUser user, String returnId) {
    WarehouseReturnResponse order = warehouseRepository.returnOrder(user.tenantId(), returnId)
        .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "配送退货单不存在", HttpStatus.NOT_FOUND));
    boolean central = topologyService == null
        ? requireReturnAccess(user, order.returnStoreId())
        : requirePrintStoreDocument(user,
            warehouseRepository.returnWarehouseId(user.tenantId(), returnId),
            order.returnStoreId(), "下载该配送退货单");
    if (order.receiveWarehouseName() == null || order.receiveWarehouseName().isBlank()) {
      throw new BusinessException(
          "RETURN_RECEIVE_WAREHOUSE_SNAPSHOT_MISSING",
          "配送退货单缺少收货仓历史快照，不能打印",
          HttpStatus.CONFLICT);
    }
    byte[] bytes = pdfRenderer.returnOrder(central ? order : safeReturn(order));
    warehouseRepository.logAction(
        user.tenantId(),
        user.id(),
        user.displayName(),
        "下载配送退货单",
        order.returnNo(),
        order.returnStoreId(),
        order.returnStoreName()
    );
    return new WarehousePrintDocument(returnFilename(order), bytes);
  }

  private void requireReceiptAccess(AuthUser user) {
    if (accessControl != null) {
      accessControl.requireWarehouseCentralRead(user);
      DataScope dataScope = warehouseDataScope(user);
      if (dataScope.allowsAllStores() || DataScopeModes.CENTRAL_WAREHOUSE.equals(dataScope.mode())) {
        return;
      }
      accessControl.requireStoreAccess(
          user,
          DataScopeDomains.WAREHOUSE,
          "__CENTRAL_WAREHOUSE__",
          "下载仓库入库单"
      );
      throw new BusinessException("FORBIDDEN", "无权下载入库单", HttpStatus.FORBIDDEN);
    }
    if (hasLegacyPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ)) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "无权下载入库单", HttpStatus.FORBIDDEN);
  }

  private boolean requirePrintFacility(AuthUser user, java.util.Optional<Long> warehouseId, String action) {
    long id = warehouseId.orElseThrow(() -> new BusinessException(
        "WAREHOUSE_NOT_FOUND", "单据未关联有效仓库", HttpStatus.CONFLICT));
    topologyService.visibleFacilities(user);
    topologyService.requireVisibleFacility(user, id, action);
    return user != null && !"STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private boolean requirePrintStoreDocument(
      AuthUser user,
      java.util.Optional<Long> warehouseId,
      String storeId,
      String action
  ) {
    boolean central = requirePrintFacility(user, warehouseId, action);
    if (!central) {
      requireStoreDocumentAccess(user, storeId, action);
    }
    return central;
  }

  private boolean isStoreManager(AuthUser user) {
    return user != null
        && "STORE_MANAGER".equals(AccessControlService.canonicalRole(user.role()));
  }

  private boolean requireDeliveryAccess(AuthUser user, String storeId) {
    return requireStoreDocumentAccess(user, storeId, "下载该出库单");
  }

  private boolean requireReturnAccess(AuthUser user, String storeId) {
    return requireStoreDocumentAccess(user, storeId, "下载该配送退货单");
  }

  private void requireMovementAccess(AuthUser user, WarehouseMovementPrintRow row) {
    if ("IN".equals(row.movementType())) {
      requireReceiptAccess(user);
      return;
    }
    requireDeliveryAccess(user, row.storeId());
  }

  private boolean requireStoreDocumentAccess(AuthUser user, String storeId, String action) {
    if (accessControl != null) {
      boolean centralPermission = accessControl.hasPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ);
      DataScope dataScope = warehouseDataScope(user);
      if (centralPermission) {
        accessControl.requireWarehouseCentralRead(user);
        if (dataScope.allowsAllStores() || DataScopeModes.CENTRAL_WAREHOUSE.equals(dataScope.mode())) {
          return true;
        }
      } else {
        accessControl.requireWarehouseStoreRead(user);
      }
      accessControl.requireStoreAccess(user, DataScopeDomains.WAREHOUSE, storeId, action);
      return false;
    }
    if (hasLegacyPermission(user, PermissionCodes.WAREHOUSE_CENTRAL_READ)) {
      return true;
    }
    if (hasLegacyPermission(user, PermissionCodes.WAREHOUSE_STORE_READ)
        && user != null
        && storeId != null
        && storeId.equals(user.storeId())) {
      return false;
    }
    throw new BusinessException("FORBIDDEN", "无权" + action, HttpStatus.FORBIDDEN);
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

  private boolean hasLegacyPermission(AuthUser user, String permissionCode) {
    return AccessControlService.isBoss(user)
        || AuthorizationService.legacyTemplatePermissions(user == null ? null : user.role())
            .contains(permissionCode);
  }

  private List<WarehouseDeliveryPrintLine> safeDeliveryLines(List<WarehouseDeliveryPrintLine> lines) {
    return lines.stream()
        .map(line -> new WarehouseDeliveryPrintLine(
            line.itemId(),
            line.itemName(),
            line.spec(),
            line.unit(),
            line.shippedQuantity(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            line.batchNos(),
            line.note()
        ))
        .toList();
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
        BigDecimal.ZERO,
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
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                line.reason(),
                line.note()
            ))
            .toList()
    );
  }

  private String receiptFilename(WarehouseReceiptPrintRow row) {
    return "入库单-" + WarehouseDocumentNumbers.receipt(row.createdAt(), row.batchId()) + ".pdf";
  }

  private String deliveryFilename(WarehouseDeliveryPrintHeader header) {
    return "配送单-" + WarehouseDocumentNumbers.delivery(
        header.shippedAt(),
        header.deliveryId(),
        header.requisitionId()
    ) + ".pdf";
  }

  private String movementFilename(WarehouseMovementPrintRow row) {
    if ("IN".equals(row.movementType())) {
      return "入库单-" + WarehouseDocumentNumbers.receipt(
          row.createdAt(),
          row.movementId()
      ) + ".pdf";
    }
    String prefix = "库存流水单";
    return prefix + "-" + dateCompact(row.createdAt()) + "-" + safeName(row.itemName()) + "-" + safeName(row.sourceId()) + ".pdf";
  }

  private String returnFilename(WarehouseReturnResponse order) {
    return "配送退货单-" + WarehouseDocumentNumbers.returnOrder(
        order.returnDate(),
        order.returnNo(),
        order.id()
    ) + ".pdf";
  }

  private String dateCompact(String value) {
    if (value == null || value.length() < 10) {
      return "00000000";
    }
    return value.substring(0, 10).replace("-", "");
  }

  private String safeName(String value) {
    if (value == null || value.isBlank()) {
      return "未命名";
    }
    return value.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
  }
}
