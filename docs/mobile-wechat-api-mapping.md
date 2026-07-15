# 微信小程序一期 API 映射

所有受保护调用使用 `Authorization: Bearer <token>`；401 清除仅有的本地 Token 并回登录，403 显示中文无权限提示，业务数据不回退到本地缓存。

| 小程序场景 | 正式接口 | 服务端保障 |
| --- | --- | --- |
| 会话与动态菜单 | `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout` | Token、role、permissions、dataScopes；未登录 401 |
| 店长库存/预警 | `GET /api/warehouse/overview` | 门店/仓库范围裁剪 |
| 叫货/收货 | `GET/POST /api/warehouse/requisitions`、`POST /{id}/receive` | `clientRequestId` 去重、行锁、操作日志 |
| 仓库发货 | `POST /api/warehouse/requisitions/{id}/ship` | `warehouse.requisition.process`、状态锁、操作日志 |
| 退货收货 | `GET /api/warehouse/returns`、`POST /api/warehouse/returns/{id}/receive` | 仓库范围、状态锁、操作日志 |
| 巡检与 AI 建议 | `/api/inspections/**`、`POST /api/storage/upload` | 标准/范围复核；AI 仅建议，人工确认后保存 |
| 整改与复核 | `GET /api/inspections/rectifications/mine`、`POST /{id}/rectification/evidence`、`POST /{id}/rectification`、`GET /rectifications/reviews`、`POST /{id}/rectification/review` | 附件归属、门店范围、状态机、操作日志；不重算历史分数 |
| 角色待办 | `/api/{boss|finance|operations|supervisor|store-manager|warehouse}/todos` | 后端按角色和 dataScope 聚合 |
| 学习/视频 | `/api/exam-center/videos/**` | 短时播放票据、会话回查、票据脱敏 |
| 考试 | `/api/exam-center/**` | 任务归属、行锁、重复交卷返回首次结果 |
| 员工助手 | `/api/employee-assistant/status`、`/chat` | 不暴露上游密钥；不可用时中文降级 |
| 只读摘要 | `/api/finance/**`、`/api/boss/**` | 权限及数据范围裁剪 |

审计结论：一期不需要移动数据副本、移动专用业务表或新的 `/api/mobile/workbench`。现有角色待办及业务接口能够提供聚合和闭环；后续仅在现有接口不足以表达只读聚合时增加最小只读接口。
