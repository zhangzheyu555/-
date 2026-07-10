# 旧版工资功能矩阵 → Vue3 实现对照

> 旧版来源：`store-profit-system-upload/index.html` + `database.js`
> 旧版基于 localStorage + 员工姓名关联，新版基于 MySQL + employee_id 关联

## 1. 页面整体布局

| 功能区 | 旧版实现 | Vue3 实现状态 |
|--------|---------|--------------|
| 页面标题 | `员工工资 · 茹果奶茶` | ✅ `员工工资` + 门店/月份副标题 |
| 月份选择 | `<select id="salMonth">` 下拉框 | ✅ `<input type="month">` |
| 门店选择 | `<select id="salStore">` 全部/单店 | ✅ `<select v-model="selectedStoreId">` |
| 搜索框 | `<input id="salSearch">` 姓名/职位 | ✅ `<input v-model="keyword">` 姓名/工号/岗位 |
| 状态筛选 | 无 | ✅ `<select v-model="statusFilter">` 6 种状态 |
| 新增按钮 | `＋ 新增员工` 按钮 | ✅ 通过生成接口批量生成 |
| 删除本月按钮 | `🗑 删除本月` | ✅ 逐条删除（带确认） |

## 2. 汇总指标

| 指标 | 旧版 | Vue3 |
|------|------|------|
| 员工数 | ✅ `rows.length` | ✅ `total`（分页总记录数） |
| 应发工资合计 | ✅ `gSum` | ✅ `summary.grossTotal` |
| 总工时合计 | ✅ `hSum` | ❌ 当前汇总不含工时 |
| 假期剩余合计 | ✅ `vSum` | ❌ 当前汇总不含假期 |
| 状态分布 | 无（无状态机） | ✅ 草稿/待审核/已审核/已发放 |

## 3. 表格列

| 旧版列 | 旧版字段 | Vue3 列 | 实现状态 |
|--------|---------|---------|---------|
| # | 序号 | 工号 | ✅ `employeeId` |
| 姓名 | `name` (员工名) | 姓名 | ✅ `employeeName` |
| 职位 | `position` | 岗位 | ✅ `position` |
| 门店 | (仅全部门店时显示) | 门店 | ✅ `storeName` |
| 出勤 | `attendance` | — | ❌ 表格中未显示 |
| 应发工资 | `gross` | 应发 | ✅ `gross` |
| 总工时 | `workHours` | — | ❌ 表格中未显示 |
| 假期剩余 | `vacationLeft` | — | ❌ 表格中未显示 |
| 操作 | 编辑/删除 | 查看/编辑/提交/审核/发放/锁定/删除 | ✅ 完整状态流转操作 |

## 4. 工资明细字段（编辑弹窗）

| 旧版字段 | 旧版标签 | Vue3 字段 | 实现状态 |
|---------|---------|----------|---------|
| `base` | 基本工资 | `base` | ✅ |
| `social` | 社保补助 | `social` | ✅ |
| `post` | 岗位工资 | `post` | ✅ |
| `meal` | 餐补 | `meal` | ✅ |
| `full` | 全勤 | `fullAttendance` | ✅ |
| `commission` | 提成 | `commission` | ✅ |
| `overtime` | 加班工资（自动=加班时长×20） | `overtime` | ✅ |
| `seniority` | 工龄工资 | `seniority` | ✅ |
| `latenight` | 深夜班 | `lateNight` | ✅ |
| `subsidy` | 补贴 | `subsidy` | ✅ |
| `performance` | 绩效 | `performance` | ✅ |
| `deductUniform` | 扣工服费 | `deductUniform` | ✅ |
| `returnUniform` | 返工服费 | `returnUniform` | ✅ |

## 5. 工资规则（旧版硬编码 → 需确认是否保留）

| 规则 | 旧版值 | Vue3 状态 |
|------|--------|----------|
| 保底工资(店长) | ¥5,300 | ❌ 未实现 |
| 保底工资(领班) | ¥4,600 | ❌ 未实现 |
| 保底工资(训练员) | ¥4,300 | ❌ 未实现 |
| 保底工资(营业员) | ¥4,000 | ❌ 未实现 |
| 固定工资(店长) | base:1900/social:800/post:1300/meal:300/full:200 | ❌ 使用员工档案的 baseSalary |
| 加班费单价 | ¥20/小时 | ❌ 未实现 |
| 假期额度 | 4天/月 | ❌ 未实现 |
| 2月保底规则 | 2月不参与保底 | ❌ 未实现 |
| 实习转正计算 | 两段合并（实习时薪+转正天数） | ❌ 未实现 |
| 满勤天数 | 当月天数-4 | ❌ 未实现 |

## 6. 审核流程

| 功能 | 旧版 | Vue3 |
|------|------|------|
| 状态机 | 无（数据直接编辑） | ✅ DRAFT→PENDING_REVIEW→APPROVED→PAID→LOCKED |
| 编辑权限 | `can("edit")` | ✅ canEdit（ADMIN/BOSS/FINANCE） |
| 删除权限 | `can("delete")` | ✅ canEdit + 草稿/驳回状态 |
| 审核操作 | 无 | ✅ 通过/驳回（BOSS/ADMIN） |
| 发放标记 | 无 | ✅ markPaid |
| 锁定 | 无 | ✅ lockRecord |

## 7. 导出

| 旧版 | Vue3 |
|------|------|
| 无 CSV 导出功能 | ✅ `GET /api/salaries/export` CSV 导出 |

## 8. 数据存储

| 维度 | 旧版 | Vue3 |
|------|------|------|
| 存储 | localStorage (`SALARY` 数组) | MySQL `salary_record` 表 |
| 关联键 | 员工姓名 `name` + 门店 `sid` | `employee_id`（稳定） |
| 租户隔离 | 无 | ✅ `tenant_id` |
| 门店快照 | 无 | ✅ store_name/brand_name 从 JOIN 获取 |

## 9. 缺失功能清单（旧版有、Vue3 无）

| 缺失功能 | 优先级 | 说明 |
|---------|--------|------|
| 保底工资规则 | 中 | 满勤保底计算逻辑 |
| 职位固定工资模板 | 中 | TITLE_WAGE 按职位预设工资项 |
| 加班费自动计算 | 中 | `加班工资 = 加班时长 × 20` |
| 假期管理（余假/结转） | 低 | MONTHLY_VAC=4天，月度结转 |
| 实习转正计算 | 低 | 两段合并（时薪×小时 + 日薪×天数） |
| 工时/假期汇总 | 低 | 表格汇总行显示总工时、总余假 |

## 10. 新增功能（Vue3 有、旧版无）

| 新增功能 | 说明 |
|---------|------|
| 工资状态机 | DRAFT/PENDING_REVIEW/APPROVED/REJECTED/PAID/LOCKED |
| 操作日志 | 所有操作记录到 operation_log |
| 员工档案关联 | employee_id 稳定关联，不因改名丢失 |
| 分页查询 | 大数据量时分页加载 |
| 生成预览 | 预览可生成/跳过/异常员工 |
| 幂等生成 | 同员工+月份不重复 |
| CSV 导出 | 完整 CSV 导出带操作日志 |
| 详情抽屉 | 查看/编辑工资详情，不堆在主页面 |
| 多角色权限 | BOSS/FINANCE/STORE_MANAGER 不同权限 |
| 审核流程 | 提交审核→通过/驳回→发放→锁定 |
