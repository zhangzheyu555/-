# 数据录入截图识别与表格导入修改方案

日期：2026-07-07

## 背景

当前 8080 旧版主页面的数据录入页已经有两个入口：

- 截图识别：支持粘贴或上传企迈截图。
- Excel 表格：支持上传 `.xlsx/.xls/.csv`。

但现有实现仍是前端把图片或表格文本直接发给外部模型，再把结果填入表单。这种方式适合 Demo，不适合企业交付，主要问题是：

- 模型调用在前端，不利于保护密钥和控制供应商切换。
- 识别结果没有草稿、置信度、字段来源和错误提示。
- Excel 只转 CSV 文本交给 AI，无法稳定处理多 sheet、合并单元格、字段别名和批量门店。
- 识别后直接填表，缺少导入预览、冲突处理和导入审计。
- 后端已有 `/api/finance/entries`，但旧页面仍以本地 `ENTRIES` 为主，需要设计兼容路径。

## 目标

把数据录入升级为企业可交付的数据导入中心第一版：

- 截图识别可从企迈截图中提取营业总收入、退款、优惠、成本、费用等字段。
- 表格导入可解析“营业额与支出”Excel/CSV，支持单店单月和多店多月数据。
- 所有识别结果先进入“导入草稿”，用户确认后才写入正式利润数据。
- 后端统一提供识别、解析、预览、提交接口，前端不直接调用模型。
- DeepSeek Key 或其他 AI Key 只通过运行环境变量进入后端，不写入前端、HTML、提交历史。
- 保持当前手工录入、实时利润核算、历史记录可用。

## 非目标

本阶段不做完整 ERP/POS 对接。

本阶段不做复杂的模板市场，只内置当前门店利润字段和企迈常见表格格式。

本阶段不自动覆盖正式数据。所有覆盖都必须经过用户确认。

本阶段不重写为 Vue 版本，只改 8080 旧版主线和 Spring Boot 后端。

## 方案比较

### 方案一：前端继续调用 AI

优点是开发快。缺点是密钥暴露风险高、无法审计、无法统一错误处理，也无法稳定支持企业私有化部署。不推荐。

### 方案二：后端 Import Service 统一识别

前端只负责上传文件、展示预览和提交确认。后端负责图片 OCR/视觉模型、Excel 解析、字段映射、校验和保存。这个方案安全、可审计、可扩展，适合作为商业 Demo 的主线方案。推荐采用。

### 方案三：先不接 AI，只做 Excel 模板导入

优点是稳定，缺点是不能解决老板实际拿截图、乱表格导入的痛点，也无法体现 AI 产品价值。可作为降级能力保留，但不应作为唯一方案。

## 推荐方案

采用“后端 Import Service + 前端导入预览”的方案。

整体流程：

```mermaid
flowchart LR
  A["用户上传截图/Excel"] --> B["后端 Import Service"]
  B --> C["文件解析层"]
  C --> D["字段映射与标准化"]
  D --> E["校验与冲突检测"]
  E --> F["导入预览草稿"]
  F --> G["用户确认/修改"]
  G --> H["保存到 profit_entry"]
  H --> I["操作日志"]
```

## 页面改造

### 数据录入页顶部

保留当前品牌、月份、门店选择。

将当前虚线识别区改为“导入助手”区域：

- 截图识别：选择图片、粘贴截图、显示缩略图。
- 表格导入：选择 Excel/CSV、显示文件名、sheet 数量、识别模式。
- 识别按钮：统一文案为“生成导入预览”。

### 识别预览面板

识别后不直接覆盖表单，而是在录入表单上方显示预览：

- 来源：截图 / Excel / CSV。
- 识别门店、月份。
- 字段列表：字段名、识别值、当前表单值、置信度、来源位置。
- 异常提示：缺字段、金额为负、月份不一致、门店不匹配。
- 操作：
  - 应用到当前表单。
  - 修改后应用。
  - 放弃本次识别。
  - 多行数据时进入批量导入确认。

### 批量导入面板

Excel 中识别到多店或多月时，展示表格：

- 门店
- 月份
- 营业总收入
- 退款
- 优惠
- 成本合计
- 费用合计
- 净利润预估
- 状态：新增 / 覆盖 / 有错误 / 需确认

用户可以勾选行后批量保存。

## 后端接口设计

### 1. 上传并识别

`POST /api/imports/profit/recognize`

请求使用 `multipart/form-data`：

- `file`：图片、Excel 或 CSV。
- `sourceType`：`SCREENSHOT`、`EXCEL`、`CSV`、`AUTO`。
- `storeId`：可选，当前页面选中的门店。
- `month`：可选，当前页面选中的月份。

返回：

```json
{
  "importId": "imp_20260707_xxx",
  "sourceType": "EXCEL",
  "status": "NEEDS_REVIEW",
  "rows": [
    {
      "rowId": "row_1",
      "storeId": "rg001",
      "storeName": "荆州之星店",
      "month": "2026-07",
      "confidence": 0.91,
      "values": {
        "sales": 83896,
        "refund": 0,
        "discount": 0,
        "material": 27871
      },
      "warnings": []
    }
  ],
  "errors": []
}
```

### 2. 提交导入结果

`POST /api/imports/profit/{importId}/commit`

请求：

```json
{
  "rows": [
    {
      "rowId": "row_1",
      "storeId": "rg001",
      "month": "2026-07",
      "overwrite": true,
      "values": {
        "sales": 83896,
        "refund": 0,
        "discount": 0,
        "material": 27871,
        "packaging": 0,
        "loss": 0,
        "costOther": 0,
        "rent": 0,
        "labor": 0,
        "utility": 0,
        "property": 0,
        "commission": 0,
        "promo": 0,
        "repair": 0,
        "equip": 0,
        "expOther": 0,
        "note": "Excel导入"
      }
    }
  ]
}
```

