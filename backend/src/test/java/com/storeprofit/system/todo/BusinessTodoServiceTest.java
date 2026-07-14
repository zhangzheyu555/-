package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeprofit.system.finance.FinanceRepository;
import com.storeprofit.system.finance.ProfitEntryResponse;
import com.storeprofit.system.platform.auth.AccessControlService;
import com.storeprofit.system.platform.auth.AuthUser;
import com.storeprofit.system.platform.authorization.DataScopeDomains;
import com.storeprofit.system.todo.BusinessTodoRepository.BusinessTodoRow;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoActionRecord;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoAttachmentContent;
import com.storeprofit.system.todo.RoleTodoActionRepository.RoleTodoOperationLogRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusinessTodoServiceTest {
  private final FinanceRepository financeRepository = mock(FinanceRepository.class);
  private final BusinessTodoRepository businessTodoRepository = mock(BusinessTodoRepository.class);
  private final RoleTodoActionRepository actionRepository = mock(RoleTodoActionRepository.class);
  private final AccessControlService accessControl = mock(AccessControlService.class);
  private final BusinessTodoService service = new BusinessTodoService(
      financeRepository,
      businessTodoRepository,
      actionRepository,
      accessControl,
      new BigDecimal("0.45"),
      new BigDecimal("0.20")
  );
  private final AuthUser finance = new AuthUser(2L, 1L, "default", "finance", "", "财务", "FINANCE", null, true);
  private final AuthUser manager = new AuthUser(3L, 1L, "default", "s1", "", "店长", "STORE_MANAGER", "s1", true);

  @BeforeEach
  void dataScopes() {
    when(accessControl.allowedStoreIds(manager, DataScopeDomains.STORE)).thenReturn(Set.of("s1"));
    when(accessControl.hasAllDataScope(finance, DataScopeDomains.STORE)).thenReturn(true);
  }

  @Test
  void financeSaveReconciliationCreatesLossAndCostRatioTodos() {
    ProfitEntryResponse lossEntry = entry("s1", "2026-07", new BigDecimal("1000"), new BigDecimal("600"), new BigDecimal("-80"));
    when(financeRepository.entries(1L, "2026-07", null, "s1")).thenReturn(List.of(lossEntry));
    when(businessTodoRepository.openOrRefresh(eq(1L), any())).thenAnswer(invocation -> rowFromDraft(invocation.getArgument(1)));

    service.reconcileStore(manager, "s1", "2026-07");

    ArgumentCaptor<BusinessTodoDraft> drafts = ArgumentCaptor.forClass(BusinessTodoDraft.class);
    verify(businessTodoRepository, org.mockito.Mockito.atLeast(2)).openOrRefresh(eq(1L), drafts.capture());
    assertThat(drafts.getAllValues()).extracting(BusinessTodoDraft::ruleCode)
        .contains("STORE_LOSS", "COST_RATIO_HIGH");
  }

  @Test
  void storeManagerOnlySeesWorkflowTodoInsideAssignedStore() {
    assertThat(service.list(manager, null)).isEmpty();

    verify(businessTodoRepository).listVisible(
        1L, null, 300, "STORE_MANAGER", false, false, List.of("s1"));
  }

  @Test
  void assignedManagerCanMoveTodoIntoProcessingAndHistoryIsWritten() {
    BusinessTodoRow pending = row("s1", "STORE_MANAGER", "FINANCE", "PENDING");
    when(businessTodoRepository.findVisibleById(
        1L, "todo-1", "STORE_MANAGER", false, false, List.of("s1")))
        .thenReturn(Optional.of(pending));
    when(businessTodoRepository.updateStatus(1L, "todo-1", BusinessTodoStatus.PENDING,
        BusinessTodoStatus.IN_PROGRESS, 3L, "店长")).thenReturn(true);

    service.transition(manager, "todo-1", new BusinessTodoTransitionRequest("IN_PROGRESS", "开始核对数据", List.of()));

    ArgumentCaptor<RoleTodoActionRecord> action = ArgumentCaptor.forClass(RoleTodoActionRecord.class);
    verify(actionRepository).saveAction(action.capture());
    assertThat(action.getValue().status()).isEqualTo("IN_PROGRESS");
    assertThat(action.getValue().note()).isEqualTo("开始核对数据");
  }

  @Test
  void confirmedAssistantActionCreatesAuditedStoreScopedTodo() {
    when(businessTodoRepository.openOrRefresh(eq(1L), any()))
        .thenAnswer(invocation -> rowFromDraft(invocation.getArgument(1)));

    BusinessTodoResponse created = service.createManual(manager, new ManualBusinessTodoRequest(
        "本周降低食材损耗",
        "复盘高损耗品并调整备货量",
        "s1",
        "2026-07",
        "STORE_MANAGER",
        "本周五",
        "ASSISTANT",
        "req-safe-1",
        "损耗率下降",
        "损耗率较上周下降 1 个百分点",
        true
    ));

    assertThat(created.ruleCode()).isEqualTo("ASSISTANT_ACTION");
    assertThat(created.summary()).contains("建议期限：本周五", "预期改善：损耗率下降", "验证指标：");
    verify(accessControl).requireStoreAccess(manager, "s1", "从经营助手创建待办");
    ArgumentCaptor<RoleTodoActionRecord> action = ArgumentCaptor.forClass(RoleTodoActionRecord.class);
    verify(actionRepository).saveAction(action.capture());
    assertThat(action.getValue().actionType()).isEqualTo("ASSISTANT_MANUAL_CREATE");
  }

  @Test
  void attachmentDownloadIsWrittenToOperationLog() {
    BusinessTodoRow pending = row("s1", "STORE_MANAGER", "FINANCE", "PENDING");
    when(businessTodoRepository.findVisibleById(
        1L, "todo-1", "FINANCE", false, true, List.of()))
        .thenReturn(Optional.of(pending));
    when(actionRepository.attachment(1L, "todo-1", "file-1"))
        .thenReturn(Optional.of(new RoleTodoAttachmentContent(
            "file-1", "整改照片.png", "image/png", 3L, new byte[]{1, 2, 3})));

    BusinessTodoService.DownloadedTodoAttachment downloaded = service.attachment(finance, "todo-1", "file-1");

    assertThat(downloaded.fileName()).isEqualTo("整改照片.png");
    ArgumentCaptor<RoleTodoOperationLogRecord> log = ArgumentCaptor.forClass(RoleTodoOperationLogRecord.class);
    verify(actionRepository).saveOperationLog(log.capture());
    assertThat(log.getValue().action()).isEqualTo("下载待办附件");
    assertThat(log.getValue().targetType()).isEqualTo("business_todo_attachment");
    assertThat(log.getValue().targetId()).isEqualTo("file-1");
  }

  private ProfitEntryResponse entry(String storeId, String month, BigDecimal income, BigDecimal cost, BigDecimal net) {
    return new ProfitEntryResponse(
        1L, storeId, storeId, "门店一", 1L, "品牌一", "", "", month,
        income, BigDecimal.ZERO, BigDecimal.ZERO, income,
        cost, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cost,
        BigDecimal.ZERO,
        income.subtract(cost), BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        net, BigDecimal.ZERO, "亏损", null
    );
  }

  private BusinessTodoRow row(String storeId, String assigneeRole, String reviewRole, String status) {
    return new BusinessTodoRow(
        "todo-1", "STORE_LOSS", "profit_entry", storeId + "|2026-07", "profit-loss:" + storeId,
        1, "门店亏损", "请处理", assigneeRole, reviewRole,
        storeId, "门店一", "品牌一", "2026-07", 3, status, true, null,
        null, null, "2026-07-10T09:00", "2026-07-10T09:00", null
    );
  }

  private BusinessTodoRow rowFromDraft(BusinessTodoDraft draft) {
    return new BusinessTodoRow(
        "todo-" + draft.ruleCode(), draft.ruleCode(), draft.sourceModule(), draft.sourceRecordId(), draft.sourceKey(),
        1, draft.title(), draft.summary(), draft.assigneeRole(), draft.reviewRole(),
        draft.storeId(), "门店一", "品牌一", draft.month(), draft.priority(), "PENDING", true, draft.metadataJson(),
        null, null, "2026-07-10T09:00", "2026-07-10T09:00", null
    );
  }
}
