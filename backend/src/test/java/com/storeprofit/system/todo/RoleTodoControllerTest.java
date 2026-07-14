package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RoleTodoControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final RoleTodoService roleTodoService = mock(RoleTodoService.class);
  private final RoleTodoController controller = new RoleTodoController(authService, roleTodoService);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

  @Test
  void bossTodosUseAuthenticatedUserAndQueryFilters() {
    RoleTodoQuery query = new RoleTodoQuery(false, "RISK", 20, 1L, "s1");
    RoleTodoResponse response = response(RoleTodoAudience.BOSS);
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(roleTodoService.todos(boss, RoleTodoAudience.BOSS, query)).thenReturn(response);

    ApiResponse<RoleTodoResponse> result = controller.bossTodos("Bearer token", false, "risk", 20, 1L, "s1");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(roleTodoService).todos(boss, RoleTodoAudience.BOSS, query);
  }

  @Test
  void bossTodoDashboardUsesAuthenticatedUserAndQueryFilters() {
    RoleTodoQuery query = new RoleTodoQuery(true, "DONE", 80, 1L, "s1");
    BossTodoDashboardResponse response = new BossTodoDashboardResponse(
        "老板",
        "MySQL结构化数据 / 后端标准接口",
        "2026-07-08T10:00:00+08:00",
        new BossTodoFocusResponse(0, 0, 0, 0, 0, 0, "今天没有必须老板处理的事项。"),
        List.of(),
        List.of(),
        List.of(),
        List.of()
    );
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(roleTodoService.bossDashboard(boss, query)).thenReturn(response);

    ResponseEntity<ApiResponse<BossTodoDashboardResponse>> result =
        controller.bossTodoDashboard("Bearer token", true, "done", 80, 1L, "s1");

    assertThat(result.getHeaders().getFirst("Deprecation")).isEqualTo("true");
    assertThat(result.getHeaders().getFirst("Link")).isEqualTo("</api/todos>; rel=\"successor-version\"");
    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().success()).isTrue();
    assertThat(result.getBody().data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(roleTodoService).bossDashboard(boss, query);
  }

  @Test
  void allRoleEndpointsMapToTheirAudiences() {
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    for (RoleTodoAudience audience : RoleTodoAudience.values()) {
      when(roleTodoService.todos(boss, audience, RoleTodoQuery.defaults())).thenReturn(response(audience));
    }

    assertThat(controller.bossTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("老板（系统管理员）");
    assertThat(controller.financeTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("财务");
    assertThat(controller.supervisorTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("督导");
    assertThat(controller.storeManagerTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("店长");
    assertThat(controller.warehouseTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("仓库管理员");
    assertThat(controller.operationsTodos("Bearer token", null, null, null, null, null).data().roleName()).isEqualTo("运营");

    for (RoleTodoAudience audience : RoleTodoAudience.values()) {
      verify(roleTodoService).todos(boss, audience, RoleTodoQuery.defaults());
    }
  }

  @Test
  void financeEscalateUsesAuthenticatedUserAndWrapsResponse() {
    RoleTodoEscalationRequest request = new RoleTodoEscalationRequest("凭证缺失，门店无法补齐", "RISK");
    RoleTodoEscalationResponse response = new RoleTodoEscalationResponse("esc-1", "boss-escalation-esc-1");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(roleTodoService.escalate(boss, RoleTodoAudience.FINANCE, "todo-1", request)).thenReturn(response);

    ApiResponse<RoleTodoEscalationResponse> result = controller.financeEscalate("Bearer token", "todo-1", request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(roleTodoService).escalate(boss, RoleTodoAudience.FINANCE, "todo-1", request);
  }

  @Test
  void roleResolveAndBossCloseUseAuthenticatedUserAndWrapResponse() {
    RoleTodoCompletionRequest request = new RoleTodoCompletionRequest("已经处理", List.of());
    RoleTodoActionResultResponse resolved =
        new RoleTodoActionResultResponse("todo-1", "DONE", "act-1", 0, "已经处理");
    RoleTodoActionResultResponse closed =
        new RoleTodoActionResultResponse("boss-escalation-1", "DONE", "act-2", 0, "事情没有很大影响，已默认处理");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(roleTodoService.resolve(boss, RoleTodoAudience.FINANCE, "todo-1", request)).thenReturn(resolved);
    when(roleTodoService.close(boss, "boss-escalation-1", request)).thenReturn(closed);

    ApiResponse<RoleTodoActionResultResponse> resolveResult =
        controller.financeResolve("Bearer token", "todo-1", request);
    ApiResponse<RoleTodoActionResultResponse> closeResult =
        controller.bossClose("Bearer token", "boss-escalation-1", request);

    assertThat(resolveResult.data()).isSameAs(resolved);
    assertThat(closeResult.data()).isSameAs(closed);
    verify(roleTodoService).resolve(boss, RoleTodoAudience.FINANCE, "todo-1", request);
    verify(roleTodoService).close(boss, "boss-escalation-1", request);
  }

  @Test
  void storeManagerCanEscalateToBoss() {
    RoleTodoEscalationRequest request = new RoleTodoEscalationRequest("需要老板协调", "RISK");
    RoleTodoEscalationResponse response = new RoleTodoEscalationResponse("esc-1", "boss-escalation-esc-1");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(roleTodoService.escalate(boss, RoleTodoAudience.STORE_MANAGER, "inspection-1", request)).thenReturn(response);

    ApiResponse<RoleTodoEscalationResponse> result =
        controller.storeManagerEscalate("Bearer token", "inspection-1", request);

    assertThat(result.data()).isSameAs(response);
    verify(roleTodoService).escalate(boss, RoleTodoAudience.STORE_MANAGER, "inspection-1", request);
  }

  private RoleTodoResponse response(RoleTodoAudience audience) {
    return new RoleTodoResponse(
        audience.roleName(),
        "MySQL结构化数据 / 后端标准接口",
        "2026-07-08T10:00:00+08:00",
        List.of(
            new RoleTodoStatResponse("RISK", 0),
            new RoleTodoStatResponse("PENDING", 0),
            new RoleTodoStatResponse("REMINDER", 0),
            new RoleTodoStatResponse("DONE", 0)
        ),
        new RoleTodoAiSummaryResponse("RULE", "none", ""),
        List.of()
    );
  }
}
