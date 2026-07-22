# FIN-06 经营数据导出：范围冻结

执行批次：`E2E-20260720`
冻结时间：2026-07-21
状态：`PASS`
范围：桌面 Web、隔离 QA MySQL、受控 E2E 账号；不含移动端和真实业务数据。

## 结论

FIN-06 的实际模块为 **经营数据导出**。产品已确认：**店长不需要、也不得导出任何经营数据**。该规则已同步到验收矩阵、前端路由/菜单和后端授权边界；即使存在历史或个人 `finance.export` 授权，店长仍会被后端拒绝。

范围冻结期间发现的成功导出审计范围缺失已按最小改动修复为 `IP-P1-017`：三类下载在写入成功审计前统一解析实际业务范围，审计写入实际门店、月份和范围说明；工资范围拒绝也带请求月份。已补充单元测试、通过定向后端回归和前端生产构建。

受控 BOSS 账号已使用本轮明确授权的 QA 凭据登录并确认角色为 `BOSS`；未重置密码。FIN-06 已完成全量接口、权限、审计、页面和构建复测，详见 `fin-06-export-acceptance.md`。

## 已冻结的实现范围

| 项目 | 现有实现与证据 | 结论 |
| --- | --- | --- |
| 正式模块名称 | `docs/function-module-inventory.md` 的财务域第 6 项为“经营数据导出”；`docs/qa/internal-pilot-gated-test-plan.md` 的 G3 顺序同样列为 FIN-06。 | 已确认 |
| 桌面入口 | 当前前端路由为 `/admin/export`，路由名 `data-export`，页面为 `frontend-vue/src/pages/DataExportPage.vue`。验收矩阵中的 `/data-export` 是旧路径记载，与当前实现不一致。 | 已确认；文档待校正 |
| 三类下载 | 门店利润：`GET /api/export/profit-ranking.csv`；报销记录：`GET /api/export/expenses.csv`；员工工资：`GET /api/export/salaries.csv`。前端均为同步 CSV 下载，带 `month`、`brandId`、`storeId` 参数。 | 已实现 |
| 后端入口 | `backend/src/main/java/com/storeprofit/system/reporting/ExportController.java`。三条接口均要求登录和 `finance.export`。CSV 为 UTF-8 BOM、附件下载；没有导出任务、异步状态表或业务写入。 | 已实现 |
| 数据来源 | 利润来自 `profit_entry`（含门店/品牌）；报销来自 `expense_claim`（含门店/品牌）；工资来自 `salary_record`（员工/门店/品牌关联）；审计写入 `operation_log`。没有独立导出数据表。 | 已确认 |
| 状态流 | 请求 → 认证/权限 → 数据范围解析或服务校验 → 查询 → 组装 CSV → 记录下载审计 → 返回文件。重复下载不会新建业务单据或汇总记录，只应新增独立下载审计。 | 已确认 |
| 范围控制 | 利润与报销复用财务数据范围；工资使用 `SALARY` 数据范围。`BusinessScopeResolver` 对店长会绑定本店，对伪造门店应拒绝。 | 已实现，但见权限冲突 |
| 当前默认角色权限 | QA 角色权限表中，`finance.export` 的默认角色仅为 `FINANCE`；老板为系统特权角色。店长、督导、仓库、员工默认不具备该权限。 | 已确认 |

## 规则状态清单

| 规则 | 状态 | 依据/说明 |
| --- | --- | --- |
| 财务、老板可在授权范围内下载三类 CSV | 代码已实现 | `ExportController`、`AccessControlService.requireDataExport`、当前 QA 角色权限。 |
| 未登录请求必须拒绝 | 代码已实现 | 三个接口在下载前要求认证。 |
| 非法月份返回明确中文错误 | 代码已实现 | `ExportController.normalizeMonth` 返回 `EXPORT_MONTH_INVALID` / “月份格式不正确”。 |
| 重复下载不写业务数据 | 代码已实现 | 导出为同步 GET，无导出任务或业务写库逻辑。 |
| 成功下载审计含操作者、对象、实际门店范围、月份和结果 | 代码已修复，待 QA 复测 | `ExportController` 已记录已解析的 `store_id`、月份及范围说明；`ExportControllerScopeTest` 覆盖利润/报销成功审计范围。 |
| 店长可否导出 | 已冻结 | 不允许。前端路由和菜单仅对财务（老板保留系统特权）开放；后端 `requireDataExport` 对非财务/老板角色做不可绕过的拒绝。 |
| 店长是否可下载工资明细 | 已冻结 | 不允许导出任何 CSV，因此不存在工资字段脱敏导出路径。 |

## 验收完成

本轮已完成老板/财务三类 CSV、重复下载、非法月份、跨范围、六角色拒绝、审计、1280px 页面、FIN-01～FIN-05 基线复核和清理；未修改真实业务数据。

## 本次受控验证与清理

- 已以隔离 QA 财务账号下载一份 `2026-07` 门店 A 的利润 CSV，仅用于核验范围与审计字段；CSV 内容仅包含门店 A 的两项受控利润字段，未读写真实数据。
- 该请求未修改 `profit_entry`、`expense_claim`、`salary_record` 或任何业务汇总。
- 已按精确 ID 清理本次下载审计与退出会话审计（`operation_log` 记录 433、434）；复核两条记录残留为 0，财务 E2E 账号有效令牌残留为 0。
- 本轮修复后的 QA 预检中，财务页面可正常登录并显示三类下载与两家授权门店；老板登录返回 401 后立即停止。已退出浏览器财务会话，精确清理本轮财务、店长、仓库测试令牌；本轮新增导出审计残留为 0。QA 基线仍为 `profit_entry=2`、2026-07 净利 `3350.00`、报销 `0`、工资 `0`。
