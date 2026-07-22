# G4 最小合成夹具候选 API 只读审计

审计时间：`2026-07-21`
范围：仅阅读候选后端的 Controller、DTO、服务授权边界、Flyway 和既有 H2 测试；未登录、未调用 HTTP API、未连接数据库、未写入任何 QA 数据。

## 结论

在以下两个已知前置成立后，FLOW-01/02/03 所需的**业务夹具和流程记录**可经候选 API 建立，无须直接写入业务流程表：

1. 受控本地 QA 的 BOSS 认证恢复可用；当前 `flow-01-qa-acceptance.md` 已记录该认证前置仍失败，故本审计不把可行路径写成已执行证据。
2. 保留已获准的合成品牌基线，或另行批准提供同等受控的品牌引导。`BrandController` 只有读取接口，不能通过候选 API 新建品牌；已有 G4 合成品牌基线可用时，从 `GET /api/brands` 读取其 ID 即可。

仓库拓扑不需要手工夹具：Flyway V43 已为每个租户建立“荆州总仓 / 山东分仓”及总仓到直属分仓的路线。供应商也不是阻断项：采购请求的 `supplierId` 可省略，服务会接受 `null`。

## 公共会话和只读定位

所有下列受保护请求均使用本地 QA 服务返回的 `Authorization: Bearer <QA 临时令牌>`；令牌和密码不应记录在命令、报告或对话中。

| 用途 | 方法与端点 | 最低请求/参数 | 角色与边界 |
| --- | --- | --- | --- |
| 建立会话 | `POST /api/auth/login` | `username`、`password`、`tenantId`（本基线为 `1`） | 仅本地 QA 临时账号；成功体中的令牌只存进进程变量/私有临时文件。 |
| 定位品牌 | `GET /api/brands` | 无 | BOSS；读取已批准的合成品牌 ID。不存在品牌创建 API。 |
| 定位仓库 | `GET /api/warehouse/warehouses` | 无 | BOSS 或已授权 WAREHOUSE；读取两个仓库 ID，不假设自增值。 |
| 定位物料/批次/采购结果 | `GET /api/warehouse/overview?warehouseId=<id>`、`GET /api/warehouse/items` | `warehouseId` 为已定位 ID | 仅在该账号数据范围内；返回物料、采购单、批次、流水、预警。 |

## 受控基础夹具 API

| 夹具 | 方法与端点 | 最低 JSON 字段 | 角色与不可绕过约束 |
| --- | --- | --- | --- |
| 门店 A（可选门店 B 用于范围拒绝） | `POST /api/stores` | `id`、`name`、`brandId`、`regionCode`、`status`；`code` 建议唯一 | **仅 BOSS**。`regionCode` 只能是 `JINGZHOU` 或 `SHANDONG`；`status` 为启用时，服务自动根据区域绑定供货仓。不得发送/指定 `supplyWarehouseId`。 |
| 店长 A | `POST /api/users` | `username`、`displayName`、`role:"STORE_MANAGER"`、`storeId`、`storeScope:[同一门店]`、`password` | **仅 BOSS**。店长必须且只能绑定一店；创建时自动写受限的本店数据范围。 |
| 总仓仓库员 | `POST /api/users`，随后 `PUT /api/users/{id}/access-profile` | 创建字段同上，`role:"WAREHOUSE"`；档案更新含 `displayName`、`role`、`storeId:null`、`storeScope:[]`、`enabled:true`、`overrides:[]`、`dataScopes` | **仅 BOSS**。将 `WAREHOUSE` 域设为 `WAREHOUSE_LIST` 且 `warehouseIds:[<荆州总仓>]`；其他域显式 `NONE`。新建 WAREHOUSE 若不配置，会落到保守的仅总仓兼容范围。 |
| 山东分仓仓库员 | 同上 | 同上 | **仅 BOSS**。`WAREHOUSE` 域改为 `WAREHOUSE_LIST` 且仅含 `<山东分仓>`；用于申请/提交/收货，避免以全范围账号掩盖授权。 |
| 物料类别 | `POST /api/warehouse/item-categories` | `name` | BOSS 或有 `warehouse.configure` 的 WAREHOUSE；返回类别 ID。 |
| 物料 | `POST /api/warehouse/items` | `code`、`name`，以及 `categoryId` 或 `category`；建议同时给 `unit`、`stockUnit`、`purchaseUnit`、`unitPrice`、`active:true` | BOSS 或有 `warehouse.configure` 的 WAREHOUSE。物料 ID 通过后续 `GET /api/warehouse/items` 读取；不得直写库存表。 |

