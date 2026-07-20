# AI Profit OS 45 项功能验收矩阵

## 使用说明

- 范围以 `docs/function-module-inventory.md` 的 45 项正式能力为准；`frontend-vue` 和 `backend` 是唯一正式入口。
- 所有写入、状态流转、附件和导出验收均在独立 QA MySQL 空库或可回收 QA 库执行，禁止使用 3307 历史库或生产库。
- 角色缩写：BOSS、FINANCE、SUPERVISOR、STORE_MANAGER、WAREHOUSE、EMPLOYEE；`OPERATIONS/OPS` 只作为历史迁移验证输入。除 BOSS 明确全域外，跨店访问均断言 403；未登录均断言 401。
- 数据库断言均通过 QA MySQL 查询完成；操作日志断言查询 `operation_log`，并校验操作人、对象、门店和动作。
- `已有自动化覆盖` 仅表示已有单测、集成测试或浏览器脚本覆盖主要路径，不等价于已完成真实 QA MySQL 验收。

## WS：角色工作台

### WS-01 老板工作台
- **路由/API/模块**：`/boss`；`/api/boss/**`、`/api/todos`；`boss`、`todo`。
- **角色**：允许 BOSS；禁止其余六类角色。数据：全域门店、风险和待办；正常流程：进入工作台、按待办跳转业务页；失败：无待办、接口失败提示。
- **安全/数据/日志**：错误角色和跨域对象 403；断言聚合仅包含授权租户，待办状态由原业务表驱动；待办跳转不重复写业务日志。
- **UI/并发/自动化/状态**：桌面与 390x844 菜单和卡片无溢出；只读，不适用并发；`verify-role-menus.mjs`；**已有自动化覆盖**。

### WS-02 财务工作台
- **路由/API/模块**：`/finance`；`/api/finance/**`、`/api/todos`；`finance`、`todo`。
- **角色**：财务工作台页面的主角色为 FINANCE；BOSS 可读取底层财务工作台 API 和进入具体财务业务模块，但不把 `/finance` 作为默认角色工作台；禁止 STORE_MANAGER 写财务汇总。数据：QA 财务月度数据；流程：查看待办并进入利润、报销、工资。
- **安全/数据/日志**：未授权 401/403；断言仅返回授权门店和租户；进入只读无日志，审批/导出转由业务项断言。
- **UI/并发/自动化/状态**：桌面和移动菜单；只读聚合，不适用并发；财务 Controller/Service 测试；**已有自动化覆盖**。

### WS-03 仓库工作台
- **路由/API/模块**：`/warehouse`；`/api/warehouse/**`、`/api/todos`；`warehouse`、`todo`。
- **角色**：允许 WAREHOUSE、BOSS，FINANCE 按只读权限；禁止 STORE_MANAGER 处理总仓业务。数据：总仓、区域仓、待叫货单。
- **安全/数据/日志**：越仓/越店 403；断言库存和待办按仓范围过滤；处理单据写操作日志。
- **UI/并发/自动化/状态**：桌面与移动工作台入口可用；状态流转见 WH-04 至 WH-10；仓库权限测试；**已有自动化覆盖**。

### WS-04 店长工作台
- **路由/API/模块**：`/store`；`/api/store-manager/**`、`/api/todos`；`storemanager`、`expense`、`warehouse`。
- **角色**：店长工作台页面及 `/api/store-manager/workbench` 仅允许已绑定门店的 STORE_MANAGER；BOSS 通过老板工作台和门店、经营、仓库等全局模块管理门店，不冒充店长上下文；禁止跨店店长。数据：两家 QA 门店和各自待办。
- **安全/数据/日志**：第二店店长访问第一店资源 403；断言工作台只显示本店；待办完成由源业务记录日志。
- **UI/并发/自动化/状态**：桌面/移动入口、报销和日报损卡片可点；写入由对应业务项保证幂等；角色菜单脚本；**已有自动化覆盖**。

