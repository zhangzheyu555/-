# 角色待办生命周期接口合同

日期：2026-07-08  
适用产品：AI Profit OS / 多门店经营异常处理系统  
适用范围：老板、财务、督导、店长、仓库管理员、运营角色的今日待办接口

## 1. 目标

今日待办不是前端自己猜出来的提醒，而是后端根据真实 MySQL 业务数据生成的角色工作入口。前端只负责展示、筛选和跳转；待办是否存在、优先级、负责人、截止时间、处理状态、是否上报老板，都必须由后端明确返回。

## 2. 分角色接口

不使用统一 `/api/todos`。每个角色必须有独立接口，便于后端做权限、门店范围和业务规则隔离。

- 老板：`GET /api/boss/todos`
- 财务：`GET /api/finance/todos`
- 督导：`GET /api/supervisor/todos`
- 店长：`GET /api/store-manager/todos`
- 仓库管理员：`GET /api/warehouse/todos`
- 运营：`GET /api/operations/todos`

## 3. 请求参数

所有接口支持同一组查询参数。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `includeDone` | 否 | 默认 `false`。为 `true` 时返回已处理事项。 |
| `status` | 否 | `RISK`、`PENDING`、`REMINDER`、`DONE`。 |
| `limit` | 否 | 默认建议 `50`。 |
| `brandId` | 否 | 老板、财务、督导、仓库、运营可用。店长接口必须忽略前端传入的品牌筛选。 |
| `storeId` | 否 | 老板、财务、督导、仓库、运营可用。店长接口必须以后端登录身份绑定门店为准。 |

## 4. 响应结构

```json
{
  "roleName": "老板",
  "dataSource": "MySQL结构化数据 / 后端标准接口",
  "updatedAt": "2026-07-08T10:30:00",
  "stats": [
    {"status": "RISK", "count": 2},
    {"status": "PENDING", "count": 5},
    {"status": "REMINDER", "count": 3},
    {"status": "DONE", "count": 0}
  ],
  "aiSummary": {
    "source": "DEEPSEEK",
    "text": "今天先看荆州之星店净利率异常和仓库缺货风险。",
    "fallbackReason": ""
  },
  "items": [
    {
      "id": "profit-risk-rg1-2026-07",
      "title": "荆州之星店净利率低于目标",
      "summary": "2026-07 净利率低于目标线，需要查看费用和收入明细。",
      "status": "RISK",
      "priority": 10,
      "brandName": "茹果奶茶",
      "storeId": "rg1",
      "storeName": "荆州之星店",
      "month": "2026-07",
      "ownerName": "财务",
      "dueAt": "2026-07-08T18:00:00",
      "sourceModule": "利润表",
      "sourceRecordId": "rg1|2026-07",
      "processStatus": "待财务核对",
      "escalatedToBoss": false,
      "dataSource": "profit_entry",
      "updatedAt": "2026-07-08T10:30:00",
      "occurredAt": "2026-07-08T09:00:00",
      "action": {
        "target": "report",
        "label": "查看利润表",
        "params": {
          "storeId": "rg1",
          "month": "2026-07",
          "mode": "single"
        }
      }
    }
  ]
}
```

## 5. `items` 标准字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 待办唯一 ID。必须稳定，不能每次刷新变化。 |
| `title` | string | 是 | 人能看懂的一句话标题。 |
| `summary` | string | 是 | 为什么需要处理。 |
| `status` | string | 是 | `RISK`、`PENDING`、`REMINDER`、`DONE`。 |
| `priority` | number | 是 | 数字越大越靠前。红色风险优先级最高。 |
| `brandName` | string | 否 | 品牌名。 |
| `storeId` | string | 否 | 门店 ID。 |
| `storeName` | string | 否 | 门店名称。 |
| `month` | string | 否 | 业务月份，格式 `YYYY-MM`。 |
| `ownerName` | string | 是 | 当前处理责任人或责任角色。 |
| `dueAt` | string | 是 | 截止时间，ISO 字符串。 |
| `sourceModule` | string | 是 | 来源模块，例如 `利润表`、`报销`、`督导巡店`、`仓库叫货`。 |
| `sourceRecordId` | string | 是 | 来源业务记录 ID，用于追溯。 |
| `processStatus` | string | 是 | 当前处理状态，例如 `待财务核对`、`待仓库配货`、`店长整改中`。 |
| `escalatedToBoss` | boolean | 是 | 是否已经上报给老板。 |
| `dataSource` | string | 是 | 后端数据来源表或服务名。 |
| `updatedAt` | string | 是 | 待办生成或刷新时间。 |
| `occurredAt` | string | 否 | 异常发生时间。 |
| `action` | object | 是 | 跳转目标。今日待办只跳转，不直接处理复杂业务。 |

