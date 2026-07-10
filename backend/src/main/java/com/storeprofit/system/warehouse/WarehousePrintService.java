package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintHeader;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseDeliveryPrintLine;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseMovementPrintRow;
import com.storeprofit.system.warehouse.WarehouseRepository.WarehouseReceiptPrintRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehousePrintService {
  private final WarehouseRepository warehouseRepository;
  private final WarehousePdfRenderer pdfRenderer;

  public WarehousePrintService(WarehouseRepository warehouseRepository, WarehousePdfRenderer pdfRenderer) {
    this.warehouseRepository = warehouseRepository;
    this.pdfRenderer = pdfRenderer;
  }

  @Transactional
  public WarehousePrintDocument receiptPdf(AuthUser user, long batchId) {
    requireReceiptAccess(user);
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
    requireDeliveryAccess(user, header.storeId());
    List<WarehouseDeliveryPrintLine> lines = warehouseRepository.deliveryPrintLines(user.tenantId(), requisitionId);
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
    requireMovementAccess(user, row);
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
    requireReturnAccess(user, order.returnStoreId());
    byte[] bytes = pdfRenderer.returnOrder(order);
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
    if (user != null && List.of("ADMIN", "BOSS", "WAREHOUSE", "FINANCE").contains(user.role())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "无权下载入库单", HttpStatus.FORBIDDEN);
  }

  private void requireDeliveryAccess(AuthUser user, String storeId) {
    if (user != null && List.of("ADMIN", "BOSS", "WAREHOUSE").contains(user.role())) {
      return;
    }
    if (user != null && "STORE_MANAGER".equals(user.role()) && storeId != null && storeId.equals(user.storeId())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "无权下载该出库单", HttpStatus.FORBIDDEN);
  }

  private void requireReturnAccess(AuthUser user, String storeId) {
    if (user != null && List.of("ADMIN", "BOSS", "WAREHOUSE", "FINANCE").contains(user.role())) {
      return;
    }
    if (user != null && "STORE_MANAGER".equals(user.role()) && storeId != null && storeId.equals(user.storeId())) {
      return;
    }
    throw new BusinessException("FORBIDDEN", "无权下载该配送退货单", HttpStatus.FORBIDDEN);
  }

  private void requireMovementAccess(AuthUser user, WarehouseMovementPrintRow row) {
    if ("IN".equals(row.movementType())) {
      requireReceiptAccess(user);
      return;
    }
    requireDeliveryAccess(user, row.storeId());
  }

  private String receiptFilename(WarehouseReceiptPrintRow row) {
    return "入库单-" + dateCompact(row.createdAt()) + "-" + safeName(row.itemName()) + "-" + safeName(row.batchNo()) + ".pdf";
  }

  private String deliveryFilename(WarehouseDeliveryPrintHeader header) {
    return "出库单-" + dateCompact(header.shippedAt()) + "-" + safeName(header.storeName()) + "-" + safeName(header.requisitionId()) + ".pdf";
  }

  private String movementFilename(WarehouseMovementPrintRow row) {
    String prefix = "IN".equals(row.movementType()) ? "入库单" : "库存流水单";
    return prefix + "-" + dateCompact(row.createdAt()) + "-" + safeName(row.itemName()) + "-" + safeName(row.sourceId()) + ".pdf";
  }

  private String returnFilename(WarehouseReturnResponse order) {
    return "配送退货单-" + dateCompact(order.returnDate()) + "-" + safeName(order.returnNo()) + ".pdf";
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