### WS-05 督导巡店工作台
- **路由/API/模块**：`/operations/inspection`；`/api/inspection/**`、`/api/daily-loss/**`；`inspection`、`dailyloss`。
- **角色**：允许 SUPERVISOR、BOSS；STORE_MANAGER 仅可读取本店整改所需的巡检记录，不具备巡检管理和日报损复核权限；历史 OPERATIONS 不再作为正式角色。数据：授权门店巡检和待复核日报损。
- **安全/数据/日志**：未授权门店 403；断言任务、整改、日报损范围；复核写 `inspection_*` 或 `daily_loss_review`。
- **UI/并发/自动化/状态**：桌面/移动菜单、复核队列；复核状态竞争见 INS-05 和 STORE-01；角色菜单和日报损脚本；**已有自动化覆盖**。

### WS-06 督导工作台
- **路由/API/模块**：`/operations`；`/api/operations/**`、平台/盘存 API；`operations`。
- **角色**：督导工作台页面的主角色为 SUPERVISOR；BOSS 通过老板工作台进入对应全局业务模块。督导可进入授权范围内日报损复核、平台配置、盘存和培训，禁止财务敏感写入与总仓管理。数据：平台配置、盘存和培训测试数据。
- **安全/数据/日志**：授权门店日报损复核应允许，跨授权门店、财务敏感写入和总仓管理应 403；断言运营数据范围；配置变更写日志。
- **UI/并发/自动化/状态**：桌面/移动菜单黑名单；写操作按模块幂等；角色菜单脚本；**已有自动化覆盖**。

### WS-07 员工工作台
- **路由/API/模块**：`/employee`；`/api/employee-workbench/**`、`/api/employee-assistant/**`；`employee`、`employeeassistant`。
- **角色**：允许 EMPLOYEE、BOSS；禁止员工访问他人资料和管理入口。数据：员工、绑定门店、课程和待办。
- **安全/数据/日志**：跨员工/跨店 403；断言仅本人资料和门店；转人工、反馈写日志。
- **UI/并发/自动化/状态**：桌面/移动资料、待办、助手入口；读操作不适用并发；员工 Controller/Service 测试；**已有自动化覆盖**。

## FIN：经营财务

### FIN-01 利润概览
- **路由/API/模块**：`/profit-overview`；`/api/finance/profit/**`；`finance`。
- **角色**：BOSS、FINANCE 可全域或授权范围，STORE_MANAGER 仅本店只读；其余禁止。数据：两店不同月份利润。
- **安全/数据/日志**：跨店 403 或过滤；断言 BigDecimal 汇总与数据库一致；导出另见 FIN-06。
- **UI/并发/自动化/状态**：桌面/移动图表空态；只读；FinanceService/Repository 测试；**已有自动化覆盖**。

### FIN-02 利润表
- **路由/API/模块**：`/profit-table`；`/api/finance/profit/page`；`finance`。
- **角色**：BOSS、FINANCE、STORE_MANAGER 本店读；其余禁止。数据：分页、多门店、多月份利润行。
- **安全/数据/日志**：非法门店过滤或 403；断言金额精度和分页总数；读操作无强制日志。
- **UI/并发/自动化/状态**：桌面/移动筛选和空态；只读；财务分页测试；**已有自动化覆盖**。

### FIN-03 经营数据录入与导入
- **路由/API/模块**：`/data-entry`；`/api/finance/**`、`/api/import/**`；`finance`、`importing`。
- **角色**：FINANCE、BOSS 写；STORE_MANAGER 不得录入经营财务。数据：合法/非法表格、重复月份记录。
- **安全/数据/日志**：错误角色和跨店 403；断言 BigDecimal 落库、失败事务回滚；导入、修改、拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动校验、错误提示；重复导入幂等或明确冲突；导入授权和解析测试；**已有自动化覆盖**。

### FIN-04 每日报销
- **路由/API/模块**：`/expenses`；`/api/expenses/**`；`expense`、`storage`。
- **角色**：STORE_MANAGER 仅本店提交，FINANCE/BOSS 审核，SUPERVISOR/OPERATIONS 不得审核。数据：两店报销、QA 收据图片。
- **安全/数据/日志**：无图有金额失败，跨店附件 403；断言金额、状态、附件业务绑定；提交、批准、驳回、上传下载和拒绝均记日志。
- **UI/并发/自动化/状态**：桌面/移动缩略图、预览、空态；重复提交/审核不覆盖状态；ExpenseService、ExpenseSupplementService、报销图片脚本；**已有自动化覆盖**。

