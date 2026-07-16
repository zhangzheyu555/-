# 代码优化路线图

状态：进行中。目标是在不改变业务权限、评分、库存流水和审计行为的前提下，逐步把巨型页面与后端服务拆为可验证的边界。

## 基线与约束

- `frontend-vue` 和 `backend` 是唯一正式应用；历史静态目录与迁移层不能作为新功能入口。
- 当前工作树存在未提交业务改动。每一批优化必须独立、可构建、可回滚；不执行 `git reset`、`git clean` 或批量删除。
- 不改变既有 Flyway 迁移，不迁移或清空真实业务数据，不重启线上服务。
- 页面拆分优先提取纯计算、展示组件和组合式函数；接口调用、权限判断、事务与审计日志必须保持原有行为后再分别收敛。

## 已落地：巡检纯规则边界

`frontend-vue/src/utils/inspectionDraft.ts` 统一承载以下无网络、无页面状态的规则：

- 品牌规范化与展示色。
- 200 分制的安全数值、扣分和条款整改状态计算。
- 红线切换、巡检前后证据选择。
- 巡检记录的排序。

`SupervisorWorkbenchPage.vue` 继续保留路由同步、接口请求、草稿状态、上传、识别确认和模板布局，避免在第一批重构中改变业务行为。

第二批已新增 `frontend-vue/src/composables/useInspectionDraft.ts`：草稿初始化、门店/品牌选择、标准条款装配、200 分制预览、保存前阻断校验和草稿重置已从页面移出。页面继续负责接口调用、上传、保存事务、路由和业务提示。

第三批已新增 `frontend-vue/src/components/inspection/InspectionHistoricalEvidencePanel.vue`：未关联现场证据、历史证据补传入口和 AI 待确认展示已移出巨型页面。页面仍保留附件 Blob 获取、权限判断、历史条款关联、人工确认计分及弹窗焦点管理，因此不会改变证据归属、评分或审计边界。

第四批已新增 `frontend-vue/src/components/inspection/InspectionRecordSnapshotTable.vue`：历史条款快照、已关联证据缩略图、预览失败重试和证据状态标签已从页面移出。组件只消费父页已加载的数据并回传用户操作，父页仍负责受保护附件获取和最终业务动作。

第五批已新增 `frontend-vue/src/components/inspection/InspectionRecordDetailSummary.vue`：巡检详情头部、200 分制评分展示、历史迁移提示和扣分项明细已移出父页面。导出、补传和返回通过事件交给父页，既有权限、下载审计和数据计算路径不变。

第六批已新增 `frontend-vue/src/components/inspection/InspectionStandardReadinessNotice.vue`：发起巡检时的标准状态、200 分制合格线、校验诊断和刷新入口已移出父页面。品牌/门店选择、标准刷新请求和保存阻断仍由父页面保持原有实现，因此不会改变标准校验或巡检提交行为。

第七批已新增 `frontend-vue/src/components/inspection/InspectionPhotoDetectionList.vue`：照片识别状态、标注图、条款匹配、200 分制建议扣分和督导确认按钮已移出父页面。组件仅回传删除、重试、确认、驳回和撤销操作；附件上传、YOLO 调用、服务端计分与权限控制仍由父页面执行。

第八批已新增 `frontend-vue/src/components/inspection/InspectionScoreSummary.vue`：分类得分、总分、黄线风险、保存阻断提示和保存按钮已移出父页面。200 分制计算、标准校验、保存请求与服务端最终判定仍留在父页面，展示层不再重复业务计算。

第九批已新增 `frontend-vue/src/components/inspection/InspectionClauseEditor.vue`：正式条款表格、红黄线展示、实际分输入、证据关联和整改字段已移出父页面。父页面仍负责条款分数规范化、红线状态、附件关联、服务端计分和提交，因此不改变权限或评分口径。

第十批已新增 InspectionDeductionRecords.vue：人工与模型确认后的扣分记录表格、空态和删除入口已移出父页面。父页面仍负责删除后的条款状态恢复与最终保存，避免把评分业务规则分散到展示组件。

第十一批已新增 InspectionManualDeductionForm.vue：人工扣分的条款选择、分值和问题描述表单已移出父页面。实际扣分写入、标准校验、分数上限和保存请求仍由父页面统一处理。

