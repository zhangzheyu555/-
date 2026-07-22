# QMAI-01～QMAI-04 最终定向复验

- 检查时间：2026-07-21 CST
- 范围：本地进程内 H2、MockMvc、桌面 Chromium API Mock；未连接 QA MySQL、Docker、局域网或生产；未读取、输出或使用真实企迈凭证/token，未调用企迈或其他外网业务地址。
- 结论：**QMAI-01～QMAI-03 PASS（定向隔离证据）；QMAI-04 BLOCKED；因此 QMAI-01～QMAI-04 整组继续 BLOCKED。**

## 已通过的安全与权限边界

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| QMAI-01 凭证与品牌配置 | PASS（定向） | 凭证使用部署变量指定的 AES-GCM 密钥加密；读取只返回掩码/是否设置；默认出站禁用，`MOCK` 仅允许 loopback；保存配置写审计且不包含明文。平台配置管理仍限 BOSS/督导（历史 OPERATIONS 已按现行规则归一为 SUPERVISOR）。 |
| QMAI-02 营业额 | PASS（定向） | `/api/qmai/revenue` 与 `.csv` 只读取本地租户/品牌/门店快照，不出网；BOSS、SUPERVISOR、已授权 FINANCE 可读，其他角色由后端拒绝；查询/导出写审计，CSV 公式前缀转义。 |
| QMAI-03 商品销量 | PASS（定向） | `/api/qmai/products` 与 `.csv` 使用相同授权范围和审计；H2 验证跨租户、跨品牌及未授权门店均不会进入结果。 |
| 受限配方读取 | PASS（定向） | WAREHOUSE 仅可在 WAREHOUSE 数据范围读取既有的服务端配方快照；跨门店为 403 并写既有拒绝审计；匿名为 401。 |

## QMAI-04 阻断项

服务端已创建受租户、品牌约束的 `qmai_recipe_definition` 与 `qmai_recipe_ingredient` 目录，并使用 `BigDecimal` 从本地销量快照计算不可编辑的水果用量；浏览器不再提交或计算克重、出肉率、折算系数，旧浏览器内目录不再导入或执行。`/api/qmai/recipe-usage` 与 `.csv` 均只使用服务端目录和本地快照，并写查询/导出审计。

但迁移未、也不能安全地自动填入真实产品字段。当前缺少以下受控来源或管理契约：

1. `product_name`：按租户、品牌的企迈商品名与配方名称的权威映射；
2. `material_name`、`fruit_name`、`grams_per_cup`：每个配方的受控单杯用量；
3. `conversion_kind`、`conversion_factor`：出肉率/汁液换算的审批值；
4. 仅 BOSS/SUPERVISOR 可用、带审计的配方目录导入或维护接口/页面，以及审核后的来源文件。

不能从已停用的 `frontend-vue/src/data/fruitUsage.ts` 静态表推断、迁移或当作权威业务数据。因此生产/QA 目录会为空，虽然页面有安全空态，但无法完成“销量填充、用量测算”的真实闭环。该项是 QMAI-04 的唯一阻断项。

最小解锁动作：由业务提供经审核的配方目录（以上字段，按 tenant/brand），并授权实现受 BOSS/SUPERVISOR 硬上限保护的目录导入/维护流程及成功/拒绝审计；随后以本地 H2 合成目录和获准的隔离 QA 数据库目录重验 QMAI-04。

## 定向验证

| 命令/场景 | 结果 |
| --- | --- |
| `mvn -q -Dtest=QmaiSecurityAndRecipeTest,QmaiControllerAuthorizationTest test` | PASS：H2/Flyway、凭证加密、出站拒绝、租户/品牌/门店范围、BigDecimal 快照、BOSS/SUPERVISOR/FINANCE 查询、WAREHOUSE 只读快照、其他角色 403、匿名 401、成功与跨范围拒绝审计。 |
| `vue-tsc -b` | PASS。 |
| `playwright test tests/e2e/36-qmai-server-snapshot-desktop.spec.ts --project=chromium` | PASS（1/1）：1280×720，服务端快照请求带认证头、无浏览器可编辑配方输入、无横向溢出、无控制台/页面错误。 |

## 清理

- H2 数据库仅为进程内存并已随测试退出释放。
- Chromium 会话与 API Mock 已关闭；临时 Vite `18174` 已停止且无监听。
- 未生成持久化业务数据、真实凭证、QA 基线、Docker 资源或外网请求。
- 未修改 G3 台账，未提交代码。