### FIN-05 工资与本店工资核对
- **路由/API/模块**：`/salary`、`/store/salary`；`/api/salaries/**`；`salary`。
- **角色**：FINANCE/BOSS 管理，STORE_MANAGER 本店核对，EMPLOYEE 仅本人信息；其余禁止。数据：员工、工资月份、状态样本。
- **安全/数据/日志**：跨店/跨员工 403；断言工资快照、状态和金额；提交、审核、支付写日志。
- **UI/并发/自动化/状态**：桌面/移动列表与详情；状态变更要求条件更新；SalaryService/Workflow 测试；**已有自动化覆盖**。

### FIN-06 经营数据导出
- **路由/API/模块**：`/data-export`；`/api/export/**`；`reporting`、`finance`。
- **角色**：BOSS、FINANCE 按范围导出；STORE_MANAGER 仅本店允许范围。数据：多店利润、报销和导出请求。
- **安全/数据/日志**：跨店导出 403；断言导出内容仅为授权范围；下载/导出写日志。
- **UI/并发/自动化/状态**：桌面/移动导出状态、错误提示；重复请求不产生异常任务；ExportControllerScopeTest；**已有自动化覆盖**。

## STORE：门店日常

### STORE-01 每日报损
- **路由/API/模块**：`/daily-loss`；`/api/daily-loss/**`；`dailyloss`、`storage`。
- **角色**：STORE_MANAGER 本店提交，SUPERVISOR 复核授权店，FINANCE/BOSS 只读；OPERATIONS 禁止负责人流程。数据：两店、active/inactive 品类、QA 图片。
- **安全/数据/日志**：401/跨店 403；断言同租户店日唯一、BigDecimal 单价快照、附件店铺/报损绑定；提交、复核、照片导出、上传下载、拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动分类选择、缩略图、列表、无刷新按钮；首次保存、草稿覆盖、重复提交、复核竞争要求幂等；DailyLossServiceTest、布局脚本；**已有自动化覆盖**。

### STORE-02 门店详情与经营结果
- **路由/API/模块**：`/store-detail`；`/api/stores/**`、`/api/finance/**`；`organization`、`finance`。
- **角色**：STORE_MANAGER 仅本店，SUPERVISOR 授权店，BOSS 全域；其他按权限。数据：两店资料和经营汇总。
- **安全/数据/日志**：跨店 403/过滤；断言门店和经营数据同一范围；只读不强制日志。
- **UI/并发/自动化/状态**：桌面/移动筛选、空态；只读；StoreController HTTP 授权测试；**已有自动化覆盖**。

### STORE-03 门店档案与管理
- **路由/API/模块**：`/stores`；`/api/stores/**`、`/api/brands/**`；`organization`。
- **角色**：BOSS 管理，授权角色读；STORE_MANAGER 不得维护其他门店。数据：QA 品牌和三门店。
- **安全/数据/日志**：写入和跨店访问 403；断言组织、品牌、门店关系；创建/修改写日志。
- **UI/并发/自动化/状态**：桌面/移动表单、禁用态；重复编码冲突明确；OrganizationServicePermissionTest；**已有自动化覆盖**。

## WH：仓库供应链

### WH-01 物料档案与商品启停
- **路由/API/模块**：`/warehouse` 商品页；`/api/warehouse/items/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 管理，FINANCE 读；店长仅浏览授权商品。数据：active 和 inactive 商品。
- **安全/数据/日志**：错误角色 403；断言停用商品不可新增叫货/入库、历史可读；启停写日志。
- **UI/并发/自动化/状态**：桌面/移动启停与空态；重复启停幂等；WarehouseServiceTest；**已有自动化覆盖**。

### WH-02 总仓与区域仓库存
- **路由/API/模块**：`/warehouse`；`/api/warehouse/inventory/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 全仓，FINANCE 读；店长无总仓写权。数据：中央仓、区域仓、库存余额。
- **安全/数据/日志**：越仓 403；断言仓库维度库存余额；调整写日志。
- **UI/并发/自动化/状态**：桌面/移动库存、预警；库存扣减并发不为负；WarehouseMultiFacilityFlowTest；**已有自动化覆盖**。