## 6. 状态定义

| 状态 | 前端颜色 | 含义 |
| --- | --- | --- |
| `RISK` | 红色 | 影响利润、合规、安全或老板需要关注的风险。 |
| `PENDING` | 橙色 | 当前角色必须处理的工作。 |
| `REMINDER` | 蓝色 | 需要知道或跟进，但不一定马上处理。 |
| `DONE` | 灰色 | 已处理事项，默认隐藏。 |

## 7. 跳转目标

`action.target` 必须指向现有功能页。前端会按目标带上筛选上下文。

| target | 页面 |
| --- | --- |
| `report` | 利润表 |
| `detail` | 门店详情 |
| `entry` | 数据录入 |
| `expense` | 报销栏 |
| `inspect` | 督导巡店 |
| `warehouse` | 仓库中心 |
| `salary` | 员工工资 |

## 8. 权限要求

- 店长接口必须以后端登录身份绑定门店为准，忽略前端传入的其他 `storeId`。
- 老板可以查看所有门店和所有已上报事项。
- 财务只返回财务需要处理的录入、报销、工资、利润核对事项。
- 督导只返回巡店、整改、复查相关事项。
- 仓库管理员只返回叫货、库存、采购、配货相关事项。
- 前端的角色预览不能替代后端权限校验。

## 8.1 上报老板

财务、督导、仓库管理员可以把自己角色待办上报给老板：

- `POST /api/finance/todos/{todoId}/escalate`
- `POST /api/supervisor/todos/{todoId}/escalate`
- `POST /api/warehouse/todos/{todoId}/escalate`

请求体必须包含 `reason`，并可带 `severity`：`RISK` 或 `PENDING`。后端会写入 `todo_escalation`，老板待办接口再把未处理上报记录展示为老板事项。前端必须使用 `encodeURIComponent(todoId)` 拼接路径，并在提交前要求填写上报原因。

## 9. 前端兼容期 fallback

迁移期前端会短期兼容旧字段，但后端新实现必须优先返回标准字段。

| 标准字段 | 临时兼容字段 |
| --- | --- |
| `ownerName` | `ownerUser`、`owner`、`ownerRole` |
| `dueAt` | `deadline`、`deadlineAt`、`expireAt` |
| `sourceModule` | `sourceName`、`module`、`moduleName` |
| `sourceRecordId` | `recordId`、`businessId`、`sourceId` |
| `processStatus` | `workflowStatus`、`statusText`、`todoStatus` |
| `escalatedToBoss` | `escalated`、`isEscalated`、`escalationStatus` |
| `dataSource` | `source`、`dataOrigin` |
| `updatedAt` | `generatedAt`、`refreshedAt`、`lastUpdatedAt` |

## 10. 验收标准

1. 每个角色接口都能返回 `stats` 和 `items`。
2. 每条待办都有负责人、截止时间、来源模块、来源记录、处理状态、上报状态。
3. 店长只能看到自己的门店，且后端强制限制。
4. 老板只看到利润异常和已上报事项，不接管财务、督导、仓库可以自行处理的问题。
5. 今日待办页面只负责跳转和追踪，真正处理必须到对应业务页面完成。

## 11. 2026-07-08 实现记录

后端第一版已补齐 6 个分角色接口：`/api/boss/todos`、`/api/finance/todos`、`/api/supervisor/todos`、`/api/store-manager/todos`、`/api/warehouse/todos`、`/api/operations/todos`。

当前接入的真实数据源为 `inspection_record`、`store_requisition`、`profit_entry`、`expense_claim` 和 `todo_escalation`。未通过巡店生成 `RISK` 待办；待审核或待配货叫货单生成 `PENDING` 待办；利润异常生成财务 `RISK` 待办；待审核报销生成财务 `PENDING` 待办；角色上报记录会进入老板待办。利润异常初始 `escalatedToBoss=false`，避免把“需要财务核对”和“已经上报老板”混淆。运营暂不生成虚拟数据，工资待核对需等 `salary_record` 增加审核状态字段后再接入。
