# 数据录入截图识别与表格导入实现计划

日期：2026-07-07

## 目标

在 8080 旧版主线中实现“截图识别 + Excel/CSV 表格导入”的企业可交付第一版。导入结果先生成预览草稿，用户确认后再保存到利润数据。

## 实施原则

- 不重写整页。
- 不触碰 `web/` 实验目录。
- 不在前端写入任何 AI Key。
- 不让识别结果自动覆盖正式数据。
- 后端优先，前端只做上传、预览、确认。
- 保存时复用现有 `FinanceService.save`。

## Phase 1：后端导入骨架

### 1. 新增 importing 模块

新增目录：

```text
backend/src/main/java/com/storeprofit/system/importing/
```

新增类：

- `ProfitImportController`
- `ProfitImportService`
- `ProfitImportRecognizeResponse`
- `ProfitImportRow`
- `ProfitImportCommitRequest`
- `ProfitImportError`
- `ProfitImportSourceType`

### 2. 新增识别接口

实现：

```text
POST /api/imports/profit/recognize
```

能力：

- 接收 multipart 文件。
- 判断图片、Excel、CSV。
- 返回标准化导入行。
- 不保存正式数据。

### 3. 新增提交接口

实现：

```text
POST /api/imports/profit/{importId}/commit
```

能力：

- 接收前端确认后的 rows。
- 校验权限、门店、月份、覆盖冲突。
- 调用 `FinanceService.save` 保存。
- 记录操作日志。

## Phase 2：Excel/CSV 解析

### 1. 添加解析依赖

优先使用 Apache POI 读取 `.xlsx/.xls`，CSV 可用 OpenCSV 或自定义轻量 parser。

### 2. 字段映射

实现字段别名匹配：

- 收入类：营业额、营业收入、流水、实收、退款、优惠。
- 成本类：原材料、包材、损耗、其他成本。
- 费用类：房租、人工、水电、物业、佣金、推广、维修、设备、其他费用。

### 3. 行识别

支持两种表格形态：

- 横向宽表：一行对应一个门店月份，列为各利润字段。
- 纵向键值表：字段在左侧，金额在右侧，常见于单店导出表。

### 4. 批量导入

如果表格包含多门店或多月份：

- 按行生成多个 `ProfitImportRow`。
- 逐行校验门店和月份。
- 前端批量预览后再提交。

## Phase 3：截图识别

### 1. 图片上传与压缩

前端继续支持：

- Ctrl+V 粘贴截图。
- 选择图片。

前端只负责预览和上传，不直接调用模型。

### 2. 后端视觉识别

第一版接口预留：

- 如果已配置可用视觉模型，返回结构化字段。
- 如果未配置，返回 `VISION_NOT_CONFIGURED`，前端提示“当前未开启截图识别，请使用 Excel 导入或手工录入”。

### 3. Prompt 与 schema

要求模型只返回 JSON：

```json
{
  "storeName": "",
  "month": "2026-07",
  "values": {
    "sales": 0,
    "refund": 0,
    "discount": 0
  },
  "confidence": 0.0,
  "warnings": []
}
```

后端必须二次校验 JSON，不信任模型输出。

## Phase 4：前端预览与确认

### 1. 改造导入助手区域

将当前“识别填入”改为“生成导入预览”。

展示：

- 文件名 / 图片预览。
- 识别状态。
- 错误与警告。
- 预览结果。

### 2. 单行预览

单店单月时显示字段对比：

- 字段名。
- 识别值。
- 当前表单值。
- 置信度/来源。

按钮：

- 应用到表单。
- 放弃。

### 3. 批量预览

多行时展示导入表格：

- 可勾选行。
- 错误行不可提交。
- 覆盖已有数据需确认。

按钮：

- 批量保存所选。
- 仅应用第一行到表单。
- 放弃。

## Phase 5：保存与日志

### 1. 保存路径

旧版页面中：

- 单行“应用到表单”只更新当前表单。
- 用户点“保存这家店的数据”仍走现有保存逻辑。
- 批量保存直接调用后端 commit 接口。

后端中：

- commit 接口调用 `FinanceService.save`。
- 操作日志记录“Excel导入”或“截图识别导入”。

### 2. 冲突处理

已有 `storeId + month` 数据时：

- 默认状态为 `CONFLICT`。
- 用户勾选覆盖后提交。
- 日志记录覆盖前后摘要。

## Phase 6：测试计划

### 后端测试

- Excel 宽表解析测试。
- Excel 纵向键值表解析测试。
- CSV 解析测试。
- 字段别名映射测试。
- 金额标准化测试，包括逗号、人民币符号、负数退款。
- 门店匹配与月份校验测试。
- 覆盖冲突测试。
- 未配置视觉模型时截图识别返回明确错误。

### 前端测试

- 数据录入页正常加载。
- 上传 Excel 后出现预览。
- 点击应用到表单后字段更新，实时利润核算更新。
- 批量导入时错误行不可提交。
- 上传截图时不出现前端模型请求。
- 浏览器控制台无 error。
- 网络请求不泄露密钥。

### 回归测试

- `mvn -q -DskipTests package`
- 登录 `admin / 123`
- 手工录入保存仍可用。
- 利润概览、利润表、门店详情仍能读取导入后的数据。
- 门店助手能回答导入后的月份数据。

## 文件修改清单

预计新增：

```text
backend/src/main/java/com/storeprofit/system/importing/
backend/src/test/java/com/storeprofit/system/importing/
```

预计修改：

```text
backend/pom.xml
backend/src/main/resources/static/index.html
backend/src/main/resources/static/database.js
backend/src/main/java/com/storeprofit/system/finance/FinanceService.java
```

如需要持久化导入任务，再新增 Flyway：

```text
backend/src/main/resources/db/migration/V4__profit_import_jobs.sql
```

第一版建议先不新增导入任务表，降低风险。

## 推荐提交拆分

### Commit 1

`Add profit import API contracts`

- 新增 DTO、Controller 空接口或基础接口。
- 不接前端。

### Commit 2

`Parse profit spreadsheets into import drafts`

- 实现 Excel/CSV 解析。
- 加后端单元测试。

### Commit 3

`Add profit import preview UI`

- 改造数据录入页导入助手。
- 支持预览和应用到表单。

### Commit 4

`Commit reviewed import rows`

- 实现批量提交。
- 处理冲突和日志。

### Commit 5

`Route screenshot recognition through backend`

- 移除前端直连模型。
- 后端接视觉识别或返回未配置提示。

## 验收流程

1. 启动后端 8080。
2. 登录管理员。
3. 进入数据录入。
4. 上传一份单店 Excel，确认能预览并应用到表单。
5. 保存后进入利润表确认数据更新。
6. 上传多店 Excel，确认批量预览和覆盖提示。
7. 上传截图，确认不再出现前端模型请求；未配置视觉模型时显示明确提示。
8. 检查控制台、网络请求、提交历史无密钥泄露。