### WH-03 门店库存
- **路由/API/模块**：`/warehouse/store-inventory`；`/api/warehouse/store-inventory/**`；`warehouse`。
- **角色**：STORE_MANAGER 本店读，WAREHOUSE/BOSS 管理；跨店禁止。数据：两个门店库存。
- **安全/数据/日志**：跨店 403；断言门店库存与流水一致；写入日志由入库/收货触发。
- **UI/并发/自动化/状态**：桌面/移动清单；只读查询；仓库范围测试；**已有自动化覆盖**。

### WH-04 门店叫货
- **路由/API/模块**：`/warehouse` 叫货页；`/api/warehouse/requisitions/**`；`warehouse`。
- **角色**：STORE_MANAGER 本店创建，WAREHOUSE/BOSS 查看处理；其他禁止创建。数据：门店、active 商品、可用库存。
- **安全/数据/日志**：跨店创建 403；断言停用商品拒绝、叫货单和明细落库；创建写日志。
- **UI/并发/自动化/状态**：桌面/移动数量校验；重复请求幂等；WarehouseRequestDedupMigrationTest；**已有自动化覆盖**。

### WH-05 仓库处理叫货与发货
- **路由/API/模块**：`/warehouse` 发货页；`/api/warehouse/requisitions/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 发货，STORE_MANAGER 只读本店单据。数据：待处理叫货和库存。
- **安全/数据/日志**：非仓库角色 403；断言状态、出库流水和库存一致；发货写日志。
- **UI/并发/自动化/状态**：桌面/移动处理队列；并发发货只成功一次；WarehouseNetworkServiceIdempotencyTest；**已有自动化覆盖**。

### WH-06 门店收货确认
- **路由/API/模块**：`/warehouse` 收货页；`/api/warehouse/requisitions/{id}/receive`；`warehouse`。
- **角色**：STORE_MANAGER 仅本店确认，WAREHOUSE/BOSS 查看；跨店禁止。数据：已发货单。
- **安全/数据/日志**：跨店 403；断言收货状态、门店库存、收货仓快照；确认写日志。
- **UI/并发/自动化/状态**：桌面/移动确认和失败提示；重复收货幂等；WarehouseReturnReceiveWarehouseSnapshotMigrationTest；**已有自动化覆盖**。

### WH-07 外部采购
- **路由/API/模块**：`/warehouse` 采购页；`/api/warehouse/purchases/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 创建，FINANCE 读；店长禁止。数据：供应商、商品、采购单。
- **安全/数据/日志**：角色和仓范围 403；断言采购单金额 BigDecimal；创建/审核写日志。
- **UI/并发/自动化/状态**：桌面/移动表单；重复创建有业务幂等键或冲突；WarehouseServiceTest；**已有自动化覆盖**。

