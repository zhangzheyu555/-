package com.storeprofit.system.expense;

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
@RequestMapping("/api/expenses")
public class ExpenseController {
  private final AuthService authService;
  private final ExpenseService expenseService;

  public ExpenseController(AuthService authService, ExpenseService expenseService) {
    this.authService = authService;
    this.expenseService = expenseService;
  }

  @GetMapping
  public ApiResponse<List<ExpenseClaimResponse>> claims(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) Long brandId,
      @RequestParam(required = false) String storeId,
      @RequestParam(required = false) String status
  ) {
    return ApiResponse.ok(expenseService.claims(authService.requireUser(authorization), month, brandId, storeId, status));
  }

  @PostMapping
  public ApiResponse<ExpenseClaimResponse> create(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody ExpenseClaimRequest request
  ) {
    return ApiResponse.ok(expenseService.save(authService.requireUser(authorization), null, request));
  }

  @PutMapping("/{id}")
  public ApiResponse<ExpenseClaimResponse> update(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @Valid @RequestBody ExpenseClaimRequest request
  ) {
    return ApiResponse.ok(expenseService.save(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/submit")
  public ApiResponse<ExpenseClaimResponse> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    return ApiResponse.ok(expenseService.submit(authService.requireUser(authorization), id));
  }

  @PostMapping("/{id}/approve")
  public ApiResponse<ExpenseClaimResponse> approve(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.approve(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/request-info")
  public ApiResponse<ExpenseClaimResponse> requestInfo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.requestInfo(authService.requireUser(authorization), id, request));
  }

  @PostMapping("/{id}/reject")
  public ApiResponse<ExpenseClaimResponse> reject(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestBody(required = false) ExpenseReviewRequest request
  ) {
    return ApiResponse.ok(expenseService.reject(authService.requireUser(authorization), id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id
  ) {
    expenseService.delete(authService.requireUser(authorization), id);
    return ApiResponse.ok();
  }
}
