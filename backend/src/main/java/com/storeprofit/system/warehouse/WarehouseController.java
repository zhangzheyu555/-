package com.storeprofit.system.warehouse;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {
  private final AuthService authService;
  private final WarehouseService warehouseService;

  public WarehouseController(AuthService authService, WarehouseService warehouseService) {
    this.authService = authService;
    this.warehouseService = warehouseService;
  }

  @GetMapping("/overview")
  public ApiResponse<WarehouseOverviewResponse> overview(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.overview(authService.requireUser(authorization)));
  }

  @GetMapping("/items")
  public ApiResponse<List<WarehouseItemResponse>> items(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(warehouseService.items(authService.requireUser(authorization)));
  }

  @PostMapping("/items")
  public ApiResponse<Void> saveItem(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseItemRequest request
  ) {
    warehouseService.saveItem(authService.requireUser(authorization), request);
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
}
