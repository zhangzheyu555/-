package com.storeprofit.system.staticresources;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RoleTodoWorkbenchStaticTest {
  @Test
  void frontendDefinesRoleTodoWorkbenchShellAndRoleEndpoints() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("data-v=\"todo\"")
        .contains("id=\"v-todo\"")
        .contains("function activateRoleDefaultTab")
        .contains("function resetRoleScopedBackendTokens")
        .contains("function renderTodoWorkbench")
        .contains("function openTodoAction")
        .contains("function todoResetFilter")
        .contains("TODO_LOAD_SEQ")
        .contains("function todoSetLoading")
        .contains("/api/boss/todos")
        .contains("/api/finance/todos")
        .contains("/api/supervisor/todos")
        .contains("/api/store-manager/todos")
        .contains("/api/warehouse/todos")
        .contains("includeDone=false");

    assertThat(databaseJs)
        .contains("\"todo\"")
        .contains("\"financeWorkbench\"")
        .contains("\"老板\":\"todo\"")
        .contains("\"财务\":\"financeWorkbench\"")
        .contains("\"督导\":\"todo\"")
        .contains("\"店长\":\"todo\"")
        .contains("\"仓库管理员\":\"todo\"");
  }

  @Test
  void frontendLocksStoreDetailScopeForStoreManagersAndSingleStoreRoles() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("function detailScopeControlHtml")
        .contains("CURRENT_ROLE===\"店长\"||brands.length<=1")
        .contains("CURRENT_ROLE===\"店长\"||stores.length<=1")
        .contains("<span class=\"detail-fixed brand\"><span>品牌</span>")
        .contains("<span class=\"detail-fixed store\"><span>门店</span>")
        .contains("const visible=visibleStores();")
        .contains("!visible.some(x=>x.id===DETAIL_SID)")
        .contains("detail-context");
  }

  @Test
  void storeManagerTodoWorkbenchOnlyShowsRemindersInsteadOfEmbeddingWarehouseOperations() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    int start = indexHtml.indexOf("function renderStoreManagerWorkbenchData(data)");
    int end = indexHtml.indexOf("function renderStoreManagerFocus(payload)");
    assertThat(start).isGreaterThan(0);
    assertThat(end).isGreaterThan(start);

    String storeManagerTodoRenderer = indexHtml.substring(start, end);
    assertThat(storeManagerTodoRenderer)
        .contains("renderStoreManagerNeedSection(payload.needMyAction||[])")
        .contains("renderStoreManagerBusinessSection(payload.businessReminder||{})")
        .doesNotContain("renderStoreManagerWarehouseSection")
        .doesNotContain("renderStoreManagerRectificationSection")
        .doesNotContain("renderStoreManagerRecordsSection")
        .doesNotContain("storeManagerSubmitOrder")
        .doesNotContain("storeManagerPickOrderItem");

    assertThat(indexHtml)
        .doesNotContain("function renderStoreManagerWarehouseSection")
        .doesNotContain("function storeManagerSubmitOrder")
        .doesNotContain("function storeManagerPickOrderItem");
  }

  @Test
  void frontendShowsProductTrustAndTodoLifecycleFields() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("<title>AI Profit OS · 多门店经营异常处理系统</title>")
        .contains("AI <b>Profit</b> OS")
        .contains("AI <b>Profit</b>")
        .contains("老板只看需要决策的事")
        .contains("多门店经营异常处理系统")
        .contains("id=\"dataTrustBar\"")
        .contains("function renderDataTrustBar")
        .contains("function currentRoleScopeText")
        .contains("function todoField")
        .contains("function todoFormatTime")
        .contains("function renderTodoEvidence")
        .contains("负责人")
        .contains("截止")
        .contains("来源")
        .contains("处理状态")
        .contains("已上报老板")
        .contains("未上报");
  }

  @Test
  void frontendDefinesExplicitTodoLifecycleContractAndFallbacks() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("TODO_LIFECYCLE_FIELDS")
        .contains("TODO_LIFECYCLE_FALLBACKS")
        .contains("function todoNormalizeItem")
        .contains("function todoNormalizePayload")
        .contains("ownerName")
        .contains("dueAt")
        .contains("sourceModule")
        .contains("sourceRecordId")
        .contains("processStatus")
        .contains("escalatedToBoss")
        .contains("dataSource")
        .contains("updatedAt")
        .contains("ownerUser")
        .contains("ownerRole")
        .contains("workflowStatus")
        .contains("escalated")
        .contains("escalationStatus");
  }

  @Test
  void frontendDefinesBossOnlyDataHealthEntryAndMysqlOwnershipRules() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);
    String databaseJs = new ClassPathResource("static/database.js")
        .getContentAsString(StandardCharsets.UTF_8);
    String ownershipDoc = Files.readString(
        Path.of("..", "docs", "superpowers", "specs", "2026-07-08-mysql-data-ownership.md"),
        StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("data-v=\"dataHealth\"")
        .contains("id=\"v-dataHealth\"")
        .contains("数据健康")
        .contains("DATA_HEALTH_MODULES")
        .contains("function renderDataHealth")
        .contains("function dataHealthStatusMeta")
        .contains("MySQL结构化")
        .contains("兼容KV")
        .contains("浏览器旧数据")
        .contains("未接入")
        .contains("真实数据不存浏览器")
        .contains("虚拟数据不得入库");

    assertThat(databaseJs)
        .contains("\"dataHealth\"")
        .contains("\"老板\":[\"dataHealth\",\"financeWorkbench\"")
        .contains("\"财务\":[\"dataHealth\"")
        .contains("\"督导\":[\"financeWorkbench\",\"dataHealth\"")
        .contains("\"店长\":[\"financeWorkbench\",\"dataHealth\"")
        .contains("\"仓库管理员\":[\"financeWorkbench\",\"dataHealth\"")
        .contains("\"运营\":[\"financeWorkbench\",\"dataHealth\"");

    assertThat(ownershipDoc)
        .contains("真实业务数据必须写入 MySQL")
        .contains("虚拟数据不得入库")
        .contains("利润")
        .contains("报销")
        .contains("巡店")
        .contains("仓库")
        .contains("工资")
        .contains("今日待办");
  }

  @Test
  void frontendDefinesBossDataHealthApiContractAndFallback() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);
    String apiDoc = Files.readString(
        Path.of("..", "docs", "superpowers", "specs", "2026-07-08-boss-data-health-api-contract.md"),
        StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("DATA_HEALTH_ENDPOINT")
        .contains("/api/boss/data-health")
        .contains("DATA_HEALTH_LOAD_SEQ")
        .contains("async function loadDataHealth")
        .contains("function normalizeDataHealthPayload")
        .contains("function normalizeDataHealthModule")
        .contains("function renderStaticDataHealth")
        .contains("function renderDataHealthPayload")
        .contains("moduleName")
        .contains("lastUpdatedAt")
        .contains("migrationNote")
        .contains("recommendation")
        .contains("前端静态兜底");

    assertThat(apiDoc)
        .contains("GET /api/boss/data-health")
        .contains("moduleName")
        .contains("status")
        .contains("dataSource")
        .contains("lastUpdatedAt")
        .contains("migrationNote")
        .contains("recommendation")
        .contains("接口不可用时")
        .contains("前端静态兜底");
  }
  @Test
  void frontendDefinesRoleEscalationActionsWithRequiredReason() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("TODO_ESCALATION_ENDPOINTS")
        .contains("/api/finance/todos/")
        .contains("/api/supervisor/todos/")
        .contains("/api/warehouse/todos/")
        .contains("function todoCanEscalateRole")
        .contains("function todoEscalationSeverity")
        .contains("async function todoEscalateToBoss")
        .contains("reason")
        .contains("severity")
        .contains("encodeURIComponent(todoId)")
        .contains("renderEscalateButton")
        .contains("expEscalateButton")
        .contains("inspEscalateButton")
        .contains("whEscalateButton");
  }

  @Test
  void frontendDefinesTodoCompletionDialogWithMysqlAttachmentsAndChineseSummary() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("TODO_ACTION_ENDPOINTS")
        .contains("/api/finance/todos/")
        .contains("/api/store-manager/todos/")
        .contains("/resolve")
        .contains("/close")
        .contains("function todoOpenCompletionDialog")
        .contains("function todoSubmitCompletion")
        .contains("function todoReadAttachments")
        .contains("todoAttachmentInput")
        .contains("处理说明")
        .contains("上传图片或附件")
        .contains("处理完成")
        .contains("无影响关闭")
        .contains("事情没有很大影响，已默认处理")
        .contains("规则汇总")
        .doesNotContain("DeepSeek summary not connected")
        .doesNotContain("RULE ·");
  }

  @Test
  void bossTodoViewHidesTechnicalIdsAndTestText() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("function todoCleanBusinessText")
        .contains("function todoFriendlySource")
        .contains("function todoDisplaySummary")
        .contains("function todoDisplayDataSource")
        .contains("function renderBossTodoHero")
        .contains("function renderBossTodoDashboard")
        .contains("function renderBossNeedMeSection")
        .contains("function renderBossActionCard")
        .contains("function renderBossSummaryCard")
        .contains("岗位已上报，请老板查看处理。")
        .contains("系统实时数据")
        .contains("老板处理口径")
        .contains("需要我处理")
        .contains("高风险提醒")
        .contains("各岗位处理中")
        .contains("涉及门店")
        .contains("谁上报")
        .contains("为什么要处理")
        .contains("老板只看需要决策的事")
        .contains("岗位执行事项已收起为摘要")
        .contains("默认不展开明细")
        .contains(".boss-summary-card")
        .contains("CURRENT_ROLE===\"老板\"?todoFriendlySource(item):");
  }

  @Test
  void bossTodoViewUsesDecisionDashboardEndpointAndBusinessSections() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("TODO_DASHBOARD_ENDPOINT")
        .contains("/api/boss/todo-dashboard")
        .contains("function bossTodoDashboardRequestUrl")
        .contains("function todoNormalizeBossDashboardPayload")
        .contains("function renderBossDecisionDashboard")
        .contains("needsBossAction")
        .contains("highRiskReminders")
        .contains("roleProgress")
        .contains("doneReview")
        .contains("今日重点")
        .contains("需要我处理")
        .contains("高风险提醒")
        .contains("各岗位处理中")
        .contains("已处理复盘")
        .contains("只展示岗位上报老板的事项")
        .contains("按来源、责任岗位、门店和月份聚合")
        .contains("事情没有很大影响，已默认处理")
        .doesNotContain("boss-escalation-*")
        .doesNotContain("todo_escalation");
  }

  @Test
  void bossTodoViewShowsDoneDetailsAndNeutralEmptySummaries() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("TODO_FILTER.status===\"DONE\"")
        .contains("function renderBossDoneSection")
        .contains("已处理事项")
        .contains("已处理和已关闭的事项只用于复盘，不影响老板今天判断。")
        .contains("function renderBossSummaryEmpty")
        .contains("if(!count)return renderBossSummaryEmpty(title,hint);")
        .contains("当前没有事项，不影响老板今天判断。")
        .doesNotContain("boss-summary-card ${kind||\"\"}");
  }

  @Test
  void bossTodoStatsFollowCurrentStatusFilter() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("function todoStatsFromItems(items)")
        .contains("function todoVisibleStats(stats,items)")
        .contains("if(CURRENT_ROLE===\"老板\"&&TODO_FILTER.status)return todoStatsFromItems(items);")
        .contains("function renderTodoStats(stats,items=[])")
        .contains("const visibleStats=todoVisibleStats(stats,items);")
        .contains("renderTodoStats(stats,items)");
  }

  @Test
  void frontendLoadsOperationLogsFromBackendAuditApi() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("AUDIT_LOG_ENDPOINT")
        .contains("/api/audit/logs")
        .contains("async function loadOperationLogs")
        .contains("function renderOperationLogs")
        .contains("await storageBackendToken()")
        .contains("operatorName")
        .contains("targetType")
        .contains("targetId")
        .contains("MySQL审计日志")
        .doesNotContain("本地记录，正式多人留痕需服务端");
  }

  @Test
  void storeManagerInspectionPageUsesBackendScopedRecordsInsteadOfBrandTabs() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("CURRENT_ROLE===\"店长\"")
        .contains("本店巡检记录")
        .contains("/api/store-manager/inspections")
        .contains("系统正在从 MySQL 查询当前门店数据。")
        .contains("数据来自 MySQL，只显示当前登录店长绑定门店。")
        .contains("巡检记录加载失败，请刷新重试")
        .contains("STORE_MANAGER_INSPECTION_DATA")
        .contains("modeBar.style.display=\"none\"");
  }

  @Test
  void frontendLocksRoleToLoginAndKeepsReadableDesktopDensity() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .doesNotContain("id=\"roleSel\"")
        .doesNotContain("document.getElementById(\"roleSel\")")
        .doesNotContain("CURRENT_ROLE=e.target.value")
        .contains("id=\"currentRoleBadge\"")
        .contains("class=\"role-display\"")
        .contains("function renderCurrentRoleBadge")
        .contains("roleDefaultTab(CURRENT_ROLE)")
        .contains("#app{display:none;min-height:100dvh}")
        .contains(".sidebar{width:236px")
        .contains("height:100dvh")
        .contains("overflow:hidden")
        .contains(".tabs{display:flex;flex-direction:column;gap:4px;flex:1;min-height:0;overflow-y:auto;overflow-x:hidden")
        .contains(".logout{display:flex;align-items:center;gap:10px;border:0;background:none;border-radius:12px;padding:11px 13px;font-size:14px;font-weight:700;color:var(--muted);margin-top:8px;flex-shrink:0}")
        .contains(".tab{display:flex;align-items:center;gap:11px;border:0;background:none;padding:12px 13px;border-radius:13px;font-size:15px")
        .contains(".todo-title{font-size:15px")
        .contains(".todo-summary{font-size:13.5px")
        .contains(".todo-field{border:1px solid var(--line);border-radius:9px;background:#fff;padding:8px 10px;font-size:12.5px")
        .doesNotContain("--desktop-scale:.8")
        .doesNotContain("zoom:var(--desktop-scale)")
        .doesNotContain("transform:scale(var(--desktop-scale))");
  }

  @Test
  void assistantPageUsesRoleSpecificWorkspacesInsteadOfSingleStoreDefault() throws IOException {
    String indexHtml = new ClassPathResource("static/index.html")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(indexHtml)
        .contains("id=\"botTitle\"")
        .contains("id=\"botStoreEyebrow\"")
        .contains("<span class=\"lab\">数据助手</span>")
        .contains("function botRoleProfile")
        .contains("老板数据助手")
        .contains("本店经营助手")
        .contains("财务数据助手")
        .contains("仓库数据助手")
        .contains("巡店数据助手")
        .contains("运营数据助手")
        .contains("BOT_SCOPE_TYPE=\"ALL_STORES\"")
        .contains("BOT_ALL_SCOPE_VALUE")
        .contains("默认范围：${profile.allLabel")
        .contains("当前店长门店")
        .contains("不能回答其他门店或全公司数据")
        .contains("只回答库存、叫货、批次、入库出库和退货处理")
        .contains("只回答巡店记录、整改、红线问题和负责门店范围")
        .contains("function botRoleLocalAnswer")
        .doesNotContain("门店经营助手")
        .doesNotContain("当前门店经营助手数据");
  }
}
