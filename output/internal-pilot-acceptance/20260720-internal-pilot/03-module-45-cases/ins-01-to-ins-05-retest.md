# INS-01～INS-05 阻断修复与隔离重验

执行批次：`INS-01-05-H2-DESKTOP-RETEST-20260721`
结论：`PASS（本子任务范围）`

## 隔离与边界

- 仅使用 Flyway H2 内存库、Playwright API Mock 与本机回环 Vite 预览；未连接 QA MySQL、Docker、局域网或生产环境。
- 仅执行 Chromium 桌面项目，统一 `1280×720`；未测试移动端，也未安装或调用 WebKit。
- 本子任务按并行协调约定未执行后端全量 Maven、后端打包、`vue-tsc -b` 或生产构建；这些共享工作区验证由主线程串行汇总。
- 临时 Vite 使用 `127.0.0.1:18174`，回归结束后已关闭；H2、浏览器会话、Mock 数据和审计均为进程内资源，不留持久化 QA 数据。

## 修复与直接关联测试

| 原阻断项 | 处理 | 证据 |
| --- | --- | --- |
| 标准摘要断言不稳定 | 摘要保留统一的 `105条（物料40 / 卫生47 / 服务18）· 200分 · 合格线180分` 文案；测试改为限定摘要容器，避免跨页面文本匹配。 | `InspectionStandardReadinessNotice.vue`、`14-inspection-standard-export.spec.ts` |
| `2026.07-R1` 严格定位多命中 | 将断言限定到巡检详情指标网格。 | `14-inspection-standard-export.spec.ts` |
| 拍照/选图点击高度 36px | 巡检工作台上传按钮显式设为最小 44px。 | `SupervisorWorkbenchPage.vue` |
| 未关联 AI 图片附件断言 | 未关联图片仍不请求原图，避免仅凭元数据读取附件；已关联图片继续走受认证 Blob API，并断言 `Authorization`。 | `18-inspection-detection-confirm.spec.ts` |
| 整改/复核桌面与 H2 覆盖不足 | 新增店长整改提交与督导复核 1280px Chromium 回归；新增 H2 工作流测试。 | `35-inspection-rectification-desktop.spec.ts`、`InspectionRectificationWorkflowH2Test.java` |

## 通过证据

| 验证 | 结果 | 摘要 |
| --- | --- | --- |
| 整改定向后端 | PASS | 9/9，0 failure/error：既有提交/控制器拒绝及新增 H2 成功复核、复核人/时间/结论、成功审计、二次复核不可覆盖、跨店 403、跨租户不可见。 |
| H2 审计 | PASS | 通过复核写 `inspection_rectification_action(APPROVED)` 与 `operation_log(inspection_rectification_review_approved)`；第二次复核仅返回 `RECTIFICATION_STATE_CONFLICT`，不覆盖首个复核备注或新增动作。 |
| 跨范围 | PASS | 跨店在状态更新前被拒绝且流程保持 `PENDING_REVIEW`；其他租户记录不在认证租户查询结果中、状态不变、认证租户无操作日志。匿名与角色 HTTP 拒绝由既有 `InspectionRectificationControllerTest` 覆盖。 |
| Chromium 桌面 | PASS | 13/13，0 failure：标准摘要、版本定位、已关联附件 Authorization、未关联证据不读取原图、拍照按钮 ≥44px、店长整改提交、督导通过复核、1280px 无横向溢出、零未处理页面错误。 |

## 审计与安全结论

- 店长整改证据上传和提交，以及督导通过/驳回复核，继续使用既有受认证 API 与操作日志路径；没有放宽角色、门店或租户范围。
- 未关联 AI 建议不获取原始附件，避免将“未计分/未关联”元数据升级为原图读取权限；已关联附件的回归明确验证 `Bearer` 认证头。
- 重复复核由 `PENDING_REVIEW` 条件更新与状态检查共同保护；H2 测试证明首个结果不可被后续请求覆盖。

## 残留与交接

- 本报告不替代主线程的共享全量测试、打包与前端构建结论。
- 未执行真实 QA MySQL 登录态演练；按本轮约束，此项不作为 INS-01～INS-05 的本地 H2/Mock 门禁阻断。
- 未修改 `g3-gate-ledger.md`，未提交代码，也未进入后续业务模块。