`access-profile` 的每项范围对象字段固定为 `domainCode`、`mode`、`storeIds`、`warehouseIds`。该接口是全量替换：遗漏域会写为 `NONE`；不可把仓库员设置成 `ALL` 来省略范围验证。

## FLOW-01：店长叫货 → 仓库审核/发货 → 店长收货

| 顺序 | 方法与端点 | 最低 JSON 字段 | 执行账号 |
| --- | --- | --- | --- |
| 1 | `POST /api/warehouse/requisitions` | `storeId`、`lines:[{itemId,requestedQuantity}]`、`clientRequestId`；可选 `note` | 店长 A；门店必须是其本店且已由区域自动绑定供货仓。 |
| 2 | `POST /api/warehouse/requisitions/{id}/review` | `approved:true`；`lines` 可为空（默认按申请数） | 与该店供货仓匹配的仓库员；需 `warehouse.requisition.process` 和该仓范围。 |
| 3 | `POST /api/warehouse/requisitions/{id}/ship` | 无请求体 | 同上；要求申请已经 APPROVED 且供货仓库存已由采购入库建立。 |
| 4 | `POST /api/warehouse/requisitions/{id}/receive` | 可为空；建议 `{ "note": "合成 QA 收货" }` | 店长 A；只能本店，且仅 SHIPPED 后可收货。重复收货应不重复入库。 |
| 5 | `GET /api/warehouse/print/requisitions/{id}/delivery` | 无 | 仓库员/BOSS；验证 PDF 下载和下载审计。 |

库存来源不能使用 `POST /api/warehouse/stock-batches`：拓扑服务启用时该端点固定返回 `DIRECT_STOCK_RECEIVE_DISABLED`。必须先走 FLOW-02 的采购入库链建立总仓批次与库存。

## FLOW-02：采购 → 审批/入库 → 库存、预警、单据

| 顺序 | 方法与端点 | 最低 JSON 字段 | 执行账号 |
| --- | --- | --- | --- |
| 1 | `POST /api/warehouse/purchase-orders` | `warehouseId:<荆州总仓>`、`clientRequestId`、`lines:[{itemId,orderedQuantity,unitCost}]`；`supplierId` 可省略，`note` 可选 | 具备总仓范围及 `warehouse.purchase` 的仓库员/BOSS。 |
| 2 | `POST /api/warehouse/purchase-orders/{id}/approve` | 无 | 同一总仓仓库员/BOSS；仅 DRAFT 可审批。 |
| 3 | `POST /api/warehouse/purchase-orders/{id}/receive` | `clientRequestId`、`lines:[{itemId,batchNo,receivedDate,quantity}]`；可选 `expiryDate`、`note` | 同一总仓仓库员/BOSS；明细必须覆盖采购单所有物料且数量完全相同。 |
| 4 | `GET /api/warehouse/overview?warehouseId=<荆州总仓>` | `warehouseId` | BOSS 或总仓范围仓库员；核对采购单、批次、库存、流水、预警。 |
| 5 | `GET /api/warehouse/print/receipts/{batchId}` | `batchId` 来自概览 | 仓库员/BOSS；验证入库 PDF 与下载审计。 |

**财务注意事项：**当前正式矩阵明确 FINANCE 无仓库管理能力，现有 FINANCE 模板也不保证有 `warehouse.read`。因此 FLOW-02 的采购/库存/PDF 正向读取证据应由 BOSS（或授权 WAREHOUSE）产生；若要以 FINANCE 做正向读取，必须先确认这是新业务授权并经审批，不能以个人 ALLOW 绕过角色边界。财务可作为拒绝路径验证对象。

## FLOW-03：两条独立闭环

### A. 仓间调拨（不混用配送退货）

