# 老板数据健康接口合同

日期：2026-07-08  
适用产品：AI Profit OS / 多门店经营异常处理系统  
前端入口：老板角色左侧菜单「数据健康」  
后端接口：`GET /api/boss/data-health`

## 1. 目标

数据健康页不能长期写死在前端。它必须优先读取后端真实状态，让老板知道每个模块当前数据是否已经进入 MySQL、是否还依赖兼容 KV、是否存在浏览器旧数据、是否尚未接入。

接口不可用时，前端允许显示前端静态兜底，避免页面白屏，但兜底状态必须明确标记为「前端静态兜底」。

## 2. 权限

- 仅老板/管理员可访问。
- 普通角色不应看到入口。
- 后端必须按登录身份校验，不允许只依赖前端隐藏菜单。

## 3. 请求

```http
GET /api/boss/data-health
Authorization: Bearer <token>
```

暂不需要查询参数。后续可扩展 `tenantId`、`brandId`、`module`，但第一版先返回当前租户全部关键模块。

## 4. 响应结构

```json
{
  "dataSource": "MySQL metadata / migration status",
  "lastUpdatedAt": "2026-07-08T11:00:00",
  "modules": [
    {
      "moduleName": "利润",
      "status": "KV",
      "dataSource": "entries 兼容存储，目标为 profit_monthly_report",
      "lastUpdatedAt": "2026-07-08T10:58:00",
      "businessScope": "营业额、成本、费用、净利率、利润异常",
      "migrationNote": "历史 entries 仍在兼容读取，真实写入需要逐步切到 MySQL。",
      "recommendation": "优先完成月度利润表结构化写入和迁移校验。"
    }
  ]
}
```

## 5. 模块字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `moduleName` | string | 是 | 模块名称，例如 `今日待办`、`利润`、`报销`、`巡店`、`仓库`、`工资`。 |
| `status` | string | 是 | `MYSQL`、`KV`、`BROWSER`、`NONE`。 |
| `dataSource` | string | 是 | 当前数据来源或目标表说明。 |
| `lastUpdatedAt` | string | 否 | 该模块状态最后刷新时间。 |
| `businessScope` | string | 否 | 该模块覆盖的业务数据范围。 |
| `migrationNote` | string | 是 | 当前迁移状态或风险说明。 |
| `recommendation` | string | 是 | 下一步处理建议。 |

## 6. 状态定义

| status | 前端文案 | 含义 |
| --- | --- | --- |
| `MYSQL` | MySQL结构化 | 已接入后端标准接口或结构化业务表。 |
| `KV` | 兼容KV | 迁移期仍有兼容 KV 或旧结构读取，真实写入必须继续落 MySQL。 |
| `BROWSER` | 浏览器旧数据 | 发现浏览器旧数据，只允许迁移读取，不能作为正式数据源。 |
| `NONE` | 未接入 | 该模块尚未接入结构化后端状态。 |

## 7. 第一版必须返回的模块

- 今日待办
- 利润
- 报销
- 巡店
- 仓库
- 工资

## 8. 前端兜底规则

接口不可用时：

1. 前端显示前端静态兜底。
2. 页面不白屏。
3. 页面仍显示「真实数据不存浏览器；虚拟数据不得入库」。
4. 兜底数据只用于展示产品要求，不代表真实系统状态。

## 9. 验收标准

1. 老板进入数据健康页时，前端会请求 `GET /api/boss/data-health`。
2. 后端正常返回时，页面显示接口返回的模块状态。
3. 后端不可用时，页面显示前端静态兜底。
4. 返回字段必须包含 `moduleName`、`status`、`dataSource`、`migrationNote`、`recommendation`。
5. 每个模块状态必须能解释为什么是这个状态，以及下一步该处理什么。
