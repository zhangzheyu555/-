package com.storeprofit.system.warehouse;

import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse/requests")
public class WarehouseRequisitionReportController {
  private static final MediaType XLSX = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final AuthService authService;
  private final WarehouseService warehouseService;

  public WarehouseRequisitionReportController(
      AuthService authService,
      WarehouseService warehouseService
  ) {
    this.authService = authService;
    this.warehouseService = warehouseService;
  }

  @PostMapping(value = "/export-summary",
      produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  public ResponseEntity<byte[]> exportSummary(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody WarehouseRequisitionSummaryExportRequest request
  ) {
    WarehouseRequisitionSummaryExport export = warehouseService.exportRequisitionSummary(
        authService.requireUser(authorization),
        request
    );
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(export.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
        .body(export.content());
  }
}
