package com.storeprofit.system.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.common.GlobalExceptionHandler;
import com.storeprofit.system.inspection.InspectionController;
import com.storeprofit.system.inspection.InspectionExportService;
import com.storeprofit.system.inspection.InspectionRecordRequest;
import com.storeprofit.system.inspection.InspectionService;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthController;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.storage.StorageController;
import com.storeprofit.system.storage.StorageService;
import com.storeprofit.system.warehouse.WarehouseController;
import com.storeprofit.system.warehouse.WarehouseNetworkService;
import com.storeprofit.system.warehouse.WarehousePrintService;
import com.storeprofit.system.warehouse.WarehouseRepository;
import com.storeprofit.system.warehouse.WarehouseRequisitionRequest;
import com.storeprofit.system.warehouse.WarehouseRequisitionResponse;
import com.storeprofit.system.warehouse.WarehouseService;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository;
import com.storeprofit.system.warehouse.WarehouseTopologyRepository.FacilityRow;
import com.storeprofit.system.warehouse.WarehouseTopologyService;
import com.storeprofit.system.warehouse.WarehouseTransferResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP contract guard used by H5, MP-WEIXIN and APP-PLUS clients.
 *
 * <p>The mobile application deliberately reuses the formal desktop API. These tests pin the
 * platform-neutral Bearer-token boundary and the security/idempotency behaviour that must not be
 * weakened by a mobile client.</p>
 */
class MobileApiContractTest {
  private static final String MANAGER_TOKEN = "Bearer mobile-manager-token";
  private static final AuthUser MANAGER = new AuthUser(
      7L, 1L, "测试企业", "manager-s1", "", "一店店长", "STORE_MANAGER", "s1", true);
  private static final String INSPECTOR_TOKEN = "Bearer mobile-inspector-token";
  private static final AuthUser INSPECTOR = new AuthUser(
      8L, 1L, "测试企业", "inspector", "", "移动督导", "SUPERVISOR", "s1", true);

