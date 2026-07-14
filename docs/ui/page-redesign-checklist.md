# Vue3 页面改造清单

## 改造边界

- 正式前端仅为 `frontend-vue`，正式数据源为 Spring Boot API 与 MySQL。
- 本轮只重构桌面端视觉和操作布局，不删除路由、按钮能力、接口调用或权限判断。
- 旧版 HTML、`runtime-static` 和 `database.js` 仅用于核对业务流程，不恢复旧数据逻辑。
- 老板统一使用 `BOSS` 角色，界面显示“老板（系统管理员）”，拥有全部菜单和数据范围。

## 统一设计系统

| 能力 | 统一实现 | 验收重点 |
| --- | --- | --- |
| 应用框架 | `AppLayout`、`AppSidebar`、全局设计令牌 | 240px 侧栏、单一内容滚动、无渐变、圆角不超过 8px |
| 页面标题与工具栏 | 路由标题、日期、门店范围、全局搜索 | 标题紧凑、操作靠右、一个区域一个主操作 |
| 指标摘要 | 横向分段 KPI | 数值清晰，正常绿色、风险红色 |
| 表格 | 紧凑表头、44px 左右行高、固定操作列 | 无卡片套卡片、无原始 JSON |
| 表单 | 统一标签、输入框、选择框、校验态 | 中文错误、必填明确、提交防重复 |
| 抽屉与弹窗 | 统一遮罩、标题栏、底部操作栏 | 编辑优先抽屉，关闭与确认行为一致 |
| 页签 | 线型页签或紧凑分段页签 | 复杂流程不堆成长页面 |
| 状态 | `StatusBadge` 与全局状态色 | 处理中橙色、正常绿色、风险红色、停用灰色 |
| 页面状态 | 加载、空数据、错误、无权限、确认 | 不显示堆栈、HTTP 英文错误或重复报错 |

## 页面与接口清单

| 模块 | 路由 | Vue 页面 | 主要功能 | 主要真实接口 | 权限/数据范围 | 改造状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 登录 | `/login` | `LoginPage.vue` | 登录、错误提示、会话建立 | `/api/auth/login` | 全员 | 统一视觉 |
| 老板工作台 | `/boss` | `BossDashboardPage.vue` | KPI、紧急待办、趋势、复核、考试、风险 | `/api/boss/todo-dashboard`、`/api/todos`、`/api/finance/*`、`/api/boss/exam-summary` | BOSS 全公司 | 重点重构 |
| 今日待办 | `/todos` | `TodayTodoPage.vue` | 角色待办、筛选、状态流转、附件 | `/api/*/todos`、`/api/todos/*` | 按角色/门店 | 统一表格与抽屉 |
| 利润概览 | `/profit` | `ProfitOverviewPage.vue` | 月份、品牌、门店、利润指标 | `/api/finance/dashboard`、`/api/finance/months` | 老板/财务/授权店长 | 统一 KPI 与表格 |
| 利润表 | `/profit-table` | `ProfitTablePage.vue` | 利润明细、月份筛选 | `/api/finance/entries` | 老板/财务/门店范围 | 统一紧凑表格 |
| 门店详情 | `/store-detail` | `StoreDetailPage.vue` | 基础资料、经营明细、工资入口 | 门店与财务接口 | 按门店范围 | 统一详情布局 |
| 数据录入 | `/data-entry` | `DataEntryPage.vue` | 手工录入、识别、导入、保存 | `/api/import/*`、`/api/finance/*` | 老板/财务/授权角色 | 统一左右工作区 |
| 财务工作台 | `/finance` | `FinanceWorkbenchPage.vue` | 财务待办、异常、处理入口 | `/api/finance/workbench`、`/api/finance/todos` | BOSS/FINANCE | 统一工作台 |
| 报销 | `/expenses` | `ExpensePage.vue` | 新增、附件、提交、补充、审核、驳回 | `/api/finance/expenses/*`、`/api/storage/upload` | 财务/门店范围 | 统一表格与抽屉 |
| 工资 | `/salary` | `SalaryPage.vue` | 月份、员工、预览、生成、审核、发放、导出 | `/api/finance/salaries/*`、`/api/employees` | 老板/财务/门店范围 | 统一表格与状态 |
| 数据核对 | `/finance-data-check` | `FinanceDataCheckPage.vue` | 数据完整性与差异核对 | 财务核对接口 | BOSS/FINANCE | 统一状态列表 |
| 数据导出 | `/export` | `DataExportPage.vue` | 权限校验、真实文件下载 | `/api/export/*` | 按角色与数据范围 | 统一操作页 |
| 督导巡店 | `/inspection` 及 `/inspection/*` | `SupervisorWorkbenchPage.vue` | 巡检记录、发起巡检、标准、任务、复核 | `/api/inspections`、`/api/inspection/standards`、上传接口 | BOSS/SUPERVISOR/门店范围 | 保留三段式页签 |
| 仓库中心 | `/warehouse` 及 `/warehouse/*` | `WarehousePage.vue`、`WarehouseWorkbenchPage.vue` | 总览、分类、物料、叫货、审核、出库、收货、采购、流水、退货、预警 | `/api/warehouse/*` | BOSS 全权限；仓库维护；门店本店 | 保留全部仓库流程 |
| 培训考试 | `/exam-center`、`/operations/training`、`/operations/exam` | `ExamCenterPage.vue`、运营组件 | 题库、试卷、发布、分配、答题、评分、统计 | `/api/exam-center/*`、`/api/boss/exam-summary` | BOSS 只读总览+全局查看；运营管理；店长/员工受限 | 统一页签 |
| 经营助手 | `/assistant` | `AssistantPage.vue` | 门店/月上下文、本地指标、DeepSeek 对话 | `/api/assistant/chat`、财务/门店接口 | 授权角色 | 统一对话工作区 |
| 门店管理 | `/stores` | `StoreManagementPage.vue` | 门店资料、状态与范围 | 门店管理接口 | BOSS | 统一表格与抽屉 |
| 账号权限 | `/users` | `UserPermissionPage.vue` | 账号、角色、范围、重置密码 | `/api/users/*` | BOSS | 统一表格与抽屉 |
| 平台配置 | `/platform-login`、`/operations/platform`、`/operations/eleme` | `PlatformLoginPage.vue`、`OperationsWorkbenchPage.vue` | 平台账号、饿了么状态与配置 | 平台与饿了么接口 | BOSS/OPERATIONS | 统一配置页 |
| 运营中心 | `/operations` 及 `/operations/*` | `OperationsWorkbenchPage.vue` | 分析、盘存、导入、数据健康、迁移、日志 | `/api/operations/*` | BOSS/OPERATIONS | 保留全部页签 |
| 操作日志 | `/logs`、`/operations/logs` | `OperationLogPage.vue`、运营组件 | 查询关键操作与权限拒绝 | 操作日志接口 | BOSS/授权运营 | 统一紧凑表格 |
| 无权限 | `/no-permission` | `NoPermissionPage.vue` | 403 中文状态与返回入口 | 无 | 全员 | 统一状态页 |

## 不允许回归的功能

- 所有写操作继续调用现有 API，不使用 `localStorage` 保存业务数据。
- 路由守卫、按钮权限和后端权限必须同时生效。
- 查询、筛选、分页、下载、上传、审核、驳回、复核、出入库和考试交卷均保留。
- 接口失败保留已成功加载的数据，页面只展示中文业务错误，控制台保留技术细节。
- 刷新页面后重新从 API 加载，不依赖内存或模拟数据。
