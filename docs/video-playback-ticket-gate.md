# 视频播放票据 QA 拓扑门禁

本文件只规定隔离 QA 候选的视频播放票据拓扑，不包含 Redis 地址、密码、用户令牌、播放链接或任何真实环境值。

## 允许模式

`scripts/qa/Start-QAReleaseCandidate.ps1` 通过 `-ExpectedApplicationInstances` 和
`-VideoTicketMode` 记录候选拓扑：

| 候选拓扑 | 允许模式 | 后端行为 |
|---|---|---|
| 单实例 | `LOCAL_SINGLE_INSTANCE` | 使用本进程短期票据。不得把该结果当作多实例验收。 |
| 多实例 | `REDIS_SHARED` | 设置 `MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true`，仅从 QA 环境变量传入 Spring Redis 配置；Redis 不可用时应用拒绝启动。 |
| 多实例 | `STICKY_SESSION` | 仍使用本地短期票据，但仅限满足下方全部粘性会话约束并已记录该文档锚点的 QA 网关。 |

多实例使用 `LOCAL_SINGLE_INSTANCE` 会在启动前失败。`STICKY_SESSION` 必须传入一个仅文档锚点形式的 `-StickySessionConstraintReference`，例如
`docs/video-playback-ticket-gate.md#sticky-session-constraint`；脚本拒绝 URL、查询字符串和其他可能包含票据的引用。

先使用下列 Plan-only 命令确认门禁；它不读取 Redis 密码、不连接数据库，也不启动候选：

```powershell
# Redis 共享模式的多实例 QA 计划
& .\scripts\qa\Start-QAReleaseCandidate.ps1 `
  -JarPath .\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar `
  -MySqlDatabase ai_profit_qa_<batch> -MySqlUsername qa_release `
  -ExpectedApplicationInstances 2 -VideoTicketMode REDIS_SHARED

# 已由网关负责人确认粘性会话约束时的多实例 QA 计划
& .\scripts\qa\Start-QAReleaseCandidate.ps1 `
  -JarPath .\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar `
  -MySqlDatabase ai_profit_qa_<batch> -MySqlUsername qa_release `
  -ExpectedApplicationInstances 2 -VideoTicketMode STICKY_SESSION `
  -StickySessionConstraintReference docs/video-playback-ticket-gate.md#sticky-session-constraint
```

只有获得单独授权后，才能在上述参数之外追加 `-Apply -AuthorizeQaCandidateStart`。该授权不会替代 Redis 连通性和网关约束的实际验收。

## Redis 共享存储

执行受授权启动时，`REDIS_SHARED` 只从以下当前 Windows 用户或进程环境变量读取值，并且不写入命令行、控制台或证据 JSON：

- `QA_REDIS_HOST`
- `QA_REDIS_PASSWORD`

端口通过 `-RedisPort` 提供，默认 `6379`。脚本只把这些值映射为子进程的标准 Spring
Redis 环境变量；`TrainingVideoPlaybackTicketService` 会在应用接受流量前执行 Redis `PING`，不可用时拒绝启动，绝不回退到进程内存。
这个本地 QA 启动脚本只接受 loopback Redis，避免把未验证的远程端点误当成 QA；远程多实例部署须由受控部署系统提供等价的隔离证明，不能绕过本门禁。

## Sticky-session constraint

选择 `STICKY_SESSION` 前，QA 网关负责人必须在发布记录中确认：

1. 播放票据签发请求及同一浏览器随后所有 `/api/exam-center/videos/*/stream` Range 请求，在票据有效期内路由到同一应用实例；
2. 路由依赖同源、安全的会话亲和信息，不能依赖 `ticket` 查询参数，不能因为扩容、重启或健康检查切换而丢失亲和性；
3. 网关、负载均衡器、APM、访问日志和错误页均不记录请求查询字符串；
4. 验收至少覆盖首次 `200/206`、后续 Range、刷新、两个浏览器会话和节点切换的预期失败/恢复行为；
5. 上述网关配置和验收收据在 QA 发布证据中保存，但不保存任何完整播放 URL 或票据值。

若无法提供全部约束或验收收据，必须选择 `REDIS_SHARED`，或者停止多实例 QA 发布。

## 安全日志边界

QA profile 显式关闭 Tomcat access log 和 Spring MVC request-details。启动脚本只请求无查询参数的
`/api/health`，证据仅记录票据模式、实例数、非敏感文档锚点以及布尔标记；不得记录 Redis 值、授权头、播放 URL 或 `ticket` 查询参数。