提交成功后复用 `FinanceService.save`，按当前登录用户的租户和权限写入 `profit_entry`。

## 后端模块设计

新增包：

```text
backend/src/main/java/com/storeprofit/system/importing/
```

建议类：

- `ProfitImportController`
- `ProfitImportService`
- `ProfitImportParser`
- `SpreadsheetProfitParser`
- `ScreenshotProfitRecognizer`
- `ProfitImportDraft`
- `ProfitImportRow`
- `ProfitImportCommitRequest`
- `ProfitImportRecognizeResponse`

职责划分：

- Controller：认证、上传参数、接口响应。
- Service：编排解析、校验、草稿缓存、提交保存。
- Spreadsheet parser：读取 Excel/CSV，结构化 sheet、行列、字段映射。
- Screenshot recognizer：压缩图片、调用 AI Gateway、解析 JSON。
- Validator：门店匹配、月份校验、金额范围、字段缺失、覆盖冲突。

## AI 调用设计

截图识别不直接在业务代码里写 DeepSeek 调用，使用可替换的 AI Gateway：

```text
ProfitImportService
  -> AiGateway
      -> DeepSeek / OpenAI / 未来私有模型
```

本阶段可以先复用现有 DeepSeek 配置：

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`
- `DEEPSEEK_MODEL`

但要把提示词和响应 schema 独立出来，避免散落在前端。

如果当前 DeepSeek 模型不支持图片输入，截图识别第一版采用两级策略：

1. 优先后端 OCR 或视觉模型接口。
2. 识别不可用时返回明确错误：`VISION_NOT_CONFIGURED`，前端提示用户改用 Excel 导入或手工录入。

## 字段映射

标准字段与当前利润录入字段一致：

| 标准字段 | 页面字段 | 后端字段 |
| --- | --- | --- |
| 营业总收入 | 营业总收入 | `sales` |
| 退款金额 | 退款金额 | `refund` |
| 优惠金额 | 优惠金额 | `discount` |
| 原材料成本 | 原材料成本 | `material` |
| 包材成本 | 包材成本 | `packaging` |
| 损耗成本 | 损耗成本 | `loss` |
| 其他成本 | 其他成本 | `costOther` |
| 房租 | 房租 | `rent` |
| 人工工资 | 人工工资 | `labor` |
| 水电费 | 水电费 | `utility` |
| 物业费 | 物业费 | `property` |
| 平台佣金 | 平台佣金 | `commission` |
| 推广费 | 推广费 | `promo` |
| 维修费 | 维修费 | `repair` |
| 设备费 | 设备费 | `equip` |
| 其他费用 | 其他费用 | `expOther` |

表格别名示例：

- 营业额、收入、流水、营业收入 -> `sales`
- 退款、退款金额、售后退款 -> `refund`
- 优惠、折扣、活动优惠 -> `discount`
- 物料、原料、原材料 -> `material`
- 包材、包装材料 -> `packaging`
- 损耗、报损 -> `loss`
- 人工、工资、员工工资 -> `labor`
- 佣金、平台费、平台佣金 -> `commission`

## 校验规则

识别结果必须经过校验：

- 金额字段为空时按 0 处理，但标记为低置信度。
- 金额不能为负；如原始表中是负数退款，应转换为正数退款金额。
- 月份必须是 `YYYY-MM`。
- 门店必须匹配当前用户可见门店。
- 如果上传数据的门店或月份与当前选择不一致，必须提示用户确认。
- 如果已有正式数据，默认标记为“覆盖冲突”，用户勾选覆盖后才能提交。
- 净利润、成本合计、费用合计只作为校验值，不直接保存；保存仍使用明细字段计算。

## 数据与审计

第一版可以不新增数据库表，导入草稿存在内存或前端状态中，提交时写入 `profit_entry` 和操作日志。

商业版建议新增表：

- `profit_import_job`
- `profit_import_row`

用于记录：

- 上传文件名、类型、大小、hash。
- 导入人、企业、时间。
- 识别状态。
- 行级错误和警告。
- 最终提交了哪些门店月份。

考虑当前项目阶段，推荐先做“无持久草稿”的实现，接口返回 `importId` 和 rows，前端保存当前草稿；提交时把 rows 发回后端。后续再补持久化导入任务。

## 安全要求

- 不在前端保存或展示任何 AI Key。
- 上传文件大小限制建议第一版为 10MB。
- 图片压缩后再上传，避免大图拖慢服务。
- 后端只接受登录用户可访问的门店数据。
- 导入保存必须走 `FinanceService.save`，继承现有租户隔离与权限校验。
- 操作日志记录导入来源和覆盖情况。

## 验收标准

- 上传企迈截图后，页面出现导入预览，不直接覆盖表单。
- 用户点击“应用到表单”后，金额字段填入，实时利润核算同步更新。
- 上传标准 Excel 后，能识别至少一行单店单月利润数据。
- 上传多店/月 Excel 后，能显示批量预览并保存勾选行。
- 已有数据时必须提示覆盖冲突。
- 未配置视觉 AI 时，截图识别给出清晰提示，不报前端异常。
- 浏览器网络面板不出现任何模型密钥。
- 后端构建通过，核心解析和校验有单元测试。

## 风险与对策

- Excel 格式不可控：先用别名映射和表头定位覆盖常见格式，低置信度行交给用户确认。
- 截图识别准确率不稳定：截图只作为辅助，不允许自动保存。
- 大文件导入慢：限制文件大小，后续再做异步任务。
- 老页面本地存储与后端存储并存：提交导入时优先调用后端 API，旧页面同步刷新本地视图。
