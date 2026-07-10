package com.storeprofit.system.todo;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoleTodoController {
  private final AuthService authService;
  private final RoleTodoService roleTodoService;

  public RoleTodoController(AuthService authService, RoleTodoService roleTodoService) {
    this.authService = authService;
    this.roleTodoService = roleTodoService;
  }

  @GetMapping("/api/boss/todos")
  public ApiResponse<RoleTodoResponse> bossTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.BOSS, includeDone, status, limit, brandId, storeId);
  }

  @GetMapping("/api/boss/todo-dashboard")
  public ApiResponse<BossTodoDashboardResponse> bossTodoDashboard(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    RoleTodoQuery query = new RoleTodoQuery(Boolean.TRUE.equals(includeDone), status, limit == null ? 50 : limit, brandId, storeId);
    return ApiResponse.ok(roleTodoService.bossDashboard(authService.requireUser(authorization), query));
  }

  @GetMapping("/api/finance/todos")
  public ApiResponse<RoleTodoResponse> financeTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.FINANCE, includeDone, status, limit, brandId, storeId);
  }

  @GetMapping("/api/supervisor/todos")
  public ApiResponse<RoleTodoResponse> supervisorTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.SUPERVISOR, includeDone, status, limit, brandId, storeId);
  }

  @GetMapping("/api/store-manager/todos")
  public ApiResponse<RoleTodoResponse> storeManagerTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.STORE_MANAGER, includeDone, status, limit, brandId, storeId);
  }

  @GetMapping("/api/warehouse/todos")
  public ApiResponse<RoleTodoResponse> warehouseTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.WAREHOUSE, includeDone, status, limit, brandId, storeId);
  }

  @GetMapping("/api/operations/todos")
  public ApiResponse<RoleTodoResponse> operationsTodos(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) Boolean includeDone,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return todos(authorization, RoleTodoAudience.OPERATIONS, includeDone, status, limit, brandId, storeId);
  }

  @PostMapping("/api/finance/todos/{todoId}/escalate")
  public ApiResponse<RoleTodoEscalationResponse> financeEscalate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoEscalationRequest request
  ) {
    return escalate(authorization, RoleTodoAudience.FINANCE, todoId, request);
  }

  @PostMapping("/api/supervisor/todos/{todoId}/escalate")
  public ApiResponse<RoleTodoEscalationResponse> supervisorEscalate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoEscalationRequest request
  ) {
    return escalate(authorization, RoleTodoAudience.SUPERVISOR, todoId, request);
  }

  @PostMapping("/api/warehouse/todos/{todoId}/escalate")
  public ApiResponse<RoleTodoEscalationResponse> warehouseEscalate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoEscalationRequest request
  ) {
    return escalate(authorization, RoleTodoAudience.WAREHOUSE, todoId, request);
  }

  @PostMapping("/api/store-manager/todos/{todoId}/escalate")
  public ApiResponse<RoleTodoEscalationResponse> storeManagerEscalate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoEscalationRequest request
  ) {
    return escalate(authorization, RoleTodoAudience.STORE_MANAGER, todoId, request);
  }

  @PostMapping("/api/operations/todos/{todoId}/escalate")
  public ApiResponse<RoleTodoEscalationResponse> operationsEscalate(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoEscalationRequest request
  ) {
    return escalate(authorization, RoleTodoAudience.OPERATIONS, todoId, request);
  }

  @PostMapping("/api/finance/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> financeResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return resolve(authorization, RoleTodoAudience.FINANCE, todoId, request);
  }

  @PostMapping("/api/supervisor/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> supervisorResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return resolve(authorization, RoleTodoAudience.SUPERVISOR, todoId, request);
  }

  @PostMapping("/api/store-manager/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> storeManagerResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return resolve(authorization, RoleTodoAudience.STORE_MANAGER, todoId, request);
  }

  @PostMapping("/api/warehouse/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> warehouseResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return resolve(authorization, RoleTodoAudience.WAREHOUSE, todoId, request);
  }

  @PostMapping("/api/operations/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> operationsResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return resolve(authorization, RoleTodoAudience.OPERATIONS, todoId, request);
  }

  @PostMapping("/api/boss/todos/{todoId}/resolve")
  public ApiResponse<RoleTodoActionResultResponse> bossResolve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return ApiResponse.ok(roleTodoService.resolveBoss(authService.requireUser(authorization), todoId, request));
  }

  @PostMapping("/api/boss/todos/{todoId}/close")
  public ApiResponse<RoleTodoActionResultResponse> bossClose(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody RoleTodoCompletionRequest request
  ) {
    return ApiResponse.ok(roleTodoService.close(authService.requireUser(authorization), todoId, request));
  }

  private ApiResponse<RoleTodoResponse> todos(
      String authorization,
      RoleTodoAudience audience,
      Boolean includeDone,
      String status,
      Integer limit,
      Long brandId,
      String storeId
  ) {
    RoleTodoQuery query = new RoleTodoQuery(Boolean.TRUE.equals(includeDone), status, limit == null ? 50 : limit, brandId, storeId);
    return ApiResponse.ok(roleTodoService.todos(authService.requireUser(authorization), audience, query));
  }

  private ApiResponse<RoleTodoEscalationResponse> escalate(
      String authorization,
      RoleTodoAudience audience,
      String todoId,
      RoleTodoEscalationRequest request
  ) {
    return ApiResponse.ok(roleTodoService.escalate(authService.requireUser(authorization), audience, todoId, request));
  }

  private ApiResponse<RoleTodoActionResultResponse> resolve(
      String authorization,
      RoleTodoAudience audience,
      String todoId,
      RoleTodoCompletionRequest request
  ) {
    return ApiResponse.ok(roleTodoService.resolve(authService.requireUser(authorization), audience, todoId, request));
  }
}
