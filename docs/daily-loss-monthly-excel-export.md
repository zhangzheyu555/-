# 每日报损按月导出 Excel

## 接口契约

`GET /api/daily-loss/exports/monthly.xlsx?month=YYYY-MM&storeId={可选}`

- 必须登录；服务端只使用登录会话中的 `tenantId`、角色和数据范围。
- `storeId` 有值时，只导出该门店，并校验其是否在当前账号的数据范围内。
- `storeId` 为空时，导出当前账号权限范围内的全部门店；店长会由服务端固定为其绑定门店。
- 成功响应为 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，并设置 `Content-Disposition` UTF-8 文件名与 `Cache-Control: private, no-store, max-age=0`。
- 文件名：`{门店名称或全部门店}-{YYYY年MM月}-每日报损.xlsx`。
- 导出只读取报损报表和明细数据，不读取附件元数据或二进制，也不包含照片、附件 URL、ZIP 或内嵌图片。

## 工作表和列

### 每日汇总

日期、门店编码、门店名称、报损品类数、报损总数量、报损总金额、上报状态、提交人、提交时间、复核人、复核时间、复核意见。

按导出范围中的每个门店和自然月每一天生成一行；没有报表的日期状态为“未报”，数量和金额为数值 `0`。

### 报损明细

日期、门店编码、门店名称、物料编码、物料名称、品类、报损数量、单位、单价、报损金额、报损原因、上报状态、提交人、提交时间、复核人、复核时间、复核意见。

数量、单价和金额为 Excel 数值单元格；日期和时间为日期单元格。两个工作表均冻结表头、启用筛选并设置列宽。所有文本字段以 `=`, `+`, `-`, `@` 开头时会添加前导单引号，防止公式注入。

## 权限规则

- 权限码：`daily_loss.export`。
- 老板、财务、督导、店长可以导出；具体门店范围仍由服务端数据范围强制控制。
- 店长只允许其唯一绑定门店；财务使用 FINANCE 范围，其他角色使用 STORE 范围。
- 每次成功导出写入 `operation_log`，动作为 `daily_loss_export`，记录月份、门店范围、汇总行数和明细行数，不记录明细业务内容。

## 迁移说明

旧接口 `GET /api/daily-loss/stores/{storeId}/months/{yyyyMM}/photos.zip` 已移除：仓库内未发现其他业务调用方。日报损的照片上传、预览、审核和库存扣减保持不变。

新增 Flyway 迁移 `V71__daily_loss_monthly_excel_export_permission.sql`，为财务、督导和店长增加导出权限；老板通过后端全权限基线获得该权限。迁移会更新对应账号的权限版本并使旧会话失效。

## 测试命令与结果

建议在项目根目录 `src` 下执行：

```bash
cd backend && mvn -q test
cd ../frontend-vue && npm run build
cd ../frontend-vue && FRONTEND_PREVIEW_URL=http://127.0.0.1:5174 node scripts/verify-daily-loss-reimbursement.mjs
```

- 后端测试：覆盖单门店、空月份、未报日期、跨门店拒绝、响应头、两个 Sheet、数值/日期格式、无图片、无附件 URL、公式注入转义，并使用 Apache POI 实际打开 XLSX。
- 前端 Playwright 校验：覆盖新按钮和文案、`monthly.xlsx` 调用、下载状态、成功/失败中文提示以及旧 ZIP 入口不可见；同时回归日报损照片预览。
- 本次环境结果：Vite 生产构建通过；日报损前端校验脚本通过语法检查。`vue-tsc -b` 被既有的 `WarehouseWorkbenchPage.vue` 缺少 `pendingTransferCount` 类型字段阻断，与本功能无关。当前系统没有可用 Maven/Java 运行时，后端测试无法启动；Playwright Chromium 未安装，浏览器验收脚本无法启动。需要在具备 JDK 21、Maven 与 Playwright 浏览器的 CI/开发环境执行以上完整命令后作为发布证据。