第十二批已新增 InspectionDraftActions.vue：整改备注、清空表单和保存入口已移出父页面。草稿重置、上传状态、保存阻断原因和后端提交仍由父页面统一维护。

第十三批已新增 InspectionStandardCatalog.vue：标准摘要、校验诊断、维度筛选与完整条款目录已移出父页面。标准刷新请求和全局标准状态仍保留在父页面，确保新建巡检和标准页使用同一份权威数据。

第十四批已新增 InspectionRecordList.vue：品牌和月份筛选、记录空态与巡检列表已移出父页面；详情内容通过插槽保留在原有加载和权限编排内。筛选状态、详情请求和路由同步仍由父页面统一维护。

第十五批已新增 InspectionRecordMetrics.vue：巡检次数、平均得分、评分待修复提示与红线次数展示已移出父页面。统计口径仍使用父页面已有的统一汇总计算，避免出现重复计算。

第十六批已新增 `InspectionScoreSnapshotValidator`：历史巡检导出前的快照完整性检查、版本解析、已存分数校验和 200 分制重算已从 `InspectionService` 拆出。新类保持无状态且只依赖记录自身的不可变快照；导出服务仍负责权限、查询、审计写入与事务编排，接口和数据库结构不变。

第十七批已新增 `InspectionHistoricalRepairCalculator`：历史巡检修复的快照匹配、红线判定和分项重算已从 `InspectionService` 拆出。计算器遇到缺失、重复或无法确认的快照时只返回人工复核结论；原服务继续拥有修复权限、数据库写入与操作日志。

第十八批已新增 `InspectionLegacySnapshotParser`：旧版扣分及红线 JSON 的快照解析已从 `InspectionService` 拆出。解析失败时保持原始历史数据而不生成错误快照；主服务仍负责记录保存、附件绑定和权限控制。

第十九批已新增 `InspectionPhotoJsonCodec`：巡检照片识别结果解析及附件编号提取已从 `InspectionService` 拆出。附件编号仍在服务层完成权限校验和存储绑定；编解码器只负责格式解析和无效编号拦截。

第二十批已新增 `InspectionEvidencePhotoPolicy`，并扩展 `InspectionPhotoJsonCodec` 的历史证据解析边界：补传原图按服务端照片位置精确替换、追加附件去重以及“每张图片必须关联人工确认条款”的校验已从 `InspectionService` 拆出。权限、附件归属校验、存储重绑定、数据库更新和事务仍保留在主服务中，历史证据接口契约不变。

第二十一批已新增 `InspectionDetectionRules` 与 `InspectionDetectionEvidenceNormalizer`：模型识别证据去重、IoU 边界、稳定识别编号、置信度归一化、正式条款匹配及 200 分制服务端扣分已从 `InspectionService` 拆出。主服务继续负责权限、正式标准读取、YOLO 调用、持久化、操作日志和事务，模型或浏览器传入的分数仍不能决定最终得分。

## 下一批拆分顺序

1. **巡检页面**：继续提取照片识别结果与条款评分编辑区。每一步保留现有 API 契约，并覆盖创建、识别确认、历史证据补传、店长整改和运营复核。
2. **巡检后端**：从 `InspectionService` 按“标准快照、记录写入、附件/证据、识别编排、整改/复核”拆为协作服务；Controller 保持接口不变，事务只留在跨表业务编排层。
3. **仓库后端**：先从 `WarehouseRepository` 抽出商品目录、库存流水、叫货、采购/退货和打印查询；再将 `WarehouseService` 的跨仓审批、库存扣减、PDF 审计保留为明确事务入口。
4. **无引用组件**：仅在静态引用、路由、动态组件、测试和浏览器回归均确认无入口后，单独提交删除；不与业务重构混在一起。
5. **历史迁移层**：历史导入验收签字后先加只读/退役开关，观察一个发布周期，再删除旧 KV 迁移路径。

## 每批验收门槛

- 前端：`npm run build`，并使用受控角色账号验证桌面与 390px 移动宽度的受影响流程。
- 后端：`mvn -q test`、隔离构建打包；涉及数据库时在空 MySQL 完整重放 Flyway。
- 权限：至少验证未登录 401、错误角色/跨门店 403，以及关键写入和导出的操作日志。
- 发布：每批保持独立提交或候选包，记录构建哈希、测试结论和未验证的外部依赖。
