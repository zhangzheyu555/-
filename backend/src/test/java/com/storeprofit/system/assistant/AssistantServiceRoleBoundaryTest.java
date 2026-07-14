package com.storeprofit.system.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeprofit.system.finance.FinanceService;
import com.storeprofit.system.platform.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantServiceRoleBoundaryTest {
  private final FinanceService financeService = mock(FinanceService.class);
  private final AssistantService assistantService = new AssistantService(new DeepSeekProperties(), financeService);

  @Test
  void storeManagerCannotAskForAllStoreRankingEvenWhenFrontendContextIsProvided() {
    AssistantChatResponse response = assistantService.chat(
        storeManager(),
        new AssistantChatRequest("全部门店利润排名", List.of(), "其他门店利润数据", null, null, null)
    );

    assertThat(response.error().code()).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(response.localData().source()).isEqualTo("系统安全规则");
    assertThat(response.localData().summary()).contains("本店经营助手只能查看和回答你绑定门店的数据");
    verifyNoInteractions(financeService);
  }

  @Test
  void warehouseCannotAskProfitMetrics() {
    AssistantChatResponse response = assistantService.chat(
        warehouse(),
        new AssistantChatRequest("本月净利润最低的门店是哪家", List.of(), "", null, null, null)
    );

    assertThat(response.error().code()).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(response.localData().summary()).contains("仓库数据助手只回答库存、叫货、采购入库");
    verifyNoInteractions(financeService);
  }

  @Test
  void warehouseFallbackUsesWarehouseScopeInsteadOfFinanceOverview() {
    AssistantChatResponse response = assistantService.chat(
        warehouse(),
        new AssistantChatRequest("哪些商品库存不足", List.of(), "当前角色：仓库管理员；低库存提醒：2", null, null, null)
    );

    assertThat(response.error()).isNull();
    assertThat(response.aiAnalysis().available()).isFalse();
    assertThat(response.localData().source()).isEqualTo("后端权限规则");
    assertThat(response.localData().summary()).contains("仓库数据助手需要仓库页面上下文");
    verifyNoInteractions(financeService);
  }

  private AuthUser storeManager() {
    return new AuthUser(1L, 1L, "默认企业", "rg1", "hash", "荆州之星店店长", "STORE_MANAGER", "rg1", true);
  }

  private AuthUser warehouse() {
    return new AuthUser(2L, 1L, "默认企业", "warehouse", "hash", "仓库管理员", "WAREHOUSE", null, true);
  }
}
