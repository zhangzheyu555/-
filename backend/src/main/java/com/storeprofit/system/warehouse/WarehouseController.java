package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {
  private final AuthService authService;
  private final WarehouseService warehouseService;
  private final WarehousePrintService warehousePrintService;
  private final WarehouseNetworkService warehouseNetworkService;

  @Autowired
  public WarehouseController(
      AuthService authService,
      WarehouseService warehouseService,
      WarehousePrintService warehousePrintService,
      WarehouseNetworkService warehouseNetworkService
  ) {
    this.authService = authService;
    this.warehouseService = warehouseService;
    this.warehousePrintService = warehousePrintService;
    this.warehouseNetworkService = warehouseNetworkService;
  }

  /** Compatibility constructor for isolated controller tests. */
  public WarehouseController(
      AuthService authService,
      WarehouseService warehouseService,
      WarehousePrintService warehousePrintService
  ) {
    this(authService, warehouseService, warehousePrintService, null);
  }

  @GetMapping("/warehouses")
  public ApiResponse<List<WarehouseFacilityResponse>> warehouses(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseNetworkService.facilities(authService.requireUser(authorization)));
  }

  @GetMapping("/overview")
  public ApiResponse<WarehouseOverviewResponse> overview(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "warehouseId", required = false) Long warehouseId
  ) {
    return ApiResponse.ok(warehouseService.overview(authService.requireUser(authorization), warehouseId));
  }

  @GetMapping("/transfers")
  public ApiResponse<List<WarehouseTransferResponse>> transfers(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "warehouseId", required = false) Long warehouseId
  ) {
    return ApiResponse.ok(warehouseNetworkService.transfers(authService.requireUser(authorization), warehouseId));
  }

  @GetMapping("/transfers/context")
  public ApiResponse<WarehouseTransferContextResponse> transferContext(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "warehouseId", required = false) Long warehouseId
  ) {
    return ApiResponse.ok(warehouseNetworkService.transferContext(
        authService.requireUser(authorization), warehouseId));
  }

  @GetMapping("/transfers/{id}")
  public ApiResponse<WarehouseTransferResponse> transfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(warehouseNetworkService.transfer(authService.requireUser(authorization), id));
  }

  @PostMapping("/transfers")
  public ApiResponse<WarehouseTransferResponse> createTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseTransferCreateRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.create(authService.requireUser(authorization), request));
  }

  @PostMapping("/transfers/{id}/submit")
  public ApiResponse<WarehouseTransferResponse> submitTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) WarehouseTransferActionRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.submit(authService.requireUser(authorization), id,
        request == null ? WarehouseTransferActionRequest.empty() : request));
  }

  @PostMapping("/transfers/{id}/review")
  public ApiResponse<WarehouseTransferResponse> reviewTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody WarehouseTransferReviewRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.review(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/transfers/{id}/ship")
  public ApiResponse<WarehouseTransferResponse> shipTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) WarehouseTransferActionRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.ship(authService.requireUser(authorization), id,
        request == null ? WarehouseTransferActionRequest.empty() : request));
  }

  @PostMapping("/transfers/{id}/receive")
  public ApiResponse<WarehouseTransferResponse> receiveTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) WarehouseTransferReceiveRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.receive(authService.requireUser(authorization), id,
        request == null ? WarehouseTransferReceiveRequest.empty() : request));
  }

  @PostMapping("/transfers/{id}/cancel")
  public ApiResponse<WarehouseTransferResponse> cancelTransfer(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) WarehouseTransferActionRequest request
  ) {
    return ApiResponse.ok(warehouseNetworkService.cancel(authService.requireUser(authorization), id,
        request == null ? WarehouseTransferActionRequest.empty() : request));
  }

  @GetMapping("/items")
  public ApiResponse<List<WarehouseItemResponse>> items(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.items(authService.requireUser(authorization)));
  }

  @GetMapping("/items/{id}")
  public ApiResponse<WarehouseItemResponse> item(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    return ApiResponse.ok(warehouseService.item(authService.requireUser(authorization), id));
  }

  @PostMapping("/items")
  public ApiResponse<Void> saveItem(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseItemRequest request
  ) {
    warehouseService.saveItem(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }

  @PostMapping("/items/{id}/enabled")
  public ApiResponse<Void> setItemEnabled(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id,
      @RequestBody(required = false) WarehouseItemEnabledRequest request
  ) {
    warehouseService.setItemEnabled(authService.requireUser(authorization), id, request);
    return ApiResponse.ok();
  }

  @GetMapping("/item-categories")
  public ApiResponse<List<WarehouseItemCategoryResponse>> itemCategories(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.itemCategories(authService.requireUser(authorization)));
  }

  @PostMapping("/item-categories")
  public ApiResponse<WarehouseItemCategoryResponse> saveItemCategory(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseItemCategoryRequest request
  ) {
    return ApiResponse.ok(warehouseService.saveItemCategory(authService.requireUser(authorization), request));
  }

  @PostMapping("/item-categories/{id}/enabled")
  public ApiResponse<Void> setItemCategoryEnabled(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id,
      @RequestBody(required = false) WarehouseItemEnabledRequest request
  ) {
    warehouseService.setItemCategoryEnabled(authService.requireUser(authorization), id, request);
    return ApiResponse.ok();
  }

  @DeleteMapping("/item-categories/{id}")
  public ApiResponse<Void> deleteItemCategory(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id
  ) {
    warehouseService.deleteItemCategory(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }

  @PostMapping("/items/{id}/alert-settings")
  public ApiResponse<Void> updateAlertSettings(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long id,
      @Valid @RequestBody WarehouseAlertSettingsRequest request
  ) {
    warehouseService.updateAlertSettings(authService.requireUser(authorization), id, request);
    return ApiResponse.ok();
  }

  @PostMapping("/stock-batches")
  public ApiResponse<Void> receiveStock(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseStockBatchRequest request
  ) {
    warehouseService.receiveStock(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }

  @GetMapping("/movements")
  public ApiResponse<List<WarehouseStockMovementResponse>> movements(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.movements(authService.requireUser(authorization)));
  }

  @PostMapping("/returns")
  public ApiResponse<WarehouseReturnResponse> createReturn(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseReturnRequest request
  ) {
    return ApiResponse.ok(warehouseService.createReturn(authService.requireUser(authorization), request));
  }

  @GetMapping("/returns")
  public ApiResponse<List<WarehouseReturnResponse>> returns(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.returns(authService.requireUser(authorization)));
  }

  @GetMapping("/returns/{returnId}")
  public ApiResponse<WarehouseReturnResponse> returnOrder(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String returnId
  ) {
    return ApiResponse.ok(warehouseService.returnOrder(authService.requireUser(authorization), returnId));
  }

  @PostMapping("/returns/{returnId}/review")
  public ApiResponse<WarehouseReturnResponse> reviewReturn(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String returnId,
      @RequestBody(required = false) WarehouseReturnReviewRequest request
  ) {
    return ApiResponse.ok(warehouseService.reviewReturn(
        authService.requireUser(authorization),
        returnId,
        request == null ? new WarehouseReturnReviewRequest(false, null) : request
    ));
  }

  @PostMapping("/returns/{returnId}/receive")
  public ApiResponse<WarehouseReturnResponse> receiveReturn(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String returnId,
      @RequestBody(required = false) WarehouseReturnReceiveRequest request
  ) {
    return ApiResponse.ok(warehouseService.receiveReturn(
        authService.requireUser(authorization),
        returnId,
        request == null ? new WarehouseReturnReceiveRequest(null) : request
    ));
  }

  @PostMapping("/purchase-orders")
  public ApiResponse<WarehousePurchaseOrderResponse> createPurchaseOrder(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehousePurchaseOrderRequest request
  ) {
    return ApiResponse.ok(warehouseService.createPurchaseOrder(authService.requireUser(authorization), request));
  }

  @PostMapping("/purchase-orders/{id}/approve")
  public ApiResponse<WarehousePurchaseOrderResponse> approvePurchaseOrder(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(warehouseService.approvePurchaseOrder(authService.requireUser(authorization), id));
  }

  @PostMapping("/purchase-orders/{id}/receive")
  public ApiResponse<WarehousePurchaseOrderResponse> receivePurchaseOrder(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody WarehousePurchaseReceiveRequest request
  ) {
    return ApiResponse.ok(warehouseService.receivePurchaseOrder(authService.requireUser(authorization), id, request));
  }

  @GetMapping("/requisitions")
  public ApiResponse<List<WarehouseRequisitionResponse>> requisitions(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.requisitions(authService.requireUser(authorization)));
  }

  @PostMapping("/requisitions")
  public ApiResponse<WarehouseRequisitionResponse> createRequisition(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseRequisitionRequest request
  ) {
    return ApiResponse.ok(warehouseService.createRequisition(authService.requireUser(authorization), request));
  }

  @PostMapping("/requisitions/{id}/review")
  public ApiResponse<Void> review(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody WarehouseRequisitionReviewRequest request
  ) {
    warehouseService.review(authService.requireUser(authorization), id, request);
    return ApiResponse.ok();
  }

  @PostMapping("/requisitions/{id}/ship")
  public ApiResponse<Void> ship(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    warehouseService.ship(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }

  @PostMapping("/requisitions/{id}/receive")
  public ApiResponse<Void> receiveByStore(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) WarehouseReceiptRequest request
  ) {
    warehouseService.receiveByStore(authService.requireUser(authorization), id, request == null ? new WarehouseReceiptRequest(null) : request);
    return ApiResponse.ok();
  }

  @GetMapping(value = "/print/receipts/{batchId}", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> printReceipt(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long batchId
  ) {
    return pdfResponse(warehousePrintService.receiptPdf(authService.requireUser(authorization), batchId));
  }

  @GetMapping(value = "/print/requisitions/{requisitionId}/delivery", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> printDelivery(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String requisitionId
  ) {
    return pdfResponse(warehousePrintService.deliveryPdf(authService.requireUser(authorization), requisitionId));
  }

  @GetMapping(value = "/print/movements/{movementId}", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> printMovement(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable long movementId
  ) {
    return pdfResponse(warehousePrintService.movementPdf(authService.requireUser(authorization), movementId));
  }

  @GetMapping(value = "/print/returns/{returnId}", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> printReturn(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String returnId
  ) {
    return pdfResponse(warehousePrintService.returnPdf(authService.requireUser(authorization), returnId));
  }

  private ResponseEntity<byte[]> pdfResponse(WarehousePrintDocument document) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(document.filename(), StandardCharsets.UTF_8)
            .build()
            .toString())
        .body(document.bytes());
  }
}