| 顺序 | 方法与端点 | 最低 JSON 字段 | 执行账号 |
| --- | --- | --- | --- |
| 1 | `POST /api/warehouse/transfers` | `sourceWarehouseId:<荆州总仓>`、`targetWarehouseId:<山东分仓>`、`lines:[{itemId,quantity}]`、`clientRequestId` | 山东分仓仓库员；创建授权只要求目标仓范围，但服务强制“总仓 → 直属分仓”路线。 |
| 2 | `POST /api/warehouse/transfers/{id}/submit` | 可空；建议 `clientRequestId`、`note` | 山东分仓仓库员；仅 DRAFT。 |
| 3 | `POST /api/warehouse/transfers/{id}/review` | `approved:true` | 总仓仓库员；仅 SUBMITTED，审批时预占来源库存。 |
| 4 | `POST /api/warehouse/transfers/{id}/ship` | 可空；建议 `clientRequestId`、`note` | 总仓仓库员；仅 APPROVED，验证源仓扣减、目标仓在途增加和成本守恒。 |
| 5 | `POST /api/warehouse/transfers/{id}/receive` | 可空可全收；建议 `clientRequestId`、`lines:[{itemId,receivedQuantity}]`、`note` | 山东分仓仓库员；仅 SHIPPED/部分收货，验证在途转实收及批次。部分收货必须有 `clientRequestId`。 |

现有候选 API 未实现“调拨收货后反向退回”端点；已按方案（2）以收货为仓间闭环终点。

### B. 配送退货（独立于仓间调拨）

先完成 FLOW-01 的店长收货，才能产生可退的来源叫货、配送单和门店库存。

| 顺序 | 方法与端点 | 最低 JSON 字段 | 执行账号 |
| --- | --- | --- | --- |
| 1 | `POST /api/warehouse/returns` | `sourceRequisitionId`、`lines:[{itemId,quantity}]`；建议 `returnStoreId`、`reason`、`returnDate`、`note`、`attachments` | 店长 A；只可基于本店已发货/已收货叫货单，退货量不得超过原单可退量及本店实存。 |
| 2 | 同一创建请求的 `attachments` | 每项 `fileName`、`contentType`、`dataBase64` | 仅合成、非敏感、小尺寸附件；服务存为退货附件。 |
| 3 | `POST /api/warehouse/returns/{returnId}/review` | `approved:true`；可选 `note` | 供货仓仓库员/BOSS；店长不能审核。 |
| 4 | `POST /api/warehouse/returns/{returnId}/receive` | 可空；建议 `note` | 同一供货仓仓库员/BOSS；完成仓库退货入库。 |
| 5 | `GET /api/warehouse/print/returns/{returnId}` | 无 | 仓库员/BOSS；验证退货 PDF 与下载审计。 |

## 不可自行越过的边界

- 不得以 SQL 直接插入 `auth_user`、`store_branch`、库存、采购、叫货、调拨、退货或操作日志，来替代候选 API 的权限和审计证据。
- 品牌不存在时，只有两条合规路径：已批准的合成品牌引导/基线，或新增 BOSS 限定、租户隔离且审计完备的品牌维护 API；本轮不实施该功能。
- 每个写操作的 `clientRequestId` 必须使用本次 QA 专属、不含真实数据的唯一值；采购入库和调拨创建为强制，部分调拨收货也为强制。
- 每个流程必须在完成后用只读 API/受控 SQL 核验，再依据 QA 清理清单删除或恢复本次合成夹具；本审计未执行任何一步。

## 代码依据

- `backend/src/main/java/com/storeprofit/system/warehouse/WarehouseController.java`
- `backend/src/main/java/com/storeprofit/system/warehouse/WarehouseService.java`
- `backend/src/main/java/com/storeprofit/system/warehouse/WarehouseNetworkService.java`
- `backend/src/main/java/com/storeprofit/system/warehouse/WarehouseTopologyService.java`
- `backend/src/main/java/com/storeprofit/system/organization/StoreController.java`
- `backend/src/main/java/com/storeprofit/system/organization/OrganizationService.java`
- `backend/src/main/java/com/storeprofit/system/platform/users/UserController.java`
- `backend/src/main/java/com/storeprofit/system/platform/users/UserManagementService.java`
- `backend/src/main/resources/db/migration/V43__multi_warehouse_topology.sql`
