package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionRecordControllerTest {
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final InspectionService inspectionService = mock(InspectionService.class);
  private final InspectionController controller = new InspectionController(accessControl, inspectionService);
  private final AuthUser supervisor = new AuthUser(1L, 1L, "default", "supervisor", "", "Supervisor", "SUPERVISOR", null, true);

  @Test
  void listAndDetailUseAuthenticatedUserAndWrapResponses() {
    InspectionRecordResponse row = response("insp-1");
    when(accessControl.requireUser("Bearer token")).thenReturn(supervisor);
    when(inspectionService.records(supervisor, "2026-05-01", "2026-05-31", 1L, "s1", false)).thenReturn(List.of(row));
    when(inspectionService.record(supervisor, "insp-1")).thenReturn(row);

    ApiResponse<List<InspectionRecordResponse>> list = controller.records(
        "Bearer token",
        "2026-05-01",
        "2026-05-31",
        1L,
        "s1",
        false
    );
    ApiResponse<InspectionRecordResponse> detail = controller.record("Bearer token", "insp-1");

    assertThat(list.success()).isTrue();
    assertThat(list.data()).containsExactly(row);
    assertThat(detail.data()).isSameAs(row);
    verify(accessControl, times(2)).requireUser("Bearer token");
    verify(accessControl, times(2)).requireInspectionRead(supervisor);
    verify(inspectionService).records(supervisor, "2026-05-01", "2026-05-31", 1L, "s1", false);
    verify(inspectionService).record(supervisor, "insp-1");
  }

  @Test
  void createUpdateAndDeleteUseAuthenticatedUser() {
    InspectionRecordRequest request = request();
    InspectionRecordResponse row = response("insp-1");
    when(accessControl.requireUser("Bearer token")).thenReturn(supervisor);
    when(inspectionService.save(supervisor, null, request)).thenReturn(row);
    when(inspectionService.save(supervisor, "insp-1", request)).thenReturn(row);

    ApiResponse<InspectionRecordResponse> created = controller.create("Bearer token", request);
    ApiResponse<InspectionRecordResponse> updated = controller.update("Bearer token", "insp-1", request);
    ApiResponse<Void> deleted = controller.delete("Bearer token", "insp-1");

    assertThat(created.data()).isSameAs(row);
    assertThat(updated.data()).isSameAs(row);
    assertThat(deleted.success()).isTrue();
    verify(inspectionService).save(supervisor, null, request);
    verify(inspectionService).save(supervisor, "insp-1", request);
    verify(inspectionService).delete(supervisor, "insp-1");
    verify(accessControl, times(3)).requireInspectionManage(supervisor);
  }

  @Test
  void serviceHealthRequiresAuthenticationAndWrapsResponse() {
    InspectionServiceHealthResponse response = new InspectionServiceHealthResponse(
        "UP",
        true,
        "http://127.0.0.1:8000/health",
        "http://127.0.0.1:8000/detect",
        "http://127.0.0.1:8000/export",
        "卫生识别服务可用",
        Map.of("ok", true)
    );
    when(accessControl.requireUser("Bearer token")).thenReturn(supervisor);
    when(inspectionService.serviceHealth()).thenReturn(response);

    ApiResponse<InspectionServiceHealthResponse> result = controller.serviceHealth("Bearer token");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(accessControl).requireUser("Bearer token");
    verify(accessControl).requireInspectionRead(supervisor);
    verify(inspectionService).serviceHealth();
  }

  @Test
  void bindDetectionResultsUsesAuthenticatedUserAndWrapsResponse() {
    InspectionDetectionBindingRequest request = detectionBindingRequest();
    InspectionRecordResponse row = response("insp-1");
    when(accessControl.requireUser("Bearer token")).thenReturn(supervisor);
    when(inspectionService.bindDetectionResults(supervisor, "insp-1", request)).thenReturn(row);

    ApiResponse<InspectionRecordResponse> result = controller.bindDetectionResults("Bearer token", "insp-1", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(row);
    verify(accessControl).requireUser("Bearer token");
    verify(accessControl).requireInspectionManage(supervisor);
    verify(inspectionService).bindDetectionResults(supervisor, "insp-1", request);
  }

  @Test
  void historyRepairUsesAuthenticatedInspectionManager() {
    InspectionHistoryRepairResponse response =
        new InspectionHistoryRepairResponse(2, 1, 1, 0, List.of("manual-1"));
    when(accessControl.requireUser("Bearer token")).thenReturn(supervisor);
    when(inspectionService.repairHistory(supervisor)).thenReturn(response);

    ApiResponse<InspectionHistoryRepairResponse> result =
        controller.repairHistory("Bearer token");

    assertThat(result.data()).isSameAs(response);
    verify(accessControl).requireUser("Bearer token");
    verify(accessControl).requireInspectionManage(supervisor);
    verify(inspectionService).repairHistory(supervisor);
  }

  private InspectionRecordRequest request() {
    return new InspectionRecordRequest(
        "s1",
        "2026-05-21",
        "Inspector A",
        "Tea",
        new BigDecimal("100.00"),
        new BigDecimal("92.00"),
        false,
        "[{\"item\":\"counter\",\"deduct\":8}]",
        "[{\"item\":\"food safety\"}]",
        "[]",
        "follow up"
    );
  }

  private InspectionDetectionBindingRequest detectionBindingRequest() {
    return new InspectionDetectionBindingRequest(
        "Inspector B",
        "Tea",
        new BigDecimal("100.00"),
        List.of(Map.of(
            "image_id", "img-1",
            "filename", "floor.jpg",
            "passed", false,
            "detection_count", 1,
            "detection_summary", "paper on floor"
        )),
        "AI detection result"
    );
  }

  private InspectionRecordResponse response(String id) {
    return new InspectionRecordResponse(
        id,
        "s1",
        "001",
        "One",
        1L,
        "Tea",
        "2026-05-21",
        "Inspector A",
        "Tea",
        new BigDecimal("100.00"),
        new BigDecimal("92.00"),
        false,
        "[{\"item\":\"counter\",\"deduct\":8}]",
        "[{\"item\":\"food safety\"}]",
        "[]",
        "follow up"
    );
  }
}
