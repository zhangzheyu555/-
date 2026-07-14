# 待办与考试 API 兼容边界

## 权威接口

| 业务 | 权威接口 | 说明 |
| --- | --- | --- |
| 业务待办 | `/api/todos` | 持久化待办的查询、详情、状态流转和附件下载入口。 |
| 考试培训 | `/api/exam-center` | 试卷、发布、分配、答题、阅卷、结果与学习资料的唯一写入口。 |

## 只读兼容入口

- `/api/boss/todo-dashboard` 仅保留老板工作台聚合展示。响应带 `Deprecation: true`，并通过 `Link` 指向 `/api/todos`；不得在该入口增加写操作。
- `/api/operations/exam-papers*` 与 `/api/operations/exam-attempts*` 的 `GET` 仅用于旧客户端只读兼容。响应带 `Deprecation: true`，并通过 `Link` 指向 `/api/exam-center` 对应入口。
- 旧 `POST /api/operations/exam-attempts` 已停止写入并返回 `410 Gone`。考试必须从 `/api/exam-center/assignments/{assignmentId}/submit` 提交，以执行考试分配、本人范围、时间窗和重复提交校验。

## 后续迁移

- `/api/boss/todos` 等角色待办聚合接口仍承载旧工作台的角色进度与上报老板流程。迁移前需先把这些操作映射到持久化 `business_todo` 状态机，不能直接删除。
- 旧考试查询响应模型与考试中心模型不同。确认没有旧客户端后，可删除 `/api/operations/exam-*` 兼容入口以及 `ExamAttemptRequest` 旧请求模型。
