package com.storeprofit.system.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class WarehousePrintControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final WarehouseService warehouseService = mock(WarehouseService.class);
  private final WarehousePrintService printService = mock(WarehousePrintService.class);
  private final WarehouseController controller = new WarehouseController(authService, warehouseService, printService);
  private final AuthUser warehouse = new AuthUser(2L, 1L, "default", "warehouse", "", "仓库管理员", "WAREHOUSE", null, true);

  @Test
  void receiptPrintEndpointReturnsPdfAttachment() {
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F'};
    when(authService.requireUser("Bearer token")).thenReturn(warehouse);
    when(printService.receiptPdf(warehouse, 7L)).thenReturn(
        new WarehousePrintDocument("入库单-RKD260708557452919.pdf", pdf)
    );

    ResponseEntity<byte[]> response = controller.printReceipt("Bearer token", 7L);

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
    assertThat(response.getHeaders().getContentDisposition().getFilename())
        .isEqualTo("入库单-RKD260708557452919.pdf");
    assertThat(response.getBody()).isSameAs(pdf);
    verify(printService).receiptPdf(warehouse, 7L);
  }

  @Test
  void deliveryPrintEndpointReturnsPdfAttachment() {
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F'};
    when(authService.requireUser("Bearer token")).thenReturn(warehouse);
    when(printService.deliveryPdf(warehouse, "REQ1")).thenReturn(
        new WarehousePrintDocument("配送单-PSD260708242206633.pdf", pdf)
    );

    ResponseEntity<byte[]> response = controller.printDelivery("Bearer token", "REQ1");

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
    assertThat(response.getHeaders().getContentDisposition().getFilename())
        .isEqualTo("配送单-PSD260708242206633.pdf");
    assertThat(response.getBody()).isSameAs(pdf);
    verify(printService).deliveryPdf(warehouse, "REQ1");
  }

  @Test
  void returnPrintEndpointReturnsPdfAttachment() {
    byte[] pdf = new byte[] {'%', 'P', 'D', 'F'};
    when(authService.requireUser("Bearer token")).thenReturn(warehouse);
    when(printService.returnPdf(warehouse, "PSTH1")).thenReturn(
        new WarehousePrintDocument("配送退货单-PSTH260707882937764.pdf", pdf)
    );

    ResponseEntity<byte[]> response = controller.printReturn("Bearer token", "PSTH1");

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
    assertThat(response.getHeaders().getContentDisposition().getFilename())
        .isEqualTo("配送退货单-PSTH260707882937764.pdf");
    assertThat(response.getBody()).isSameAs(pdf);
    verify(printService).returnPdf(warehouse, "PSTH1");
  }
}
