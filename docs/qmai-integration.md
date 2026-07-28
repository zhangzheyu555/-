# 企迈经营数据接入说明

## 接入范围

- 使用固定的企迈开放平台接口读取授权门店的商品营业额。
- 数据按“租户 × 品牌 × 门店 × 营业日”同步到 MySQL，再供页面查询和导出。
- 支持整月汇总，也支持按单个营业日查看营业额和商品销售。
- 历史月份可手动补取；当前月份最多补到上海时区的昨天。
- 每天上海时间 05:00 自动同步昨天的数据，月报由每日快照汇总。
- 同一门店、同一营业日重复同步会事务性替换，不会重复累计；失败时保留上一次成功快照。
- 企迈配置界面提交的凭证只在后端处理：写入 MySQL 前使用 AES/GCM 加密，数据库只保存
  `enc:v1:` 密文；接口只返回掩码和是否已配置，绝不返回明文。

## 凭证与部署配置

企迈凭证可由后端部署环境提供，也可由具备平台管理权限的人员在页面保存。页面保存的
openId、grantCode、openKey 和控制台令牌均使用独立 AES 密钥加密后落库，读取接口只返回
脱敏状态。

企迈配置界面保存凭证前，后端必须配置系统主密钥 `QMAI_CREDENTIAL_ENCRYPTION_KEY`。
它必须是 Base64 编码的 16、24 或 32 字节随机数据，生产环境使用 32 字节 AES-256。
它不是企迈的 `openKey`，只用于本系统加密和解密数据库内的凭证。服务器配置、备份、
重启验证和密文检查见 [企迈凭证加密密钥运维](qmai-credential-encryption-key-operations.md)。

```text
QMAI_OUTBOUND_MODE=LIVE
QMAI_CREDENTIAL_ENCRYPTION_KEY=<独立的 Base64 AES 密钥>
QMAI_DAILY_SYNC_ENABLED=true
QMAI_DAILY_SYNC_CRON=0 0 5 * * *
QMAI_RECIPE_ENABLED=true
```

可选的环境回退配置：

```text
QMAI_OPEN_ID=<企迈 openId>
QMAI_GRANT_CODE=<企迈 grantCode>
QMAI_OPEN_KEY=<企迈 openKey>
QMAI_BASE_URL=https://openapi.qmai.cn
QMAI_VERSION=1.0
QMAI_TIMEOUT=20s
QMAI_SHOPS=<shopId:门店名:系统storeId，多个用逗号分隔>
```

密钥和凭证不得提交到仓库或下发前端。真实企迈调用必须显式设置
`QMAI_OUTBOUND_MODE=LIVE`。

## 配置与使用顺序

1. 部署人员在服务器为后端注入 `QMAI_CREDENTIAL_ENCRYPTION_KEY` 并重启后端。
2. 老板或督导进入“平台接入”，打开企迈配置。
3. 保存企迈凭证，并将每家企迈门店映射到唯一的系统门店。格式为
   `shopId:门店名:系统storeId`。
4. 选择月份，点击“补取本月历史数据”。
5. 页面轮询批次进度；任务完成后自动读取已落库数据。
6. 在“上一月”按钮后的“历史日期”筛选栏选择某一天，可查看和导出该日数据；
   此时补取按钮会变为“补取所选日期”，只修复当天，不会重拉整月。
7. 清空历史日期或点击“查看整月”，恢复整月汇总。

## 物料用量口径

- 配方目录来自《单杯用量(2).xls》`Sheet3` 的“单杯用量”；水果出成率和果汁折算系数沿用已核准的原物料口径。
- 企迈商品先跨授权门店按原商品名汇总；费用、包材、加料、零食和水果预定等非饮品不参与配方匹配。
- 同一商品的中/大杯以及 500/1000ml 配方按 1:1 分匀，先求出统一单杯用量，再乘企迈总杯数；不再把无规格销量全部套到中杯。
- 匹配时去除空格、常见标点、规格和“茹菓…默认”包装文字，并兼容企迈历史商品名（如“牛油果追芒芒”对应表内“牛油果芒果”）；未配置的商品仍保持未匹配。
- 果肉用量按“净重 ÷ 出成率”折算采购毛重；果汁按配方折算系数相乘；缺少出成率的水果按 1:1 并标记为估算。
- 椰奶、鲜奶、茶、糖、蒟蒻等非水果物料直接按“表内单杯 g/ml × 杯数”汇总；页面统一换算为千克/升展示。
- 未匹配到配方的企迈饮品不参与测算，并在页面和 CSV 中单独列出，禁止静默套用其他配方。
- 配方和换算系数只保存在服务端 MySQL。当前茹菓目录可用
  `backend/scripts/qmai-ruguo-recipe-catalog.sql` 按明确租户导入，不作为 Flyway 示例数据自动扩散。

物料用量接口：

- `GET /api/qmai/recipe-usage?brand=ruguo&month=YYYY-MM`
- `GET /api/qmai/recipe-usage.csv?brand=ruguo&month=YYYY-MM`

## 同步接口

- `POST /api/qmai/sync/backfill?brand=ruguo&month=YYYY-MM`：补取一个自然月。
- 上述接口增加 `businessDate=YYYY-MM-DD`：只修复一个已结束的营业日。
- `GET /api/qmai/sync/batches/latest?brand=ruguo&month=YYYY-MM`：查询月份最新批次。
- `GET /api/qmai/sync/batches/{batchId}?brand=ruguo`：查询指定批次。
- `GET /api/qmai/revenue`、`GET /api/qmai/products` 及对应 CSV：支持可选
  `businessDate=YYYY-MM-DD`。

## 权限边界

- 平台配置和手动补取：具备 `platform.manage` 的老板、督导。
- 营业额和商品销售查询：老板、督导、财务，并继续应用平台门店数据范围。
- 营业额和商品销售导出：仅老板、财务，必须具备 `finance.export`，继续应用门店范围并写操作日志。
- 配方用量快照：老板、督导和具备库存读取权限的仓库账号，继续应用企迈门店范围并写操作日志。
- 未登录返回 401；无权限或跨门店访问返回 403。

## 数据表

- `qmai_platform_config`：按租户、品牌保存 AES/GCM 密文凭证和授权门店配置，是门店映射
  唯一配置源；敏感字段以 `enc:v1:` 开头，不保存可用明文。
- `qmai_store_mapping`：配置保存时在同一事务中刷新的企迈门店—系统门店一对一镜像。
- `qmai_sync_batch`：异步批次、任务进度、失败数和错误摘要。
- `qmai_daily_sales`：门店日营业额、成本和退款快照。
- `qmai_product_sales`：门店日商品销售快照。
- `qmai_operating_sync_lease`：按租户、品牌控制多实例同步互斥。
- `qmai_recipe_definition`、`qmai_recipe_ingredient`：按租户和品牌保存服务端受管配方、单杯克重与换算方式。

## 数据安全与一致性

- 企迈 shopId 不能代替系统 storeId；映射缺失或无效时拒绝落库。
- 数据库已有配置行时，即使门店清单为空也不会回退环境变量中的旧映射。
- 外部接口所有分页成功后，才在一个数据库事务中替换该门店当天的数据。
- 企迈响应结构或金额字段异常时整日失败并保留旧快照；只有明确的空列表才会保存零值日快照。
- 成功的空数据会保存零值日快照并清理旧商品；接口失败不会清空旧数据。
- 凌晨任务只枚举数据库中明确配置的租户和品牌，禁止把默认凭证扩散到其他租户。
- 诊断接口只允许登记的只读路径，写入不含参数和凭证的审计日志，不参与自动同步。