### WH-08 采购入库与入库记录
- **路由/API/模块**：`/warehouse` 入库页；`/api/warehouse/inbound/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 入库，FINANCE 读；店长禁止。数据：采购单、active/inactive 商品。
- **安全/数据/日志**：停用商品入库失败；断言库存、入库流水、金额快照；入库写日志。
- **UI/并发/自动化/状态**：桌面/移动收货录入；同单重复入库幂等；WarehouseServiceTest；**已有自动化覆盖**。

### WH-09 出入库、调拨与预警
- **路由/API/模块**：`/warehouse` 流水/预警页；`/api/warehouse/movements/**`；`warehouse`。
- **角色**：WAREHOUSE/BOSS 管理，FINANCE 读，店长仅本店读。数据：库存流水、低于安全库存物料。
- **安全/数据/日志**：跨仓 403；断言调拨两端流水和余额；预警配置修改写日志。
- **UI/并发/自动化/状态**：桌面/移动筛选、空态；并发调拨保持守恒；WarehouseMultiFacilityFlowTest；**已有自动化覆盖**。

### WH-10 配送退货、附件与打印下载
- **路由/API/模块**：`/warehouse` 退货页；`/api/warehouse/returns/**`、`/api/storage/attachments/**`；`warehouse`、`storage`。
- **角色**：WAREHOUSE 处理，STORE_MANAGER 本店申请/查看，BOSS 全域；跨店禁止。数据：退货单、QA 图片、PDF。
- **安全/数据/日志**：附件/下载跨店 403；断言退货状态、库存、附件绑定；上传、下载、权限拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动附件预览；重复收货/退货不重复入库；WarehouseReturnPdfControllerAuthorizationTest、StorageServiceTest；**已有自动化覆盖**。

## INS：督导巡店

### INS-01 发起巡检任务
- **路由/API/模块**：`/operations/inspection`；`/api/inspection/tasks/**`；`inspection`。
- **角色**：SUPERVISOR/BOSS 发起，STORE_MANAGER 只看本店，OPERATIONS 仅现有巡检权限。数据：巡检标准、两门店。
- **安全/数据/日志**：越店 403；断言任务、标准快照落库；创建任务写日志。
- **UI/并发/自动化/状态**：桌面/移动发起、空态；同店同日重复规则明确；InspectionRecordServiceTest；**已有自动化覆盖**。

### INS-02 巡检记录与问题登记
- **路由/API/模块**：`/operations/inspection/records`；`/api/inspection/records/**`；`inspection`、`storage`。
- **角色**：SUPERVISOR/BOSS 写，STORE_MANAGER 本店读；其他禁止。数据：任务、评分、QA 图片证据。
- **安全/数据/日志**：附件跨店 403；断言记录、问题和图片绑定；提交/上传写日志。
- **UI/并发/自动化/状态**：桌面/移动记录、图片、错误态；重复提交不重复问题；InspectionRecordControllerTest；**已有自动化覆盖**。

### INS-03 稽核标准维护
- **路由/API/模块**：`/operations/inspection` 标准页；`/api/inspection/standards/**`；`inspection`。
- **角色**：SUPERVISOR/BOSS 管理，STORE_MANAGER 只读；其他禁止。数据：版本化巡检标准。
- **安全/数据/日志**：错误角色 403；断言标准版本和历史快照；修改写审计日志。
- **UI/并发/自动化/状态**：桌面/移动标准列表；版本冲突需显式处理；InspectionStandardServiceTest；**已有自动化覆盖**。

### INS-04 门店整改
- **路由/API/模块**：`/store/inspection/rectifications`；`/api/inspection/rectifications/**`；`inspection`。
- **角色**：STORE_MANAGER 本店整改，SUPERVISOR/BOSS 查看；跨店禁止。数据：待整改问题、QA 图片。
- **安全/数据/日志**：跨店 403；断言整改状态、说明和证据；提交整改/上传写日志。
- **UI/并发/自动化/状态**：桌面/移动表单与预览；重复提交不覆盖已复核状态；InspectionRectificationServiceTest；**已有自动化覆盖**。

### INS-05 督导整改复核
- **路由/API/模块**：`/operations/inspection/reviews`；`/api/inspection/rectifications/{id}/review`；`inspection`。
- **角色**：SUPERVISOR/BOSS 复核；STORE_MANAGER、OPERATIONS 禁止复核。数据：已提交整改。
- **安全/数据/日志**：角色/跨店 403；断言复核人、时间、结果不可被第二次覆盖；复核和拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动复核队列；双复核要求条件更新；InspectionRectificationWorkflowMigrationTest；**已有自动化覆盖**。

## EMP：员工与培训

### EMP-01 员工档案
- **路由/API/模块**：`/staff`；`/api/employees/**`；`employee`。
- **角色**：BOSS/授权管理者维护，STORE_MANAGER 仅授权范围；EMPLOYEE 不得管理他人。数据：30 条 QA 员工、门店归属。
- **安全/数据/日志**：跨店员工 403；断言员工-账号-门店关系；新增/修改写日志。
- **UI/并发/自动化/状态**：桌面/移动列表、健康证提示；重复账号/员工编码冲突；EmployeeServicePermissionTest；**已有自动化覆盖**。

### EMP-02 员工个人资料、门店与工资信息
- **路由/API/模块**：`/employee/profile`；`/api/employees/me/**`；`employee`、`salary`。
- **角色**：EMPLOYEE 本人只读，BOSS 管理；禁止跨员工。数据：至少两名不同门店员工。
- **安全/数据/日志**：跨员工 403；断言本人、门店、工资范围；敏感修改写日志。
- **UI/并发/自动化/状态**：桌面/移动资料页；只读；EmployeeWorkbenchControllerTest；**已有自动化覆盖**。

### EMP-03 培训学习与考试
- **路由/API/模块**：`/employee/exams`、`/exam-center`；`/api/exams/**`；`operations`、`employee`。
- **角色**：EMPLOYEE 学习/答题，STORE_MANAGER/OPERATIONS 管理范围，BOSS 全域。数据：课程、题目、分配记录。
- **安全/数据/日志**：跨门店考试 403；断言答题记录、分数和冷却期；提交答卷写日志。
- **UI/并发/自动化/状态**：桌面/移动答题可用；重复提交答卷幂等；考试服务测试和移动脚本；**已有自动化覆盖**。

### EMP-04 培训进度与考试管理
- **路由/API/模块**：`/exam-center`；`/api/exams/admin/**`；`operations`。
- **角色**：OPERATIONS/BOSS 管理，STORE_MANAGER 仅报告权限；EMPLOYEE 不得管理。数据：任务、进度、考试结果。
- **安全/数据/日志**：错误角色 403；断言分配范围和进度；创建课程/分配写日志。
- **UI/并发/自动化/状态**：桌面/移动管理页；重复分配规则明确；Exam 相关服务测试；**已有自动化覆盖**。

## QMAI：外部平台与配方

### QMAI-01 企迈凭证与品牌配置
- **路由/API/模块**：`/platform-login`；`/api/platform/**`、`/api/qmai/**`；`platform`、`qmai`。
- **角色**：OPERATIONS/BOSS 管理；其余禁止。数据：Mock 或空测试配置，绝不使用生产凭证。
- **安全/数据/日志**：错误角色 403；断言凭证脱敏/加密存储和品牌范围；配置变更写日志。
- **UI/并发/自动化/状态**：桌面/移动配置空态；重复保存不泄露密钥；PlatformAdapterTest；**已有自动化覆盖**。

### QMAI-02 企迈营业额查询与导出
- **路由/API/模块**：`/platform-login`/经营页；`/api/qmai/revenue/**`；`qmai`、`reporting`。
- **角色**：OPERATIONS/BOSS，FINANCE 仅按已授权查询；其余禁止。数据：Mock 回包或空配置。
- **安全/数据/日志**：无配置友好失败，不调用生产；断言导出范围；查询失败、导出写日志。
- **UI/并发/自动化/状态**：桌面/移动空态、重试；只读；Eleme/Qmai 数据范围测试；**环境阻塞**。

### QMAI-03 企迈商品销量查询与导出
- **路由/API/模块**：平台经营页；`/api/qmai/products/**`；`qmai`、`reporting`。
- **角色**：OPERATIONS/BOSS，授权 FINANCE 读；其余禁止。数据：Mock 商品销量。
- **安全/数据/日志**：越品牌 403；断言导出无越权数据；导出和拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动筛选和空态；只读；平台数据范围测试；**环境阻塞**。

### QMAI-04 配方销量填充、用量测算与水果毛重
- **路由/API/模块**：平台/配方页；`/api/qmai/**`、`/api/warehouse/**`；`qmai`、`warehouse`。
- **角色**：OPERATIONS/BOSS 管理，WAREHOUSE 按授权读；其余禁止。数据：Mock 销量、配方、损耗率。
- **安全/数据/日志**：非法参数/跨品牌 403；断言 BigDecimal 计算和快照；填充/导出写日志。
- **UI/并发/自动化/状态**：桌面/移动错误态；重复填充幂等；计算单测；**环境阻塞**。

## AI：智能助手

### AI-01 门店经营助手
- **路由/API/模块**：`/assistant`；`/api/assistant/**`；`assistant`、`finance`。
- **角色**：具备 `assistant.use` 的授权角色；无权限者禁止。数据：QA 经营快照和无外部模型的降级场景。
- **安全/数据/日志**：跨店数据不得进入上下文；断言本地数据快照范围；提问、失败和转交按审计策略记录。
- **UI/并发/自动化/状态**：桌面/移动会话、空态、模型不可用提示；请求幂等不重复落业务数据；Assistant*Test；**已有自动化覆盖**。

### AI-02 员工服务助手
- **路由/API/模块**：`/employee-assistant`；`/api/employee-assistant/**`；`employeeassistant`。
- **角色**：EMPLOYEE 和授权管理角色；禁止读取他人敏感资料。数据：员工资料、知识库、人工转接队列。
- **安全/数据/日志**：跨员工/跨店 403；断言知识库权限和转接记录；反馈、转接写日志。
- **UI/并发/自动化/状态**：桌面/移动对话与转接；重复反馈幂等；EmployeeAssistant*Test；**已有自动化覆盖**。

## GOV：系统治理

### GOV-01 账号、角色、权限码与数据范围
- **路由/API/模块**：`/users`；`/api/users/**`、`/api/permissions/**`；`platform.users`、`authorization`。
- **角色**：BOSS 管理；其他角色禁止。数据：七类账号、两店店长、权限版本。
- **安全/数据/日志**：未登录 401、错误角色 403；断言角色模板、数据范围和令牌权限版本；授权变更、拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动权限页；并发改权以版本控制或最后写入策略明确；UserAuthorizationManagementTest；**已有自动化覆盖**。

### GOV-02 操作日志与审计查询
- **路由/API/模块**：`/logs`；`/api/audit/**`；`audit`。
- **角色**：BOSS/授权审计角色；普通用户禁止。数据：各模块操作日志。
- **安全/数据/日志**：越权 403；断言日志不可由前端伪造、包含租户和操作人；本项查询不重复写日志。
- **UI/并发/自动化/状态**：桌面/移动筛选、空态；只读；AuditControllerTest；**已有自动化覆盖**。

### GOV-03 组织、品牌、门店与平台级配置
- **路由/API/模块**：`/stores`、`/platform-login`；`/api/brands/**`、`/api/stores/**`、`/api/platform/**`；`organization`、`platform`。
- **角色**：BOSS 管理，OPERATIONS 仅平台授权范围；其余禁止。数据：QA 租户、品牌、三店。
- **安全/数据/日志**：越租户/越品牌 403；断言组织引用完整性；变更和拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动管理页；重复编码冲突明确；组织与平台测试；**已有自动化覆盖**。

## FLOW：跨模块待办闭环

### FLOW-01 今日待办与业务跳转
- **路由/API/模块**：所有工作台；`/api/todos/**`；`todo`、各源业务模块。
- **角色**：所有授权角色仅见本职责待办，BOSS 见全域；禁止跨店待办处理。数据：报销、日报损、巡检、仓库的待办源记录。
- **安全/数据/日志**：跨店处理 403；断言待办由源业务状态生成、完成后回写一致；状态转移和拒绝写日志。
- **UI/并发/自动化/状态**：桌面/移动跳转不丢上下文；重复完成幂等；BusinessTodoServiceTest、TodoAuthorizationBoundaryTest；**已有自动化覆盖**。

## 自动化统计基线

- 45 项中：**42 项已有单测、集成测试或浏览器脚本基线**；QMAI-02 至 QMAI-04 需要独立 Mock/测试凭证和 QA 平台配置，当前标记为环境阻塞。
- 阶段 2 必须将“已有自动化覆盖”逐项转为真实 QA 数据库和真实权限会话的执行证据；现有前端报销/角色脚本的 Mock 覆盖不得替代后端授权验收。