  @Test
  void unauthenticatedSessionReturns401() throws Exception {
    AuthService authService = mock(AuthService.class);
    when(authService.requireUser(null)).thenThrow(
        new BusinessException("UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED));

    mockMvc(new AuthController(authService))
        .perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void crossStoreInspectionReturns403() throws Exception {
    AccessControlService accessControl = mock(AccessControlService.class);
    InspectionService inspectionService = mock(InspectionService.class);
    when(accessControl.requireUser(MANAGER_TOKEN)).thenReturn(MANAGER);
    when(inspectionService.records(
        eq(MANAGER), nullable(String.class), nullable(String.class), nullable(Long.class),
        eq("s2"), nullable(Boolean.class)))
        .thenThrow(new BusinessException("FORBIDDEN", "门店不在当前账号的数据范围内", HttpStatus.FORBIDDEN));

    mockMvc(new InspectionController(
        accessControl, inspectionService, mock(InspectionExportService.class)))
        .perform(get("/api/inspections")
            .header("Authorization", MANAGER_TOKEN)
            .param("storeId", "s2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(accessControl).requireInspectionRead(MANAGER);
  }

  @Test
  void crossWarehouseContextReturns403() throws Exception {
    AuthService authService = mock(AuthService.class);
    WarehouseNetworkService networkService = mock(WarehouseNetworkService.class);
    when(authService.requireUser(MANAGER_TOKEN)).thenReturn(MANAGER);
    when(networkService.transferContext(MANAGER, 99L)).thenThrow(
        new BusinessException("FORBIDDEN", "仓库不在当前账号的数据范围内", HttpStatus.FORBIDDEN));

    mockMvc(new WarehouseController(
        authService, mock(WarehouseService.class), mock(WarehousePrintService.class), networkService))
        .perform(get("/api/warehouse/transfers/context")
            .header("Authorization", MANAGER_TOKEN)
            .param("warehouseId", "99"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void crossStoreAttachmentReturns403() throws Exception {
    AuthService authService = mock(AuthService.class);
    StorageService storageService = mock(StorageService.class);
    when(authService.requireUser(MANAGER_TOKEN)).thenReturn(MANAGER);
    when(storageService.attachment(MANAGER, 88L)).thenThrow(
        new BusinessException("FORBIDDEN", "附件不属于当前门店", HttpStatus.FORBIDDEN));

    mockMvc(new StorageController(authService, storageService))
        .perform(get("/api/storage/attachments/88")
            .header("Authorization", MANAGER_TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void repeatedStoreRequisitionWithSameClientRequestIdReturnsSameOrder() throws Exception {
    AuthService authService = mock(AuthService.class);
    WarehouseService warehouseService = mock(WarehouseService.class);
    WarehouseRequisitionResponse existing = new WarehouseRequisitionResponse(
        "REQ-MOBILE-1", "s1", "一店", "DRAFT", "草稿", BigDecimal.ZERO,
        "移动端弱网重试", "一店店长", null, null, null,
        "2026-07-15 10:00", null, null, null, List.of());
    when(authService.requireUser(MANAGER_TOKEN)).thenReturn(MANAGER);
    when(warehouseService.createRequisition(eq(MANAGER), any(WarehouseRequisitionRequest.class)))
        .thenReturn(existing);
    MockMvc mvc = mockMvc(new WarehouseController(
        authService, warehouseService, mock(WarehousePrintService.class),
        mock(WarehouseNetworkService.class)));
    String request = """
        {
          "storeId": "s1",
          "lines": [{"itemId": 10, "requestedQuantity": 2, "note": "门店补货"}],
          "note": "移动端弱网重试",
          "clientRequestId": "mobile-requisition-1"
        }
        """;

    for (int replay = 0; replay < 2; replay++) {
      mvc.perform(post("/api/warehouse/requisitions")
              .header("Authorization", MANAGER_TOKEN)
              .contentType(APPLICATION_JSON)
              .content(request))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value("REQ-MOBILE-1"))
          .andExpect(jsonPath("$.data.storeId").value("s1"))
          .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    verify(warehouseService, times(2)).createRequisition(
        eq(MANAGER),
        argThat(body -> "mobile-requisition-1".equals(body.clientRequestId())));
  }

  @Test
  void repeatedInspectionSaveUsesTheSameStableRecordId() throws Exception {
    AccessControlService accessControl = mock(AccessControlService.class);
    InspectionService inspectionService = mock(InspectionService.class);
    when(accessControl.requireUser(INSPECTOR_TOKEN)).thenReturn(INSPECTOR);
    MockMvc mvc = mockMvc(new InspectionController(
        accessControl, inspectionService, mock(InspectionExportService.class)));
    String recordId = "MOBINSP-20260715-stable";
    String request = """
        {
          "storeId": "s1",
          "inspectionDate": "2026-07-15",
          "fullScore": 200,
          "itemResults": []
        }
        """;

    for (int replay = 0; replay < 2; replay++) {
      mvc.perform(put("/api/inspections/{id}", recordId)
              .header("Authorization", INSPECTOR_TOKEN)
              .contentType(APPLICATION_JSON)
              .content(request))
          .andExpect(status().isOk());
    }

    verify(inspectionService, times(2)).save(
        eq(INSPECTOR), eq(recordId), argThat((InspectionRecordRequest body) ->
            "s1".equals(body.storeId()) && "2026-07-15".equals(body.inspectionDate())));
  }

  @Test
  void repeatedTransferCreateWithSameClientRequestIdReturnsSameOrder() throws Exception {
    AuthService authService = mock(AuthService.class);
    WarehouseTopologyRepository topologyRepository = mock(WarehouseTopologyRepository.class);
    WarehouseTopologyService topologyService = mock(WarehouseTopologyService.class);
    WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
    FacilityRow source = new FacilityRow(
        1L, "JZ-CENTRAL", "荆州总仓", "CENTRAL", "JINGZHOU",
        null, null, true, true, true);
    FacilityRow target = new FacilityRow(
        2L, "SD-REGIONAL", "山东分仓", "REGIONAL", "SHANDONG",
        1L, "荆州总仓", false, true, true);
    WarehouseTransferResponse existing = new WarehouseTransferResponse(
        "transfer-mobile-1", "DB202607150001", "DRAFT", "草稿",
        1L, "荆州总仓", 2L, "山东分仓", BigDecimal.ZERO,
        "一店店长", null, null, null, null,
        "2026-07-15 10:00", null, null, null, null, null,
        "移动端弱网重试", null, 0L, List.of());
    when(authService.requireUser(MANAGER_TOKEN)).thenReturn(MANAGER);
    when(topologyRepository.facility(1L, 1L)).thenReturn(Optional.of(source));
    when(topologyRepository.facility(1L, 2L)).thenReturn(Optional.of(target));
    when(topologyRepository.transferByCreateKey(1L, "mobile-transfer-create-1"))
        .thenReturn(Optional.of(existing));
    WarehouseNetworkService networkService = new WarehouseNetworkService(
        topologyRepository, topologyService, warehouseRepository, mock(AccessControlService.class));
    MockMvc mvc = mockMvc(new WarehouseController(
        authService, mock(WarehouseService.class), mock(WarehousePrintService.class), networkService));
    String request = """
        {
          "sourceWarehouseId": 1,
          "targetWarehouseId": 2,
          "lines": [{"itemId": 10, "quantity": 1, "note": "补货"}],
          "note": "移动端弱网重试",
          "clientRequestId": "mobile-transfer-create-1"
        }
        """;

    for (int replay = 0; replay < 2; replay++) {
      mvc.perform(post("/api/warehouse/transfers")
              .header("Authorization", MANAGER_TOKEN)
              .contentType(APPLICATION_JSON)
              .content(request))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value("transfer-mobile-1"))
          .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    verify(topologyService, times(2)).requireTransferRequestAuthorization(MANAGER, source, target);
    verify(topologyRepository, times(2)).transferByCreateKey(1L, "mobile-transfer-create-1");
    verify(topologyRepository, never()).insertTransfer(
        any(Long.class), any(), any(), any(Long.class), any(Long.class), any(), any(), any(Long.class));
  }

  private MockMvc mockMvc(Object controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }
}
