package com.storeprofit.system.finance;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {
  private final AuthService authService;
  private final FinanceService financeService;
  private final FinanceWorkbenchService financeWorkbenchService;

  public FinanceController(
      AuthService authService,
      FinanceService financeService,
      FinanceWorkbenchService financeWorkbenchService
  ) {
    this.authService = authService;
    this.financeService = financeService;
    this.financeWorkbenchService = financeWorkbenchService;
  }

  @GetMapping("/workbench")
  public ApiResponse<FinanceWorkbenchResponse> workbench(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(financeWorkbenchService.workbench(authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/months")
  public ApiResponse<List<String>> months(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    return ApiResponse.ok(financeService.months(authService.requireUser(authorization)));
  }

  @GetMapping("/dashboard")
  public ApiResponse<ProfitDashboardResponse> dashboard(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(financeService.dashboard(
        authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/entries")
  public ApiResponse<List<ProfitEntryResponse>> entries(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId
  ) {
    return ApiResponse.ok(financeService.entries(authService.requireUser(authorization), month, brandId, storeId));
  }

  @GetMapping("/entries/page")
  public ApiResponse<ProfitEntryPageResponse> entriesPaged(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.ok(financeService.entriesPaged(
        authService.requireUser(authorization), month, brandId, storeId, page, size));
  }

  @GetMapping("/entries/detail")
  public ApiResponse<ProfitEntryResponse> entry(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId,
      @RequestParam String month
  ) {
    return ApiResponse.ok(financeService.entry(authService.requireUser(authorization), storeId, month));
  }

  @PutMapping("/entries")
  public ApiResponse<Void> save(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody ProfitEntryRequest request
  ) {
    financeService.save(authService.requireUser(authorization), request);
    return ApiResponse.ok();
  }

  @DeleteMapping("/entries")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String storeId,
      @RequestParam String month
  ) {
    financeService.delete(authService.requireUser(authorization), storeId, month);
    return ApiResponse.ok();
  }

  @PostMapping("/todos/{todoId}/complete")
  public ApiResponse<Object> completeTodo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody(required = false) com.storeprofit.system.todo.RoleTodoCompletionRequest request
  ) {
    return ApiResponse.ok(financeWorkbenchService.complete(authService.requireUser(authorization), todoId, request));
  }

  @PostMapping("/todos/{todoId}/reject")
  public ApiResponse<FinanceTodoActionResponse> rejectTodo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody(required = false) FinanceTodoActionRequest request
  ) {
    return ApiResponse.ok(financeWorkbenchService.reject(authService.requireUser(authorization), todoId, request));
  }

  @PostMapping("/todos/{todoId}/request-info")
  public ApiResponse<FinanceTodoActionResponse> requestInfo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String todoId,
      @RequestBody(required = false) FinanceTodoActionRequest request
  ) {
    return ApiResponse.ok(financeWorkbenchService.requestInfo(authService.requireUser(authorization), todoId, request));
  }
}
