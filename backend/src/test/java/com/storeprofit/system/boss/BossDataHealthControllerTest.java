package com.storeprofit.system.boss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.common.ApiResponse;
import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BossDataHealthControllerTest {
  @Test
  void dataHealthUsesAuthenticatedBossAndWrapsResponse() {
    AuthService authService = mock(AuthService.class);
    BossDataHealthService dataHealthService = mock(BossDataHealthService.class);
    BossDataHealthController controller = new BossDataHealthController(authService, dataHealthService);
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);
    BossDataHealthResponse response = new BossDataHealthResponse(
        "SERVER_RULES",
        "2026-07-08T00:00:00+08:00",
        List.of(new BossDataHealthModuleResponse(
            "今日待办",
            "MYSQL",
            "MySQL结构化",
            "2026-07-08T00:00:00+08:00",
            "分角色待办和老板首页统计",
            "老板数据健康入口已接后端接口",
            "继续补齐各角色待办真实处理接口"
        ))
    );

    when(authService.requireUser("Bearer token")).thenReturn(user);
    when(dataHealthService.dataHealth(user)).thenReturn(response);

    ApiResponse<BossDataHealthResponse> result = controller.dataHealth("Bearer token");

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isSameAs(response);
    verify(authService).requireUser("Bearer token");
    verify(dataHealthService).dataHealth(user);
  }

  @Test
  void serviceReturnsRequiredModuleStatusesForBoss() {
    BossDataHealthService service = new BossDataHealthService();
    AuthUser user = new AuthUser(1L, 1L, "default", "boss", "", "Boss", "BOSS", null, true);

    BossDataHealthResponse result = service.dataHealth(user);

    assertThat(result.dataSource()).isEqualTo("SERVER_RULES");
    assertThat(result.lastUpdatedAt()).isNotBlank();
    assertThat(result.modules())
        .extracting(BossDataHealthModuleResponse::moduleName)
        .containsExactly("今日待办", "利润", "报销", "督导巡店", "仓库", "工资");
    assertThat(moduleByName(result.modules(), "今日待办").status()).isEqualTo("MYSQL");
    assertThat(moduleByName(result.modules(), "利润").status()).isEqualTo("KV");
    assertThat(result.modules()).allSatisfy(module -> {
      assertThat(module.status()).isIn("MYSQL", "KV");
      assertThat(module.dataSource()).isNotBlank();
      assertThat(module.lastUpdatedAt()).isNotBlank();
      assertThat(module.businessScope()).isNotBlank();
      assertThat(module.migrationNote()).isNotBlank();
      assertThat(module.recommendation()).isNotBlank();
    });
  }

  @Test
  void serviceAllowsOperationsStaffToViewDataHealth() {
    BossDataHealthService service = new BossDataHealthService();
    AuthUser user = new AuthUser(3L, 1L, "default", "operations", "", "Operations", "OPERATIONS", null, true);

    BossDataHealthResponse result = service.dataHealth(user);

    assertThat(result.dataSource()).isEqualTo("SERVER_RULES");
    assertThat(result.modules()).isNotEmpty();
  }

  @Test
  void serviceRejectsNonOwnerRoles() {
    BossDataHealthService service = new BossDataHealthService();
    AuthUser user = new AuthUser(2L, 1L, "default", "finance", "", "Finance", "FINANCE", null, true);

    assertThatThrownBy(() -> service.dataHealth(user))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
          assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
  }

  private BossDataHealthModuleResponse moduleByName(
      List<BossDataHealthModuleResponse> modules,
      String moduleName
  ) {
    return modules.stream()
        .filter(module -> moduleName.equals(module.moduleName()))
        .findFirst()
        .orElseThrow();
  }
}
