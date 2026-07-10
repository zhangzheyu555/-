# 既有测试债务：Static Resource 测试

> **状态：已记录，本轮不修复**  
> 创建日期：2026-07-10  
> 测试运行：Tests run: 210, Failures: 0, Errors: 26, Skipped: 0  
> 根因：`backend/src/main/resources/static/` 目录为空，旧版 `index.html` 和 `database.js` 已被移除

---

## 概述

以下 26 个测试在 `backend/src/main/resources/static/` 目录中读取旧版 HTML/JS 文件（`static/index.html`、`static/database.js`），但该目录已清空。这些文件是旧版单体 HTML 应用的前端代码，已由 `frontend-vue`（Vue 3）替代。

所有 26 个错误均为 `java.io.FileNotFoundException: class path resource [static/xxx] cannot be opened because it does not exist`，无断言失败。

---

## 错误清单

### 1. FrontendStoragePolicyTest（7 个）

| # | 测试方法 | 依赖文件 | 错误行 |
|---|---------|---------|--------|
| 1 | `frontendOnlyPersistsRealBusinessKeysToMysqlStorage` | `static/database.js`, `static/index.html` | L14 |
| 2 | `frontendUsesStoreIdAsStoreManagerLoginPassword` | `static/database.js` | L41 |
| 3 | `frontendUsesSongtiAsGlobalFont` | `static/index.html` | L55 |
| 4 | `frontendWritesOperationLogsToBackendAuditApiInsteadOfBrowserStorage` | `static/database.js` | L66 |
| 5 | `dataEntrySaveAndDeleteUseStructuredFinanceApiInsteadOfEntriesStorageWrite` | `static/index.html` | L81 |
| 6 | `dataHealthNavigationBelongsToOperationsStaffInsteadOfBoss` | `static/index.html`, `static/database.js` | L97 |
| 7 | `roleNavigationHidesDuplicateAndBlankTabs` | `static/database.js` | L118 |

**判定**：这些测试验证旧版 `database.js` 和 `index.html` 中的数据存储策略和 API 调用方式。由于旧版前端已由 Vue3 替代，这些测试的旧版约束已不再适用。**建议：在"彻底移除旧 HTML"任务中删除这些测试。**

---

### 2. Warehouse2StaticContractTest（2 个）

| # | 测试方法 | 依赖文件 | 错误行 |
|---|---------|---------|--------|
| 8 | `frontendDefinesWarehouse2WorkbenchAndDoesNotUseBrowserStorageForWarehouse` | `static/index.html` | L199 |
| 9 | `frontendStoreManagerWarehouseHasReadOnlyCategoryFilter` | `static/index.html` | L270 |

**其余 3 个测试（`migrationDefines*`, `backendDefines*`）通过**，它们使用 `Files.readString` 读取文件系统路径而非 ClassPathResource。

**判定**：这两个测试验证旧版 `index.html` 中的仓库前端功能。Vue3 已有对应的仓库页面（`frontend-vue/src/components/warehouse/`）。**建议：删除这两个测试。**

---

### 3. RoleTodoWorkbenchStaticTest（17 个）

| # | 测试方法 | 依赖文件 | 错误行 |
|---|---------|---------|--------|
| 10 | `frontendDefinesRoleTodoWorkbenchShellAndRoleEndpoints` | `static/index.html`, `static/database.js` | L16 |
| 11 | `frontendLocksStoreDetailScopeForStoreManagersAndSingleStoreRoles` | `static/index.html` | L50 |
| 12 | `storeManagerTodoWorkbenchOnlyShowsRemindersInsteadOfEmbeddingWarehouseOperations` | `static/index.html` | L66 |
| 13 | `frontendShowsProductTrustAndTodoLifecycleFields` | `static/index.html` | L92 |
| 14 | `frontendDefinesExplicitTodoLifecycleContractAndFallbacks` | `static/index.html` | L117 |
| 15 | `frontendDefinesBossOnlyDataHealthEntryAndMysqlOwnershipRules` | `static/index.html`, `static/database.js`, `docs/*.md` | L142 |
| 16 | `frontendDefinesBossDataHealthApiContractAndFallback` | `static/index.html`, `docs/*.md` | L186 |
| 17 | `frontendDefinesRoleEscalationActionsWithRequiredReason` | `static/index.html` | L220 |
| 18 | `frontendDefinesTodoCompletionDialogWithMysqlAttachmentsAndChineseSummary` | `static/index.html` | L242 |
| 19 | `bossTodoViewHidesTechnicalIdsAndTestText` | `static/index.html` | L267 |
| 20 | `bossTodoViewUsesDecisionDashboardEndpointAndBusinessSections` | `static/index.html` | L298 |
| 21 | `bossTodoViewShowsDoneDetailsAndNeutralEmptySummaries` | `static/index.html` | L325 |
| 22 | `bossTodoStatsFollowCurrentStatusFilter` | `static/index.html` | L341 |
| 23 | `frontendLoadsOperationLogsFromBackendAuditApi` | `static/index.html` | L355 |
| 24 | `storeManagerInspectionPageUsesBackendScopedRecordsInsteadOfBrandTabs` | `static/index.html` | L373 |
| 25 | `frontendLocksRoleToLoginAndKeepsReadableDesktopDensity` | `static/index.html` | L389 |
| 26 | `assistantPageUsesRoleSpecificWorkspacesInsteadOfSingleStoreDefault` | `static/index.html` | L417 |

**判定**：这些测试验证旧版 `index.html` 中的待办工作台、数据健康、上报升级、操作日志等前端功能契约。Vue3 中这些功能已有独立实现：
- 待办工作台：`frontend-vue/src/components/todo/`
- 数据助手：`frontend-vue/src/pages/AssistantPage.vue`
- 操作日志：`frontend-vue/src/pages/OperationLogPage.vue`

**建议：在"彻底移除旧 HTML"任务中统一判断：**
- 大部分测试应删除（旧版单文件 HTML 已废弃）
- 少数涉及后端契约的断言（如 API 端点路径）可迁移为 Vue3 集成测试
- 测试 15、16 中读取 `docs/*.md` 的部分可独立保留为设计文档验证

---

## 分类汇总

| 分类 | 数量 | 建议处理方式 |
|------|------|-------------|
| 纯前端旧版验证（只读 `index.html`/`database.js`） | 22 | 直接删除 |
| 混合验证（同时读旧版前端 + 后端源码/docs） | 4 | 拆分：删前端部分，保留后端/docs 部分 |
| 其他 staticresources 测试 | 3 | 已通过，无需处理 |

---

## 处理原则

1. **不允许**为了让测试变绿而恢复 `static/index.html`、`static/database.js` 或其他旧静态资源。
2. 在"彻底移除旧 HTML"任务中统一决定每个测试的归属：删除 / 迁移到 Vue3 / 独立保留。
3. 本轮（DeepSeek 集成）不与 static resource 测试债务混合处理。
4. 0 个新增失败（所有 26 个均为既有债务）。

---

## 后续任务

- [ ] 在"彻底移除旧 HTML"迭代中处理这 26 个测试
- [ ] 为对应 Vue3 页面建立 Playwright E2E 测试替代旧版源码级验证
- [ ] 将涉及后端 API 契约的断言迁移到 Spring MockMvc 集成测试

---

## 关联文件

- `docs/legacy-migration-inventory.md` — 旧版迁移边界清单
- `backend/src/test/java/com/storeprofit/system/staticresources/` — 4 个测试文件
