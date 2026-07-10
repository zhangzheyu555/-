package com.storeprofit.system.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final ExpenseService expenseService = mock(ExpenseService.class);
  private final ExpenseController controller = new ExpenseController(authService, expenseService);
  private final AuthUser boss = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

  @Test
  void listUsesAuthenticatedUserAndWrapsResponse() {
    ExpenseClaimResponse row = response("exp-1", "草稿");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.claims(boss, "2026-05", 1L, "s1", "草稿")).thenReturn(List.of(row));

    ApiResponse<List<ExpenseClaimResponse>> result = controller.claims("Bearer token", "2026-05", 1L, "s1", "草稿");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).containsExactly(row);
    verify(authService).requireUser("Bearer token");
    verify(expenseService).claims(boss, "2026-05", 1L, "s1", "草稿");
  }

  @Test
  void createUpdateAndDeleteUseAuthenticatedUser() {
    ExpenseClaimRequest request = request();
    ExpenseClaimResponse row = response("exp-1", "草稿");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.save(boss, null, request)).thenReturn(row);
    when(expenseService.save(boss, "exp-1", request)).thenReturn(row);

    ApiResponse<ExpenseClaimResponse> created = controller.create("Bearer token", request);
    ApiResponse<ExpenseClaimResponse> updated = controller.update("Bearer token", "exp-1", request);
    ApiResponse<Void> deleted = controller.delete("Bearer token", "exp-1");

    assertThat(created.data()).isSameAs(row);
    assertThat(updated.data()).isSameAs(row);
    assertThat(deleted.success()).isTrue();
    verify(expenseService).save(boss, null, request);
    verify(expenseService).save(boss, "exp-1", request);
    verify(expenseService).delete(boss, "exp-1");
  }

  @Test
  void stateActionsUsePathIdAndReviewNote() {
    ExpenseReviewRequest review = new ExpenseReviewRequest("OK");
    ExpenseClaimResponse pending = response("exp-1", "待审核");
    ExpenseClaimResponse approved = response("exp-1", "已完成");
    ExpenseClaimResponse rejected = response("exp-1", "已驳回");
    when(authService.requireUser("Bearer token")).thenReturn(boss);
    when(expenseService.submit(boss, "exp-1")).thenReturn(pending);
    when(expenseService.approve(boss, "exp-1", review)).thenReturn(approved);
    when(expenseService.reject(boss, "exp-1", review)).thenReturn(rejected);

    assertThat(controller.submit("Bearer token", "exp-1").data()).isSameAs(pending);
    assertThat(controller.approve("Bearer token", "exp-1", review).data()).isSameAs(approved);
    assertThat(controller.reject("Bearer token", "exp-1", review).data()).isSameAs(rejected);

    verify(expenseService).submit(boss, "exp-1");
    verify(expenseService).approve(boss, "exp-1", review);
    verify(expenseService).reject(boss, "exp-1", review);
  }

  private ExpenseClaimRequest request() {
    return new ExpenseClaimRequest(
        "s1",
        "2026-05",
        new BigDecimal("128.50"),
        "物料采购",
        "牛奶采购",
        "https://example.test/invoice.jpg"
    );
  }

  private ExpenseClaimResponse response(String id, String status) {
    return new ExpenseClaimResponse(
        id,
        "s1",
        "001",
        "One",
        1L,
        "Tea",
        "2026-05",
        new BigDecimal("128.50"),
        "物料采购",
        "牛奶采购",
        status,
        "https://example.test/invoice.jpg",
        3L,
        1L,
        LocalDateTime.of(2026, 5, 1, 12, 0)
    );
  }
}
