-- 为全部业务表字段补充中文注释。
-- 字段定义来自 V55 结构快照，仅修改 COMMENT；排除 flyway_schema_history。
-- 对三处历史结构差异按当前字段定义安全分支，绝不借补注释改变类型或 ON UPDATE。
-- 由 output/db-comment-audit/generate_v56.py 生成并经隔离 MySQL 结构指纹验证。

ALTER TABLE `auth_token`
  MODIFY COLUMN `token` varchar(96) NOT NULL COMMENT '访问令牌',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `permission_version` bigint NOT NULL COMMENT '权限版本号',
  MODIFY COLUMN `expires_at` datetime NOT NULL COMMENT '过期时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `auth_user`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `username` varchar(80) NOT NULL COMMENT '登录用户名',
  MODIFY COLUMN `password_hash` varchar(255) NOT NULL COMMENT '登录密码哈希',
  MODIFY COLUMN `display_name` varchar(120) NOT NULL COMMENT '显示名称',
  MODIFY COLUMN `role` varchar(40) NOT NULL COMMENT '角色代码',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `permission_version` bigint NOT NULL COMMENT '权限版本号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `brand`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `code` varchar(40) NOT NULL COMMENT '编码',
  MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '品牌名称',
  MODIFY COLUMN `color` varchar(40) DEFAULT NULL COMMENT '颜色',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `business_todo`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `rule_code` varchar(80) NOT NULL COMMENT '规则编码',
  MODIFY COLUMN `source_module` varchar(80) NOT NULL COMMENT '来源模块',
  MODIFY COLUMN `source_record_id` varchar(160) NOT NULL COMMENT '来源记录ID',
  MODIFY COLUMN `source_key` varchar(100) NOT NULL COMMENT '来源键',
  MODIFY COLUMN `occurrence_no` int NOT NULL COMMENT '发生序号',
  MODIFY COLUMN `title` varchar(255) NOT NULL COMMENT '标题',
  MODIFY COLUMN `summary` text COMMENT '摘要',
  MODIFY COLUMN `assignee_role` varchar(40) NOT NULL COMMENT '负责人角色',
  MODIFY COLUMN `review_role` varchar(40) DEFAULT NULL COMMENT '审核角色',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `month` char(7) DEFAULT NULL COMMENT '月份',
  MODIFY COLUMN `priority` tinyint NOT NULL COMMENT '优先级',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `condition_active` tinyint NOT NULL COMMENT '条件是否生效',
  MODIFY COLUMN `metadata_json` longtext COMMENT '元数据（JSON）',
  MODIFY COLUMN `last_operator_id` bigint DEFAULT NULL COMMENT '最近操作人ID',
  MODIFY COLUMN `last_operator_name` varchar(120) DEFAULT NULL COMMENT '最近操作人姓名',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  MODIFY COLUMN `completed_at` timestamp NULL DEFAULT NULL COMMENT '完成时间';

ALTER TABLE `daily_loss_inventory_application`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `daily_loss_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '每日报损ID',
  MODIFY COLUMN `store_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `quantity` decimal(14,2) NOT NULL COMMENT '数量',
  MODIFY COLUMN `movement_type` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流水类型',
  MODIFY COLUMN `applied_by` bigint NOT NULL COMMENT '申请人用户ID',
  MODIFY COLUMN `applied_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间';

ALTER TABLE `daily_loss_record`
  MODIFY COLUMN `id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `loss_date` date NOT NULL COMMENT '报损日期',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `item_code` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品编码',
  MODIFY COLUMN `item_name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `stock_unit` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '库存单位',
  MODIFY COLUMN `loss_quantity` decimal(14,2) NOT NULL COMMENT '报损数量',
  MODIFY COLUMN `unit_price_snapshot` decimal(18,4) NOT NULL COMMENT '单位价格快照',
  MODIFY COLUMN `amount_snapshot` decimal(18,2) NOT NULL COMMENT '金额快照',
  MODIFY COLUMN `loss_reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '报损原因',
  MODIFY COLUMN `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  MODIFY COLUMN `submitted_by` bigint NOT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `review_note` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核备注',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `employee`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `store_name` varchar(160) DEFAULT NULL COMMENT '门店名称',
  MODIFY COLUMN `brand_name` varchar(120) DEFAULT NULL COMMENT '品牌名称',
  MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '员工姓名',
  MODIFY COLUMN `phone` varchar(40) DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `role` varchar(80) DEFAULT NULL COMMENT '角色代码',
  MODIFY COLUMN `position` varchar(80) DEFAULT NULL COMMENT '岗位',
  MODIFY COLUMN `employment_type` varchar(40) DEFAULT NULL COMMENT '用工类型',
  MODIFY COLUMN `base_salary` decimal(14,2) NOT NULL COMMENT '月基础工资',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `hire_date` date DEFAULT NULL COMMENT '入职日期',
  MODIFY COLUMN `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  MODIFY COLUMN `data_source` varchar(40) DEFAULT NULL COMMENT '数据来源',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `employee_assistant_feedback`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `answer_source` varchar(32) NOT NULL COMMENT '答案来源',
  MODIFY COLUMN `knowledge_id` bigint DEFAULT NULL COMMENT '知识ID',
  MODIFY COLUMN `knowledge_version` int DEFAULT NULL COMMENT '知识库版本号',
  MODIFY COLUMN `helpful` tinyint(1) NOT NULL COMMENT '反馈是否有帮助',
  MODIFY COLUMN `reason_code` varchar(64) DEFAULT NULL COMMENT '原因编码',
  MODIFY COLUMN `created_by` bigint NOT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `employee_assistant_handoff`
  MODIFY COLUMN `id` varchar(64) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `question_redacted` varchar(1200) NOT NULL COMMENT '脱敏后的问题内容',
  MODIFY COLUMN `category` varchar(64) NOT NULL COMMENT '分类',
  MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT '状态',
  MODIFY COLUMN `requested_by` bigint NOT NULL COMMENT '申请人用户ID',
  MODIFY COLUMN `handled_by` bigint DEFAULT NULL COMMENT '处理人用户ID',
  MODIFY COLUMN `resolution` varchar(2000) DEFAULT NULL COMMENT '处理结果',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `claimed_at` timestamp NULL DEFAULT NULL COMMENT '领取时间',
  MODIFY COLUMN `responded_at` timestamp NULL DEFAULT NULL COMMENT '响应时间',
  MODIFY COLUMN `closed_at` timestamp NULL DEFAULT NULL COMMENT '关闭时间',
  MODIFY COLUMN `expires_at` timestamp NOT NULL COMMENT '过期时间',
  MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `employee_assistant_knowledge`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `category` varchar(64) NOT NULL COMMENT '分类',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `keywords` varchar(1000) NOT NULL COMMENT '关键词',
  MODIFY COLUMN `standard_answer` text NOT NULL COMMENT '标准答案',
  MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT '状态',
  MODIFY COLUMN `current_version` int NOT NULL COMMENT '当前版本号',
  MODIFY COLUMN `created_by` bigint NOT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `updated_by` bigint NOT NULL COMMENT '更新人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `employee_assistant_knowledge_version`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `knowledge_id` bigint NOT NULL COMMENT '知识ID',
  MODIFY COLUMN `version_no` int NOT NULL COMMENT '版本编号',
  MODIFY COLUMN `category` varchar(64) NOT NULL COMMENT '分类',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `keywords` varchar(1000) NOT NULL COMMENT '关键词',
  MODIFY COLUMN `standard_answer` text NOT NULL COMMENT '标准答案',
  MODIFY COLUMN `publish_action` varchar(16) NOT NULL COMMENT '发布操作',
  MODIFY COLUMN `published_by` bigint NOT NULL COMMENT '发布人用户ID',
  MODIFY COLUMN `published_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间';

ALTER TABLE `employee_month_attendance`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `employee_id` varchar(120) NOT NULL COMMENT '员工ID',
  MODIFY COLUMN `month` char(7) NOT NULL COMMENT '月份',
  MODIFY COLUMN `attendance_days` decimal(6,2) NOT NULL COMMENT '出勤天数',
  MODIFY COLUMN `normal_hours` decimal(10,2) NOT NULL COMMENT '正常工时',
  MODIFY COLUMN `overtime_hours` decimal(10,2) NOT NULL COMMENT '加班小时数',
  MODIFY COLUMN `total_hours` decimal(10,2) NOT NULL COMMENT '总工时',
  MODIFY COLUMN `vacation_balance` decimal(10,2) NOT NULL COMMENT '休假余额',
  MODIFY COLUMN `source` varchar(40) NOT NULL COMMENT '来源',
  MODIFY COLUMN `status` varchar(32) NOT NULL COMMENT '状态',
  MODIFY COLUMN `confirmed_by` bigint DEFAULT NULL COMMENT '确认人用户ID',
  MODIFY COLUMN `confirmed_at` timestamp NULL DEFAULT NULL COMMENT '确认时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `employee_salary_profile`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `employee_id` varchar(120) NOT NULL COMMENT '关联员工ID',
  MODIFY COLUMN `policy_id` varchar(120) DEFAULT NULL COMMENT '关联工资方案ID（空值使用默认方案）',
  MODIFY COLUMN `base_salary` decimal(14,2) NOT NULL COMMENT '月基础工资',
  MODIFY COLUMN `guarantee_salary` decimal(14,2) DEFAULT NULL COMMENT '月保底工资（空值表示无保底）',
  MODIFY COLUMN `overtime_hour_rate` decimal(14,2) DEFAULT NULL COMMENT '该员工每小时加班费',
  MODIFY COLUMN `performance_type` varchar(40) DEFAULT NULL COMMENT '绩效类型（FIXED_PERCENT固定比例、TIERED阶梯、NONE无）',
  MODIFY COLUMN `commission_type` varchar(40) DEFAULT NULL COMMENT '提成类型（REVENUE_PCT营业额比例、PROFIT_PCT利润比例、FIXED固定金额、NONE无）',
  MODIFY COLUMN `effective_from` date NOT NULL COMMENT '员工工资档案生效日期',
  MODIFY COLUMN `effective_to` date DEFAULT NULL COMMENT '员工工资档案失效日期（空值表示长期有效）',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `expense_claim`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `month` char(7) DEFAULT NULL COMMENT '月份',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `category` varchar(80) DEFAULT NULL COMMENT '分类',
  MODIFY COLUMN `reason` text COMMENT '原因',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `image_url` varchar(500) DEFAULT NULL COMMENT '图片URL',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `expense_supplement`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `expense_id` varchar(120) NOT NULL COMMENT '报销ID',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `submitted_by` bigint NOT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `submitted_by_name` varchar(120) NOT NULL COMMENT '提交人姓名',
  MODIFY COLUMN `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间';

ALTER TABLE `expense_supplement_attachment`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `supplement_id` bigint NOT NULL COMMENT '补充ID',
  MODIFY COLUMN `expense_id` varchar(120) NOT NULL COMMENT '报销ID',
  MODIFY COLUMN `file_name` varchar(255) NOT NULL COMMENT '文件名称',
  MODIFY COLUMN `content_type` varchar(120) NOT NULL COMMENT '内容类型',
  MODIFY COLUMN `file_size` bigint NOT NULL COMMENT '文件大小（字节）',
  MODIFY COLUMN `storage_key` varchar(160) NOT NULL COMMENT '存储键',
  MODIFY COLUMN `uploaded_by` bigint NOT NULL COMMENT '上传人用户ID',
  MODIFY COLUMN `uploaded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间';

ALTER TABLE `inspection_record`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `inspection_date` date NOT NULL COMMENT '巡检日期',
  MODIFY COLUMN `inspector` varchar(120) DEFAULT NULL COMMENT '巡检人',
  MODIFY COLUMN `brand` varchar(120) DEFAULT NULL COMMENT '品牌',
  MODIFY COLUMN `full_score` decimal(8,2) NOT NULL COMMENT '满分',
  MODIFY COLUMN `pass_score` decimal(8,2) NOT NULL COMMENT '及格分数',
  MODIFY COLUMN `score` decimal(8,2) NOT NULL COMMENT '得分',
  MODIFY COLUMN `passed` tinyint(1) NOT NULL COMMENT '是否通过',
  MODIFY COLUMN `deductions_json` longtext COMMENT '扣分项列表（JSON）',
  MODIFY COLUMN `redlines_json` longtext COMMENT '红线项列表（JSON）',
  MODIFY COLUMN `photos_json` longtext COMMENT '照片列表（JSON）',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  MODIFY COLUMN `standard_version_id` bigint DEFAULT NULL COMMENT '标准版本ID',
  MODIFY COLUMN `standard_version` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标准版本号',
  MODIFY COLUMN `material_score` decimal(8,2) DEFAULT NULL COMMENT '物料维度得分',
  MODIFY COLUMN `hygiene_score` decimal(8,2) DEFAULT NULL COMMENT '卫生维度得分',
  MODIFY COLUMN `service_score` decimal(8,2) DEFAULT NULL COMMENT '服务维度得分',
  MODIFY COLUMN `result_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '结果编码',
  MODIFY COLUMN `test_marker` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '测试标记';

ALTER TABLE `inspection_record_standard_snapshot`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `inspection_record_id` varchar(120) NOT NULL COMMENT '巡检记录ID',
  MODIFY COLUMN `standard_id` bigint DEFAULT NULL COMMENT '标准ID',
  MODIFY COLUMN `standard_version` varchar(64) DEFAULT NULL COMMENT '标准版本号',
  MODIFY COLUMN `dimension` varchar(120) DEFAULT NULL COMMENT '维度',
  MODIFY COLUMN `standard_title` varchar(500) DEFAULT NULL COMMENT '标准标题',
  MODIFY COLUMN `standard_description` text COMMENT '标准描述',
  MODIFY COLUMN `suggested_score` decimal(8,2) NOT NULL COMMENT '建议得分',
  MODIFY COLUMN `actual_deduction_score` decimal(8,2) NOT NULL COMMENT '实际扣分',
  MODIFY COLUMN `red_line` tinyint(1) NOT NULL COMMENT '是否红线项',
  MODIFY COLUMN `problem_description` text COMMENT '问题描述',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `standard_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标准编码',
  MODIFY COLUMN `check_method` longtext COLLATE utf8mb4_unicode_ci COMMENT '检查方式',
  MODIFY COLUMN `actual_score` decimal(8,2) NOT NULL COMMENT '实际得分',
  MODIFY COLUMN `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '风险等级',
  MODIFY COLUMN `photo_attachment_ids_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '照片附件ID列表（JSON）',
  MODIFY COLUMN `responsible_person` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '责任人',
  MODIFY COLUMN `rectification_deadline` date DEFAULT NULL COMMENT '整改截止时间',
  MODIFY COLUMN `rectification_status` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '整改状态',
  MODIFY COLUMN `review_result` longtext COLLATE utf8mb4_unicode_ci COMMENT '审核结果',
  MODIFY COLUMN `before_photo_attachment_ids_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '整改前照片附件ID列表（JSON）',
  MODIFY COLUMN `after_photo_attachment_ids_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '整改后照片附件ID列表（JSON）';

ALTER TABLE `inspection_rectification`
  MODIFY COLUMN `id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `inspection_record_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '巡检记录ID',
  MODIFY COLUMN `store_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  MODIFY COLUMN `manager_note` text COLLATE utf8mb4_unicode_ci COMMENT '店长备注',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `submitted_by_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交人姓名',
  MODIFY COLUMN `submitted_at` timestamp NULL DEFAULT NULL COMMENT '提交时间',
  MODIFY COLUMN `review_note` text COLLATE utf8mb4_unicode_ci COMMENT '审核备注',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_by_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核人姓名',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `inspection_rectification_action`
  MODIFY COLUMN `id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `rectification_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '整改记录ID',
  MODIFY COLUMN `inspection_record_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '巡检记录ID',
  MODIFY COLUMN `action` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作',
  MODIFY COLUMN `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  MODIFY COLUMN `note` text COLLATE utf8mb4_unicode_ci COMMENT '备注',
  MODIFY COLUMN `actor_user_id` bigint DEFAULT NULL COMMENT '操作人用户ID',
  MODIFY COLUMN `actor_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名',
  MODIFY COLUMN `actor_role` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人角色',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `inspection_result_repair_audit`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `inspection_record_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '巡检记录ID',
  MODIFY COLUMN `original_standard_version_id` bigint DEFAULT NULL COMMENT '原始标准版本ID',
  MODIFY COLUMN `original_standard_version` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始标准版本号',
  MODIFY COLUMN `original_full_score` decimal(8,2) DEFAULT NULL COMMENT '原始满分',
  MODIFY COLUMN `original_pass_score` decimal(8,2) DEFAULT NULL COMMENT '原始及格分数',
  MODIFY COLUMN `original_score` decimal(8,2) DEFAULT NULL COMMENT '原始得分',
  MODIFY COLUMN `original_material_score` decimal(8,2) DEFAULT NULL COMMENT '原始物料得分',
  MODIFY COLUMN `original_hygiene_score` decimal(8,2) DEFAULT NULL COMMENT '原始卫生得分',
  MODIFY COLUMN `original_service_score` decimal(8,2) DEFAULT NULL COMMENT '原始服务得分',
  MODIFY COLUMN `original_result_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始结果编码',
  MODIFY COLUMN `original_passed` tinyint(1) DEFAULT NULL COMMENT '原始通过',
  MODIFY COLUMN `repaired_standard_version_id` bigint NOT NULL COMMENT '修复后标准版本ID',
  MODIFY COLUMN `repaired_standard_version` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修复后标准版本号',
  MODIFY COLUMN `repaired_full_score` decimal(8,2) DEFAULT NULL COMMENT '修复后满分',
  MODIFY COLUMN `repaired_pass_score` decimal(8,2) DEFAULT NULL COMMENT '修复后及格分数',
  MODIFY COLUMN `repaired_score` decimal(8,2) DEFAULT NULL COMMENT '修复后得分',
  MODIFY COLUMN `repaired_material_score` decimal(8,2) DEFAULT NULL COMMENT '修复后物料得分',
  MODIFY COLUMN `repaired_hygiene_score` decimal(8,2) DEFAULT NULL COMMENT '修复后卫生得分',
  MODIFY COLUMN `repaired_service_score` decimal(8,2) DEFAULT NULL COMMENT '修复后服务得分',
  MODIFY COLUMN `repaired_result_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修复后结果编码',
  MODIFY COLUMN `repaired_passed` tinyint(1) DEFAULT NULL COMMENT '修复后通过',
  MODIFY COLUMN `repair_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '修复状态',
  MODIFY COLUMN `repair_reason` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '修复原因',
  MODIFY COLUMN `snapshot_item_count` int NOT NULL COMMENT '快照项目数量',
  MODIFY COLUMN `expected_item_count` int NOT NULL COMMENT '预期项目数量',
  MODIFY COLUMN `repaired_by` bigint DEFAULT NULL COMMENT '修复人用户ID',
  MODIFY COLUMN `repaired_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修复时间';

ALTER TABLE `inspection_score_scale_item_migration_audit`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `score_migration_audit_id` bigint NOT NULL COMMENT '得分迁移审计ID',
  MODIFY COLUMN `inspection_snapshot_id` bigint NOT NULL COMMENT '巡检快照ID',
  MODIFY COLUMN `conversion_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换版本',
  MODIFY COLUMN `original_suggested_score` decimal(8,2) NOT NULL COMMENT '原始建议得分',
  MODIFY COLUMN `original_deduction_score` decimal(8,2) NOT NULL COMMENT '原始扣分值',
  MODIFY COLUMN `original_actual_score` decimal(8,2) NOT NULL COMMENT '原始实际得分',
  MODIFY COLUMN `converted_suggested_score` decimal(8,2) NOT NULL COMMENT '转换后建议得分',
  MODIFY COLUMN `converted_deduction_score` decimal(8,2) NOT NULL COMMENT '转换后扣分值',
  MODIFY COLUMN `converted_actual_score` decimal(8,2) NOT NULL COMMENT '转换后实际得分',
  MODIFY COLUMN `migrated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迁移时间';

ALTER TABLE `inspection_score_scale_migration_audit`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `inspection_record_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '巡检记录ID',
  MODIFY COLUMN `migration_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '迁移唯一键',
  MODIFY COLUMN `conversion_version` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '转换版本',
  MODIFY COLUMN `conversion_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换状态',
  MODIFY COLUMN `conversion_evidence` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换依据',
  MODIFY COLUMN `original_full_score` decimal(8,2) DEFAULT NULL COMMENT '原始满分',
  MODIFY COLUMN `original_pass_score` decimal(8,2) DEFAULT NULL COMMENT '原始及格分数',
  MODIFY COLUMN `original_score` decimal(8,2) DEFAULT NULL COMMENT '原始得分',
  MODIFY COLUMN `original_material_score` decimal(8,2) DEFAULT NULL COMMENT '原始物料得分',
  MODIFY COLUMN `original_hygiene_score` decimal(8,2) DEFAULT NULL COMMENT '原始卫生得分',
  MODIFY COLUMN `original_service_score` decimal(8,2) DEFAULT NULL COMMENT '原始服务得分',
  MODIFY COLUMN `original_passed` tinyint(1) NOT NULL COMMENT '原始通过',
  MODIFY COLUMN `original_result_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始结果编码',
  MODIFY COLUMN `converted_full_score` decimal(8,2) NOT NULL COMMENT '转换后满分',
  MODIFY COLUMN `converted_pass_score` decimal(8,2) NOT NULL COMMENT '转换后及格分数',
  MODIFY COLUMN `converted_score` decimal(8,2) NOT NULL COMMENT '转换后得分',
  MODIFY COLUMN `converted_material_score` decimal(8,2) DEFAULT NULL COMMENT '转换后物料得分',
  MODIFY COLUMN `converted_hygiene_score` decimal(8,2) DEFAULT NULL COMMENT '转换后卫生得分',
  MODIFY COLUMN `converted_service_score` decimal(8,2) DEFAULT NULL COMMENT '转换后服务得分',
  MODIFY COLUMN `converted_passed` tinyint(1) NOT NULL COMMENT '转换后通过',
  MODIFY COLUMN `converted_result_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '转换后结果编码',
  MODIFY COLUMN `migrated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迁移时间';

ALTER TABLE `inspection_standard_item`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `standard_version_id` bigint NOT NULL COMMENT '标准版本ID',
  MODIFY COLUMN `dimension` varchar(120) NOT NULL COMMENT '维度',
  MODIFY COLUMN `code` varchar(80) DEFAULT NULL COMMENT '编码',
  MODIFY COLUMN `title` varchar(500) NOT NULL COMMENT '标题',
  MODIFY COLUMN `description` text COMMENT '描述',
  MODIFY COLUMN `suggested_score` decimal(8,2) NOT NULL COMMENT '建议得分',
  MODIFY COLUMN `red_line` tinyint(1) NOT NULL COMMENT '是否红线项',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  MODIFY COLUMN `check_method` longtext COLLATE utf8mb4_unicode_ci COMMENT '检查方式',
  MODIFY COLUMN `category_score` decimal(8,2) NOT NULL COMMENT '分类得分',
  MODIFY COLUMN `source_sheet` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源工作表',
  MODIFY COLUMN `source_row` int DEFAULT NULL COMMENT '来源行',
  MODIFY COLUMN `subitems_json` longtext COLLATE utf8mb4_unicode_ci COMMENT '子项目列表（JSON）',
  MODIFY COLUMN `risk_level` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '风险等级';

ALTER TABLE `inspection_standard_version`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `version` varchar(64) NOT NULL COMMENT '版本号',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `full_score` decimal(8,2) NOT NULL COMMENT '满分',
  MODIFY COLUMN `effective_date` date DEFAULT NULL COMMENT '生效日期',
  MODIFY COLUMN `status` varchar(24) NOT NULL COMMENT '状态',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间',
  MODIFY COLUMN `pass_score` decimal(8,2) NOT NULL COMMENT '及格分数';

ALTER TABLE `kv_storage`
  MODIFY COLUMN `storage_key` varchar(120) NOT NULL COMMENT '存储键',
  MODIFY COLUMN `storage_value` longtext NOT NULL COMMENT '存储值',
  MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `operation_log`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  MODIFY COLUMN `operator_name` varchar(120) DEFAULT NULL COMMENT '操作人姓名',
  MODIFY COLUMN `action` varchar(80) NOT NULL COMMENT '操作',
  MODIFY COLUMN `target_type` varchar(80) NOT NULL COMMENT '目标类型',
  MODIFY COLUMN `target_id` varchar(120) DEFAULT NULL COMMENT '目标ID',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `month` char(7) DEFAULT NULL COMMENT '月份',
  MODIFY COLUMN `before_json` longtext COMMENT '变更前数据（JSON）',
  MODIFY COLUMN `after_json` longtext COMMENT '变更后数据（JSON）',
  MODIFY COLUMN `reason` varchar(255) DEFAULT NULL COMMENT '原因',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `permission_catalog`
  MODIFY COLUMN `permission_code` varchar(120) NOT NULL COMMENT '权限代码',
  MODIFY COLUMN `module_code` varchar(40) NOT NULL COMMENT '模块编码',
  MODIFY COLUMN `permission_name` varchar(120) NOT NULL COMMENT '权限名称',
  MODIFY COLUMN `description` varchar(500) DEFAULT NULL COMMENT '描述',
  MODIFY COLUMN `risk_level` varchar(16) NOT NULL COMMENT '风险等级',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

SET @v56_comment_sql = (
  SELECT CASE
    WHEN column_type = 'datetime'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND extra = ''
      THEN 'ALTER TABLE `permission_catalog` MODIFY COLUMN `updated_at` datetime NULL DEFAULT NULL COMMENT ''更新时间'''
    WHEN column_type = 'timestamp'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND LOWER(extra) = 'on update current_timestamp'
      THEN 'ALTER TABLE `permission_catalog` MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'''
    ELSE 'ALTER TABLE `__v56_unsupported_permission_catalog_updated_at__` ADD COLUMN `definition_mismatch` int'
  END
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'permission_catalog'
    AND column_name = 'updated_at'
);
PREPARE v56_comment_stmt FROM @v56_comment_sql;
EXECUTE v56_comment_stmt;
DEALLOCATE PREPARE v56_comment_stmt;

ALTER TABLE `platform_account`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `platform_name` varchar(120) NOT NULL COMMENT '平台名称',
  MODIFY COLUMN `login_url` varchar(500) DEFAULT NULL COMMENT '登录地址URL',
  MODIFY COLUMN `username` varchar(160) DEFAULT NULL COMMENT '登录用户名',
  MODIFY COLUMN `password_cipher` varchar(500) DEFAULT NULL COMMENT '密码密文',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `platform_webhook_event`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `provider` varchar(32) NOT NULL COMMENT '服务提供方',
  MODIFY COLUMN `event_id` varchar(160) NOT NULL COMMENT '事件ID',
  MODIFY COLUMN `event_type` varchar(80) DEFAULT NULL COMMENT '事件类型',
  MODIFY COLUMN `payload_sha256` char(64) NOT NULL COMMENT '请求载荷SHA-256摘要',
  MODIFY COLUMN `processing_status` varchar(32) NOT NULL COMMENT '处理状态',
  MODIFY COLUMN `duplicate_count` int NOT NULL COMMENT '重复记录数量',
  MODIFY COLUMN `received_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收货时间',
  MODIFY COLUMN `last_received_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近收货时间';

ALTER TABLE `profit_entry`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `month` char(7) NOT NULL COMMENT '月份',
  MODIFY COLUMN `sales` decimal(14,2) NOT NULL COMMENT '营业额',
  MODIFY COLUMN `refund` decimal(14,2) NOT NULL COMMENT '退款金额',
  MODIFY COLUMN `discount` decimal(14,2) NOT NULL COMMENT '折扣金额',
  MODIFY COLUMN `material` decimal(14,2) NOT NULL COMMENT '物料成本',
  MODIFY COLUMN `packaging` decimal(14,2) NOT NULL COMMENT '包装费用',
  MODIFY COLUMN `loss` decimal(14,2) NOT NULL COMMENT '报损金额',
  MODIFY COLUMN `cost_other` decimal(14,2) NOT NULL COMMENT '其他成本',
  MODIFY COLUMN `rent` decimal(14,2) NOT NULL COMMENT '房租费用',
  MODIFY COLUMN `labor` decimal(14,2) NOT NULL COMMENT '人工成本',
  MODIFY COLUMN `utility` decimal(14,2) NOT NULL COMMENT '水电费用',
  MODIFY COLUMN `property` decimal(14,2) NOT NULL COMMENT '物业费用',
  MODIFY COLUMN `commission` decimal(14,2) NOT NULL COMMENT '平台佣金',
  MODIFY COLUMN `promo` decimal(14,2) NOT NULL COMMENT '推广费用',
  MODIFY COLUMN `repair` decimal(14,2) NOT NULL COMMENT '维修费用',
  MODIFY COLUMN `equip` decimal(14,2) NOT NULL COMMENT '设备费用',
  MODIFY COLUMN `exp_other` decimal(14,2) NOT NULL COMMENT '其他费用',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `updated_by` bigint DEFAULT NULL COMMENT '更新人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `role_permission`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `role_code` varchar(40) NOT NULL COMMENT '角色代码',
  MODIFY COLUMN `permission_code` varchar(120) NOT NULL COMMENT '权限代码',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `salary_policy`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `name` varchar(160) NOT NULL COMMENT '工资方案名称（例如：2026标准工资规则）',
  MODIFY COLUMN `version` int NOT NULL COMMENT '版本号',
  MODIFY COLUMN `effective_from` date DEFAULT NULL COMMENT '方案生效日期',
  MODIFY COLUMN `effective_to` date DEFAULT NULL COMMENT '方案失效日期（空值表示长期有效）',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '方案状态（DRAFT草稿、ACTIVE生效、ARCHIVED归档）',
  MODIFY COLUMN `guarantee_enabled` tinyint(1) NOT NULL COMMENT '是否启用保底工资',
  MODIFY COLUMN `guarantee_full_attendance_days` decimal(4,1) DEFAULT NULL COMMENT '享受保底工资所需的最低出勤天数',
  MODIFY COLUMN `guarantee_included_items` varchar(500) DEFAULT NULL COMMENT '保底计算纳入的项目编码（逗号分隔）',
  MODIFY COLUMN `guarantee_excluded_items` varchar(500) DEFAULT NULL COMMENT '保底计算排除的项目编码（逗号分隔）',
  MODIFY COLUMN `guarantee_feb_exclude` tinyint(1) NOT NULL COMMENT '保底计算是否排除二月',
  MODIFY COLUMN `overtime_hour_rate` decimal(14,2) DEFAULT NULL COMMENT '默认每小时加班费（员工工资档案可覆盖）',
  MODIFY COLUMN `overtime_hour_rate_source` varchar(40) DEFAULT NULL COMMENT '加班费率来源（ATTENDANCE_SYSTEM考勤系统、MANUAL_INPUT手工录入、PROFILE_ONLY仅员工档案）',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `salary_record`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `month` char(7) NOT NULL COMMENT '月份',
  MODIFY COLUMN `employee_id` varchar(120) DEFAULT NULL COMMENT '员工ID',
  MODIFY COLUMN `policy_id` varchar(120) DEFAULT NULL COMMENT '方案ID',
  MODIFY COLUMN `policy_version` int DEFAULT NULL COMMENT '工资方案版本号',
  MODIFY COLUMN `policy_snapshot_json` longtext COMMENT '工资方案快照（JSON）',
  MODIFY COLUMN `calculation_snapshot_json` longtext COMMENT '工资计算快照（JSON）',
  MODIFY COLUMN `employee_name` varchar(120) NOT NULL COMMENT '员工姓名',
  MODIFY COLUMN `position` varchar(80) DEFAULT NULL COMMENT '岗位',
  MODIFY COLUMN `attendance` varchar(80) DEFAULT NULL COMMENT '出勤',
  MODIFY COLUMN `gross` decimal(14,2) NOT NULL COMMENT '应发工资',
  MODIFY COLUMN `net_pay` decimal(14,2) DEFAULT NULL COMMENT '实发工资',
  MODIFY COLUMN `normal_hours` decimal(10,2) NOT NULL COMMENT '正常工时',
  MODIFY COLUMN `ot_hours` decimal(10,2) NOT NULL COMMENT '加班小时数',
  MODIFY COLUMN `work_hours` decimal(10,2) NOT NULL COMMENT '工作小时数',
  MODIFY COLUMN `vacation_left` decimal(10,2) NOT NULL COMMENT '剩余休假天数',
  MODIFY COLUMN `vacation_note` varchar(255) DEFAULT NULL COMMENT '休假备注',
  MODIFY COLUMN `base` decimal(14,2) NOT NULL COMMENT '基本工资',
  MODIFY COLUMN `social` decimal(14,2) NOT NULL COMMENT '社保补助',
  MODIFY COLUMN `post` decimal(14,2) NOT NULL COMMENT '岗位工资',
  MODIFY COLUMN `meal` decimal(14,2) NOT NULL COMMENT '餐补金额',
  MODIFY COLUMN `full_attendance` decimal(14,2) NOT NULL COMMENT '全勤奖金额',
  MODIFY COLUMN `commission` decimal(14,2) NOT NULL COMMENT '提成金额',
  MODIFY COLUMN `overtime` decimal(14,2) NOT NULL COMMENT '加班工资',
  MODIFY COLUMN `seniority` decimal(14,2) NOT NULL COMMENT '工龄工资',
  MODIFY COLUMN `late_night` decimal(14,2) NOT NULL COMMENT '深夜班补贴',
  MODIFY COLUMN `subsidy` decimal(14,2) NOT NULL COMMENT '补贴金额',
  MODIFY COLUMN `performance` decimal(14,2) NOT NULL COMMENT '绩效工资',
  MODIFY COLUMN `deduct_uniform` decimal(14,2) NOT NULL COMMENT '扣工服费',
  MODIFY COLUMN `return_uniform` decimal(14,2) NOT NULL COMMENT '返工服费',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `review_note` varchar(500) DEFAULT NULL COMMENT '审核备注',
  MODIFY COLUMN `paid_at` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  MODIFY COLUMN `version` int NOT NULL COMMENT '版本号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `salary_record_item`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `salary_record_id` varchar(120) NOT NULL COMMENT '工资记录ID',
  MODIFY COLUMN `item_code` varchar(40) NOT NULL COMMENT '薪资项目编码（例如BASE基础工资、OVERTIME加班费、COMMISSION提成、SOCIAL_INSURANCE社保）',
  MODIFY COLUMN `item_name` varchar(120) NOT NULL COMMENT '薪资项目中文名称',
  MODIFY COLUMN `item_type` varchar(20) NOT NULL COMMENT '薪资项目类型（EARNING收入、DEDUCTION扣款、EMPLOYER_COST雇主成本、INFORMATION信息）',
  MODIFY COLUMN `quantity` decimal(14,4) DEFAULT NULL COMMENT '计量数量（例如加班小时数、出勤天数）',
  MODIFY COLUMN `unit_price` decimal(14,4) DEFAULT NULL COMMENT '计算单价（例如小时费率、日费率）',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '该薪资明细最终金额',
  MODIFY COLUMN `source` varchar(40) NOT NULL COMMENT '明细来源（MANUAL手工、ATTENDANCE考勤、PERFORMANCE绩效、CALCULATED计算、IMPORT导入）',
  MODIFY COLUMN `calculation_note` varchar(500) DEFAULT NULL COMMENT '可读的计算说明',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `store_branch`
  MODIFY COLUMN `id` varchar(64) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `brand_id` bigint DEFAULT NULL COMMENT '品牌ID',
  MODIFY COLUMN `code` varchar(80) DEFAULT NULL COMMENT '编码',
  MODIFY COLUMN `name` varchar(160) NOT NULL COMMENT '门店名称',
  MODIFY COLUMN `area` varchar(160) DEFAULT NULL COMMENT '所属区域',
  MODIFY COLUMN `region_code` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '区域编码',
  MODIFY COLUMN `supply_warehouse_id` bigint DEFAULT NULL COMMENT '供货仓库ID',
  MODIFY COLUMN `manager` varchar(120) DEFAULT NULL COMMENT '店长',
  MODIFY COLUMN `open_date` date DEFAULT NULL COMMENT '开业日期',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `store_inventory`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `quantity` decimal(14,2) NOT NULL COMMENT '数量',
  MODIFY COLUMN `unit` varchar(40) DEFAULT NULL COMMENT '单位',
  MODIFY COLUMN `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `store_inventory_check`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `check_no` varchar(80) NOT NULL COMMENT '盘点单号',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `store_name` varchar(160) NOT NULL COMMENT '门店名称',
  MODIFY COLUMN `check_date` date NOT NULL COMMENT '盘点日期',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `total_amount` decimal(14,2) NOT NULL COMMENT '总金额',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `store_inventory_check_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `check_id` bigint NOT NULL COMMENT '盘点单ID',
  MODIFY COLUMN `item_name` varchar(160) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `item_code` varchar(80) DEFAULT NULL COMMENT '商品编码',
  MODIFY COLUMN `category` varchar(80) DEFAULT NULL COMMENT '分类',
  MODIFY COLUMN `spec` varchar(120) DEFAULT NULL COMMENT '规格',
  MODIFY COLUMN `unit` varchar(40) DEFAULT NULL COMMENT '单位',
  MODIFY COLUMN `package_quantity` decimal(14,2) DEFAULT NULL COMMENT '包装数量',
  MODIFY COLUMN `unit_price` decimal(14,2) NOT NULL COMMENT '单位价格',
  MODIFY COLUMN `unit_price_each` decimal(14,4) DEFAULT NULL COMMENT '每件单价',
  MODIFY COLUMN `counted_quantity` decimal(14,2) NOT NULL COMMENT '盘点数量',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `store_inventory_movement`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `quantity_delta` decimal(14,2) NOT NULL COMMENT '数量变动值',
  MODIFY COLUMN `movement_type` varchar(40) NOT NULL COMMENT '流水类型',
  MODIFY COLUMN `source_type` varchar(60) DEFAULT NULL COMMENT '来源类型',
  MODIFY COLUMN `source_id` varchar(120) DEFAULT NULL COMMENT '来源ID',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `store_receipt`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `delivery_id` varchar(120) NOT NULL COMMENT '配送ID',
  MODIFY COLUMN `requisition_id` varchar(120) NOT NULL COMMENT '叫货单ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `received_by` bigint DEFAULT NULL COMMENT '收货人用户ID',
  MODIFY COLUMN `received_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收货时间',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `store_receipt_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `receipt_id` varchar(120) NOT NULL COMMENT '收货ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `received_quantity` decimal(14,2) NOT NULL COMMENT '实收数量',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `store_requisition`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `supply_warehouse_id` bigint NOT NULL COMMENT '供货仓库ID',
  MODIFY COLUMN `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '幂等键',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `total_amount` decimal(14,2) NOT NULL COMMENT '总金额',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `received_note` text COMMENT '收货备注',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `shipped_by` bigint DEFAULT NULL COMMENT '发货人用户ID',
  MODIFY COLUMN `received_by` bigint DEFAULT NULL COMMENT '收货人用户ID',
  MODIFY COLUMN `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `shipped_at` timestamp NULL DEFAULT NULL COMMENT '发货时间',
  MODIFY COLUMN `received_at` timestamp NULL DEFAULT NULL COMMENT '收货时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `store_requisition_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `requisition_id` varchar(120) NOT NULL COMMENT '叫货单ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `requested_quantity` decimal(14,2) NOT NULL COMMENT '申请数量',
  MODIFY COLUMN `approved_quantity` decimal(14,2) DEFAULT NULL COMMENT '审批通过数量',
  MODIFY COLUMN `shipped_quantity` decimal(14,2) NOT NULL COMMENT '发货数量',
  MODIFY COLUMN `unit_price` decimal(14,2) NOT NULL COMMENT '单位价格',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `warning_text` varchar(255) DEFAULT NULL COMMENT '预警提示文字',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `tenant`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `name` varchar(160) NOT NULL COMMENT '租户名称',
  MODIFY COLUMN `industry` varchar(80) DEFAULT NULL COMMENT '行业',
  MODIFY COLUMN `scale` varchar(80) DEFAULT NULL COMMENT '企业规模',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `todo_action`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `todo_id` varchar(160) NOT NULL COMMENT '待办ID',
  MODIFY COLUMN `action_type` varchar(40) NOT NULL COMMENT '操作类型',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `note` text NOT NULL COMMENT '备注',
  MODIFY COLUMN `actor_user_id` bigint DEFAULT NULL COMMENT '操作人用户ID',
  MODIFY COLUMN `actor_name` varchar(120) DEFAULT NULL COMMENT '操作人姓名',
  MODIFY COLUMN `actor_role` varchar(40) NOT NULL COMMENT '操作人角色',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `todo_action_attachment`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `action_id` varchar(120) NOT NULL COMMENT '操作ID',
  MODIFY COLUMN `todo_id` varchar(160) NOT NULL COMMENT '待办ID',
  MODIFY COLUMN `file_name` varchar(240) NOT NULL COMMENT '文件名称',
  MODIFY COLUMN `content_type` varchar(120) DEFAULT NULL COMMENT '内容类型',
  MODIFY COLUMN `size_bytes` bigint NOT NULL COMMENT '大小（字节）',
  MODIFY COLUMN `content` longblob NOT NULL COMMENT '附件二进制内容',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `todo_escalation`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `source_role` varchar(40) NOT NULL COMMENT '来源角色',
  MODIFY COLUMN `source_module` varchar(80) NOT NULL COMMENT '来源模块',
  MODIFY COLUMN `source_id` varchar(120) NOT NULL COMMENT '来源ID',
  MODIFY COLUMN `source_todo_id` varchar(160) NOT NULL COMMENT '来源待办ID',
  MODIFY COLUMN `reason` text NOT NULL COMMENT '原因',
  MODIFY COLUMN `severity` varchar(40) NOT NULL COMMENT '严重程度',
  MODIFY COLUMN `reported_by_user_id` bigint DEFAULT NULL COMMENT '上报人用户ID',
  MODIFY COLUMN `reported_by_name` varchar(120) DEFAULT NULL COMMENT '上报人姓名',
  MODIFY COLUMN `boss_todo_id` varchar(160) NOT NULL COMMENT '老板待办ID',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `resolved_at` timestamp NULL DEFAULT NULL COMMENT '解决时间';

ALTER TABLE `training_course`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `course_code` varchar(80) NOT NULL COMMENT '课程编码',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `category` varchar(80) DEFAULT NULL COMMENT '分类',
  MODIFY COLUMN `description` text COMMENT '描述',
  MODIFY COLUMN `cover_url` varchar(500) DEFAULT NULL COMMENT '封面图片URL',
  MODIFY COLUMN `duration_minutes` int NOT NULL COMMENT '时长（分钟）',
  MODIFY COLUMN `required_role_scope` varchar(255) DEFAULT NULL COMMENT '要求的角色范围',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_course_material`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `course_id` bigint NOT NULL COMMENT '课程ID',
  MODIFY COLUMN `material_id` bigint NOT NULL COMMENT '培训资料ID',
  MODIFY COLUMN `required` tinyint(1) NOT NULL COMMENT '是否必填',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_exam_answer`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `attempt_id` bigint NOT NULL COMMENT '作答尝试ID',
  MODIFY COLUMN `question_id` bigint NOT NULL COMMENT '题目ID',
  MODIFY COLUMN `user_answer` text COMMENT '用户答案',
  MODIFY COLUMN `correct` tinyint(1) NOT NULL COMMENT '答案是否正确',
  MODIFY COLUMN `score` decimal(8,2) NOT NULL COMMENT '得分',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_exam_answer_review`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `answer_id` bigint NOT NULL COMMENT '答案ID',
  MODIFY COLUMN `awarded_score` decimal(8,2) NOT NULL COMMENT '获得分数',
  MODIFY COLUMN `review_comment` varchar(500) DEFAULT NULL COMMENT '审核意见',
  MODIFY COLUMN `reviewed_by` bigint NOT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` datetime NOT NULL COMMENT '审核时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_exam_assignment`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `campaign_id` bigint NOT NULL COMMENT '考试活动ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `examinee_name` varchar(120) NOT NULL COMMENT '考生姓名',
  MODIFY COLUMN `examinee_role` varchar(40) NOT NULL COMMENT '考生角色',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `store_name` varchar(160) NOT NULL COMMENT '门店名称',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `assigned_at` datetime NOT NULL COMMENT '分配时间',
  MODIFY COLUMN `due_at` datetime NOT NULL COMMENT '到期时间',
  MODIFY COLUMN `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  MODIFY COLUMN `retake_available_at` timestamp NULL DEFAULT NULL COMMENT '允许重考时间',
  MODIFY COLUMN `attempt_id` bigint DEFAULT NULL COMMENT '作答尝试ID',
  MODIFY COLUMN `score` decimal(8,2) DEFAULT NULL COMMENT '得分',
  MODIFY COLUMN `passed` tinyint(1) DEFAULT NULL COMMENT '是否通过',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_attempt`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `paper_id` bigint NOT NULL COMMENT '试卷ID',
  MODIFY COLUMN `campaign_id` bigint DEFAULT NULL COMMENT '考试活动ID',
  MODIFY COLUMN `assignment_id` bigint DEFAULT NULL COMMENT '任务ID',
  MODIFY COLUMN `paper_name` varchar(160) NOT NULL COMMENT '试卷名称',
  MODIFY COLUMN `examinee_name` varchar(120) NOT NULL COMMENT '考生姓名',
  MODIFY COLUMN `examinee_role` varchar(40) NOT NULL COMMENT '考生角色',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `store_name` varchar(160) DEFAULT NULL COMMENT '门店名称',
  MODIFY COLUMN `score` decimal(8,2) NOT NULL COMMENT '得分',
  MODIFY COLUMN `passed` tinyint(1) NOT NULL COMMENT '是否通过',
  MODIFY COLUMN `violated` tinyint(1) NOT NULL COMMENT '是否违规',
  MODIFY COLUMN `submitted_by` bigint DEFAULT NULL COMMENT '提交人用户ID',
  MODIFY COLUMN `submitted_at` datetime NOT NULL COMMENT '提交时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_exam_attempt_review`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `attempt_id` bigint NOT NULL COMMENT '作答尝试ID',
  MODIFY COLUMN `review_status` varchar(40) NOT NULL COMMENT '审核状态',
  MODIFY COLUMN `review_note` varchar(500) DEFAULT NULL COMMENT '审核备注',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_campaign`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `paper_id` bigint NOT NULL COMMENT '试卷ID',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `start_at` datetime NOT NULL COMMENT '开始时间',
  MODIFY COLUMN `due_at` datetime NOT NULL COMMENT '到期时间',
  MODIFY COLUMN `target_roles` varchar(255) DEFAULT NULL COMMENT '目标角色列表',
  MODIFY COLUMN `created_by` bigint NOT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `published_by` bigint NOT NULL COMMENT '发布人用户ID',
  MODIFY COLUMN `published_at` datetime NOT NULL COMMENT '发布时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_paper`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `paper_code` varchar(80) NOT NULL COMMENT '试卷编码',
  MODIFY COLUMN `paper_name` varchar(160) NOT NULL COMMENT '试卷名称',
  MODIFY COLUMN `role_scope` varchar(160) DEFAULT NULL COMMENT '角色适用范围',
  MODIFY COLUMN `pass_score` decimal(8,2) NOT NULL COMMENT '及格分数',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_paper_question_link`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `paper_question_id` bigint NOT NULL COMMENT '试卷题目ID',
  MODIFY COLUMN `bank_question_id` bigint NOT NULL COMMENT '题库题目ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_exam_question`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `paper_id` bigint NOT NULL COMMENT '试卷ID',
  MODIFY COLUMN `question_type` varchar(40) NOT NULL COMMENT '题目类型',
  MODIFY COLUMN `question_text` text NOT NULL COMMENT '题目内容',
  MODIFY COLUMN `options_json` longtext COMMENT '选项列表（JSON）',
  MODIFY COLUMN `standard_answer` text NOT NULL COMMENT '标准答案',
  MODIFY COLUMN `accept_keywords` text COMMENT '可接受答案关键词',
  MODIFY COLUMN `score` decimal(8,2) NOT NULL COMMENT '题目分值',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_question_bank`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `question_code` varchar(100) NOT NULL COMMENT '题目编码',
  MODIFY COLUMN `category_id` bigint DEFAULT NULL COMMENT '分类ID',
  MODIFY COLUMN `question_type` varchar(40) NOT NULL COMMENT '题目类型',
  MODIFY COLUMN `question_text` text NOT NULL COMMENT '题目内容',
  MODIFY COLUMN `options_json` longtext COMMENT '选项列表（JSON）',
  MODIFY COLUMN `standard_answer` text COMMENT '标准答案',
  MODIFY COLUMN `answer_analysis` text COMMENT '答案解析',
  MODIFY COLUMN `accept_keywords` text COMMENT '可接受答案关键词',
  MODIFY COLUMN `difficulty` varchar(20) NOT NULL COMMENT '难度',
  MODIFY COLUMN `default_score` decimal(8,2) NOT NULL COMMENT '默认分数',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_question_category`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `category_code` varchar(80) NOT NULL COMMENT '分类编码',
  MODIFY COLUMN `category_name` varchar(120) NOT NULL COMMENT '分类名称',
  MODIFY COLUMN `description` varchar(500) DEFAULT NULL COMMENT '描述',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_exam_wrong_question`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `attempt_id` bigint NOT NULL COMMENT '作答尝试ID',
  MODIFY COLUMN `question_id` bigint NOT NULL COMMENT '题目ID',
  MODIFY COLUMN `mastered` tinyint(1) NOT NULL COMMENT '是否已掌握',
  MODIFY COLUMN `mastered_at` datetime DEFAULT NULL COMMENT '掌握时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_learning_record`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `material_id` bigint NOT NULL COMMENT '培训资料ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `user_name` varchar(120) NOT NULL COMMENT '用户姓名',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `learned` tinyint(1) NOT NULL COMMENT '是否已学习',
  MODIFY COLUMN `learned_at` datetime NOT NULL COMMENT '学习完成时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `training_material`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `material_code` varchar(80) NOT NULL COMMENT '培训资料编码',
  MODIFY COLUMN `title` varchar(160) NOT NULL COMMENT '标题',
  MODIFY COLUMN `category` varchar(80) NOT NULL COMMENT '分类',
  MODIFY COLUMN `image_urls` longtext COMMENT '图片URL列表',
  MODIFY COLUMN `content` text COMMENT '培训资料正文',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `training_video`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `video_code` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '视频编码',
  MODIFY COLUMN `attachment_id` bigint NOT NULL COMMENT '附件ID',
  MODIFY COLUMN `course_id` bigint DEFAULT NULL COMMENT '课程ID',
  MODIFY COLUMN `title` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  MODIFY COLUMN `category` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类',
  MODIFY COLUMN `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  MODIFY COLUMN `duration_seconds` decimal(10,2) DEFAULT NULL COMMENT '时长（秒）',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `created_by` bigint NOT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `training_video_progress`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `video_id` bigint NOT NULL COMMENT '视频ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `user_name` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户姓名',
  MODIFY COLUMN `store_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `watched_seconds` decimal(10,2) NOT NULL COMMENT '已观看秒数',
  MODIFY COLUMN `duration_seconds` decimal(10,2) DEFAULT NULL COMMENT '时长（秒）',
  MODIFY COLUMN `progress_percent` decimal(5,2) NOT NULL COMMENT '完成进度百分比',
  MODIFY COLUMN `last_position` decimal(10,2) NOT NULL COMMENT '最近播放位置',
  MODIFY COLUMN `completed` tinyint(1) NOT NULL COMMENT '是否完成',
  MODIFY COLUMN `completed_at` timestamp NULL DEFAULT NULL COMMENT '完成时间',
  MODIFY COLUMN `last_reported_at` timestamp NULL DEFAULT NULL COMMENT '最近上报时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_data_scope`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `domain_code` varchar(40) NOT NULL COMMENT '业务域编码',
  MODIFY COLUMN `scope_type` varchar(32) NOT NULL COMMENT '范围类型',
  MODIFY COLUMN `scope_value_json` longtext COMMENT '数据范围值（JSON）',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

SET @v56_comment_sql = (
  SELECT CASE
    WHEN column_type = 'datetime'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND extra = ''
      THEN 'ALTER TABLE `user_data_scope` MODIFY COLUMN `updated_at` datetime NULL DEFAULT NULL COMMENT ''更新时间'''
    WHEN column_type = 'timestamp'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND LOWER(extra) = 'on update current_timestamp'
      THEN 'ALTER TABLE `user_data_scope` MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'''
    ELSE 'ALTER TABLE `__v56_unsupported_user_data_scope_updated_at__` ADD COLUMN `definition_mismatch` int'
  END
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'user_data_scope'
    AND column_name = 'updated_at'
);
PREPARE v56_comment_stmt FROM @v56_comment_sql;
EXECUTE v56_comment_stmt;
DEALLOCATE PREPARE v56_comment_stmt;

ALTER TABLE `user_permission_override`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `permission_code` varchar(120) NOT NULL COMMENT '权限代码',
  MODIFY COLUMN `effect` varchar(16) NOT NULL COMMENT '权限覆盖效果（ALLOW允许、DENY拒绝）',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

SET @v56_comment_sql = (
  SELECT CASE
    WHEN column_type = 'datetime'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND extra = ''
      THEN 'ALTER TABLE `user_permission_override` MODIFY COLUMN `updated_at` datetime NULL DEFAULT NULL COMMENT ''更新时间'''
    WHEN column_type = 'timestamp'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND LOWER(extra) = 'on update current_timestamp'
      THEN 'ALTER TABLE `user_permission_override` MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT ''更新时间'''
    ELSE 'ALTER TABLE `__v56_unsupported_user_permission_override_updated_at__` ADD COLUMN `definition_mismatch` int'
  END
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'user_permission_override'
    AND column_name = 'updated_at'
);
PREPARE v56_comment_stmt FROM @v56_comment_sql;
EXECUTE v56_comment_stmt;
DEALLOCATE PREPARE v56_comment_stmt;

ALTER TABLE `user_store_scope`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_alert`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `alert_type` varchar(60) NOT NULL COMMENT '预警类型',
  MODIFY COLUMN `severity` varchar(40) NOT NULL COMMENT '严重程度',
  MODIFY COLUMN `item_id` bigint DEFAULT NULL COMMENT '商品ID',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `source_type` varchar(60) DEFAULT NULL COMMENT '来源类型',
  MODIFY COLUMN `source_id` varchar(120) DEFAULT NULL COMMENT '来源ID',
  MODIFY COLUMN `message` varchar(500) NOT NULL COMMENT '消息内容',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `handled_at` timestamp NULL DEFAULT NULL COMMENT '处理时间';

ALTER TABLE `warehouse_attachment`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `business_type` varchar(60) NOT NULL COMMENT '业务类型',
  MODIFY COLUMN `business_id` varchar(120) NOT NULL COMMENT '业务ID',
  MODIFY COLUMN `file_name` varchar(255) NOT NULL COMMENT '文件名称',
  MODIFY COLUMN `content_type` varchar(120) DEFAULT NULL COMMENT '内容类型',
  MODIFY COLUMN `file_size` bigint DEFAULT NULL COMMENT '文件大小（字节）',
  MODIFY COLUMN `storage_path` varchar(500) DEFAULT NULL COMMENT '存储路径',
  MODIFY COLUMN `content` longblob COMMENT '附件二进制内容',
  MODIFY COLUMN `uploaded_by` bigint DEFAULT NULL COMMENT '上传人用户ID',
  MODIFY COLUMN `uploaded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间';

ALTER TABLE `warehouse_delivery_order`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `requisition_id` varchar(120) NOT NULL COMMENT '叫货单ID',
  MODIFY COLUMN `store_id` varchar(64) NOT NULL COMMENT '门店ID',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `shipped_by` bigint DEFAULT NULL COMMENT '发货人用户ID',
  MODIFY COLUMN `received_by` bigint DEFAULT NULL COMMENT '收货人用户ID',
  MODIFY COLUMN `shipped_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发货时间',
  MODIFY COLUMN `received_at` timestamp NULL DEFAULT NULL COMMENT '收货时间',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `warehouse_delivery_order_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `delivery_id` varchar(120) NOT NULL COMMENT '配送ID',
  MODIFY COLUMN `requisition_line_id` bigint DEFAULT NULL COMMENT '叫货单明细ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `shipped_quantity` decimal(14,2) NOT NULL COMMENT '发货数量',
  MODIFY COLUMN `received_quantity` decimal(14,2) NOT NULL COMMENT '实收数量',
  MODIFY COLUMN `unit_price` decimal(14,2) NOT NULL COMMENT '单位价格',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `warehouse_facility`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '编码',
  MODIFY COLUMN `name` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '仓库名称',
  MODIFY COLUMN `warehouse_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '仓库类型',
  MODIFY COLUMN `region_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '区域编码',
  MODIFY COLUMN `parent_warehouse_id` bigint DEFAULT NULL COMMENT '上级仓库ID',
  MODIFY COLUMN `external_purchase_allowed` tinyint(1) NOT NULL COMMENT '是否允许外部采购',
  MODIFY COLUMN `store_supply_allowed` tinyint(1) NOT NULL COMMENT '是否允许门店供货',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_by` bigint DEFAULT NULL COMMENT '更新人用户ID',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `warehouse_inventory`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `on_hand_quantity` decimal(14,2) NOT NULL COMMENT '现有库存数量',
  MODIFY COLUMN `reserved_quantity` decimal(14,2) NOT NULL COMMENT '预留数量',
  MODIFY COLUMN `in_transit_quantity` decimal(14,2) NOT NULL COMMENT '在途数量',
  MODIFY COLUMN `unit_cost` decimal(18,4) NOT NULL COMMENT '单位成本',
  MODIFY COLUMN `min_stock_quantity` decimal(14,2) NOT NULL COMMENT '最低库存数量',
  MODIFY COLUMN `alert_enabled` tinyint(1) NOT NULL COMMENT '是否启用预警',
  MODIFY COLUMN `expiry_alert_days` int COMMENT '临期预警天数',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `warehouse_item`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `code` varchar(80) NOT NULL COMMENT '编码',
  MODIFY COLUMN `name` varchar(160) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `category` varchar(80) DEFAULT NULL COMMENT '分类',
  MODIFY COLUMN `category_id` bigint DEFAULT NULL COMMENT '分类ID',
  MODIFY COLUMN `image_url` mediumtext COMMENT '图片URL',
  MODIFY COLUMN `unit` varchar(40) NOT NULL COMMENT '单位',
  MODIFY COLUMN `purchase_unit` varchar(40) DEFAULT NULL COMMENT '采购单位',
  MODIFY COLUMN `stock_unit` varchar(40) DEFAULT NULL COMMENT '库存单位',
  MODIFY COLUMN `ingredient_unit` varchar(40) DEFAULT NULL COMMENT '原料单位',
  MODIFY COLUMN `unit_conversion_text` varchar(160) DEFAULT NULL COMMENT '单位换算说明',
  MODIFY COLUMN `spec` varchar(160) DEFAULT NULL COMMENT '规格',
  MODIFY COLUMN `warehouse_location` varchar(120) DEFAULT NULL COMMENT '仓库位置',
  MODIFY COLUMN `unit_price` decimal(14,2) NOT NULL COMMENT '单位价格',
  MODIFY COLUMN `shelf_life_days` int DEFAULT NULL COMMENT '保质期天数',
  MODIFY COLUMN `cups_per_unit` decimal(14,2) NOT NULL COMMENT '每单位杯数',
  MODIFY COLUMN `daily_usage_estimate` decimal(14,2) NOT NULL COMMENT '日均用量估算',
  MODIFY COLUMN `min_stock_days` int NOT NULL COMMENT '最低库存天数',
  MODIFY COLUMN `max_stock_days` int NOT NULL COMMENT '最高库存天数',
  MODIFY COLUMN `min_stock_quantity` decimal(14,2) NOT NULL COMMENT '最低库存数量',
  MODIFY COLUMN `alert_enabled` tinyint(1) NOT NULL COMMENT '是否启用预警',
  MODIFY COLUMN `expiry_alert_days` int COMMENT '临期预警天数',
  MODIFY COLUMN `item_description` text COMMENT '商品描述',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `item_attributes` varchar(255) DEFAULT NULL COMMENT '商品属性',
  MODIFY COLUMN `active` tinyint(1) NOT NULL COMMENT '是否生效',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_item_category`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `name` varchar(120) NOT NULL COMMENT '商品分类名称',
  MODIFY COLUMN `parent_id` bigint DEFAULT NULL COMMENT '上级ID',
  MODIFY COLUMN `sort_order` int NOT NULL COMMENT '排序序号',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_item_department`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `department_name` varchar(120) NOT NULL COMMENT '部门名称',
  MODIFY COLUMN `department_code` varchar(80) DEFAULT NULL COMMENT '部门编码',
  MODIFY COLUMN `department_group` varchar(120) DEFAULT NULL COMMENT '部门分组',
  MODIFY COLUMN `purchase_method` varchar(120) DEFAULT NULL COMMENT '采购方式',
  MODIFY COLUMN `supplier_name` varchar(160) DEFAULT NULL COMMENT '供应商名称',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_purchase_order`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '幂等键',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `total_amount` decimal(14,2) NOT NULL COMMENT '总金额',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `received_by` bigint DEFAULT NULL COMMENT '收货人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `received_at` timestamp NULL DEFAULT NULL COMMENT '收货时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_purchase_order_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `purchase_order_id` varchar(120) NOT NULL COMMENT '采购单ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `ordered_quantity` decimal(14,2) NOT NULL COMMENT '下单数量',
  MODIFY COLUMN `received_quantity` decimal(14,2) NOT NULL COMMENT '实收数量',
  MODIFY COLUMN `unit_cost` decimal(14,2) NOT NULL COMMENT '单位成本',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `warehouse_request_dedup`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `request_type` varchar(60) NOT NULL COMMENT '申请类型',
  MODIFY COLUMN `request_key` varchar(80) NOT NULL COMMENT '请求唯一键',
  MODIFY COLUMN `business_id` varchar(120) DEFAULT NULL COMMENT '业务ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_return_order`
  MODIFY COLUMN `id` varchar(120) NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `receive_warehouse_code_snapshot` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收货仓库编码快照',
  MODIFY COLUMN `receive_warehouse_name_snapshot` varchar(160) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收货仓库名称快照',
  MODIFY COLUMN `return_no` varchar(120) NOT NULL COMMENT '退货编号',
  MODIFY COLUMN `source_requisition_id` varchar(120) DEFAULT NULL COMMENT '来源叫货单ID',
  MODIFY COLUMN `source_delivery_id` varchar(120) DEFAULT NULL COMMENT '来源配送单ID',
  MODIFY COLUMN `return_store_id` varchar(64) NOT NULL COMMENT '退货门店ID',
  MODIFY COLUMN `return_store_name` varchar(160) NOT NULL COMMENT '退货门店名称',
  MODIFY COLUMN `receive_department` varchar(120) NOT NULL COMMENT '收货部门',
  MODIFY COLUMN `status` varchar(40) NOT NULL COMMENT '状态',
  MODIFY COLUMN `total_amount` decimal(14,2) NOT NULL COMMENT '总金额',
  MODIFY COLUMN `reason` text COMMENT '原因',
  MODIFY COLUMN `handled_by` varchar(500) DEFAULT NULL COMMENT '处理人记录',
  MODIFY COLUMN `created_by` varchar(120) DEFAULT NULL COMMENT '创建人姓名',
  MODIFY COLUMN `updated_by` varchar(120) DEFAULT NULL COMMENT '更新人姓名',
  MODIFY COLUMN `reviewed_by` varchar(120) DEFAULT NULL COMMENT '审核人姓名',
  MODIFY COLUMN `checked_by` varchar(120) DEFAULT NULL COMMENT '核对人姓名',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `review_note` text COMMENT '审核备注',
  MODIFY COLUMN `received_note` text COMMENT '收货备注',
  MODIFY COLUMN `return_date` date NOT NULL COMMENT '退货日期',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `received_at` timestamp NULL DEFAULT NULL COMMENT '收货时间',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_return_order_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `return_order_id` varchar(120) NOT NULL COMMENT '退货单ID',
  MODIFY COLUMN `source_requisition_line_id` bigint DEFAULT NULL COMMENT '来源叫货明细ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `item_name` varchar(160) NOT NULL COMMENT '商品名称',
  MODIFY COLUMN `spec` varchar(160) DEFAULT NULL COMMENT '规格',
  MODIFY COLUMN `batch_id` bigint DEFAULT NULL COMMENT '批次ID',
  MODIFY COLUMN `batch_no` varchar(120) DEFAULT NULL COMMENT '批次编号',
  MODIFY COLUMN `quantity` decimal(14,2) NOT NULL COMMENT '数量',
  MODIFY COLUMN `unit` varchar(40) NOT NULL COMMENT '单位',
  MODIFY COLUMN `unit_price` decimal(14,2) NOT NULL COMMENT '单位价格',
  MODIFY COLUMN `return_price` decimal(14,2) NOT NULL COMMENT '退货单价',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `reason` text COMMENT '原因',
  MODIFY COLUMN `note` text COMMENT '备注';

ALTER TABLE `warehouse_return_snapshot_backfill_audit`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `migration_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '迁移唯一键',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `return_order_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '退货单ID',
  MODIFY COLUMN `warehouse_id` bigint DEFAULT NULL COMMENT '仓库ID',
  MODIFY COLUMN `original_code_snapshot` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始编码快照',
  MODIFY COLUMN `original_name_snapshot` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '原始名称快照',
  MODIFY COLUMN `failure_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '失败编码',
  MODIFY COLUMN `failure_message` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '失败原因说明',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `last_detected_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近检测时间';

ALTER TABLE `warehouse_stock_adjustment`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `batch_id` bigint DEFAULT NULL COMMENT '批次ID',
  MODIFY COLUMN `adjustment_type` varchar(40) NOT NULL COMMENT '调整类型',
  MODIFY COLUMN `quantity_delta` decimal(14,2) NOT NULL COMMENT '数量变动值',
  MODIFY COLUMN `reason` text COMMENT '原因',
  MODIFY COLUMN `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_stock_batch`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `batch_no` varchar(120) NOT NULL COMMENT '批次编号',
  MODIFY COLUMN `received_date` date NOT NULL COMMENT '实收日期',
  MODIFY COLUMN `expiry_date` date DEFAULT NULL COMMENT '到期日期',
  MODIFY COLUMN `quantity` decimal(14,2) NOT NULL COMMENT '数量',
  MODIFY COLUMN `reserved_quantity` decimal(14,2) NOT NULL COMMENT '预留数量',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `unit_cost` decimal(14,2) NOT NULL COMMENT '单位成本',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_stock_movement`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `warehouse_id` bigint NOT NULL COMMENT '仓库ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `batch_id` bigint DEFAULT NULL COMMENT '批次ID',
  MODIFY COLUMN `movement_type` varchar(40) NOT NULL COMMENT '流水类型',
  MODIFY COLUMN `quantity_delta` decimal(14,2) NOT NULL COMMENT '数量变动值',
  MODIFY COLUMN `reserved_quantity_delta` decimal(14,2) NOT NULL COMMENT '预留数量变动值',
  MODIFY COLUMN `in_transit_quantity_delta` decimal(14,2) NOT NULL COMMENT '在途数量变动值',
  MODIFY COLUMN `unit_cost` decimal(14,2) DEFAULT NULL COMMENT '单位成本',
  MODIFY COLUMN `source_type` varchar(60) DEFAULT NULL COMMENT '来源类型',
  MODIFY COLUMN `source_id` varchar(120) DEFAULT NULL COMMENT '来源ID',
  MODIFY COLUMN `store_id` varchar(64) DEFAULT NULL COMMENT '门店ID',
  MODIFY COLUMN `note` text COMMENT '备注',
  MODIFY COLUMN `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_supplier`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `name` varchar(160) NOT NULL COMMENT '供应商名称',
  MODIFY COLUMN `contact_name` varchar(80) DEFAULT NULL COMMENT '联系人姓名',
  MODIFY COLUMN `phone` varchar(80) DEFAULT NULL COMMENT '联系电话',
  MODIFY COLUMN `settlement_cycle` varchar(80) DEFAULT NULL COMMENT '结算周期',
  MODIFY COLUMN `active` tinyint(1) NOT NULL COMMENT '是否生效',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `warehouse_topology_migration_audit`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `migration_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '迁移唯一键',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `expected_business_store_count` int NOT NULL COMMENT '预期业务门店数量',
  MODIFY COLUMN `actual_business_store_count` int NOT NULL COMMENT '实际业务门店数量',
  MODIFY COLUMN `bound_store_count` int NOT NULL COMMENT '已绑定门店数量',
  MODIFY COLUMN `central_warehouse_id` bigint NOT NULL COMMENT '中央仓库ID',
  MODIFY COLUMN `regional_warehouse_id` bigint NOT NULL COMMENT '区域仓库ID',
  MODIFY COLUMN `binding_status` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '绑定状态',
  MODIFY COLUMN `difference_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '差异说明',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_transfer_action`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `transfer_order_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调拨单ID',
  MODIFY COLUMN `action_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型',
  MODIFY COLUMN `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '幂等键',
  MODIFY COLUMN `result_version` bigint NOT NULL COMMENT '结果版本号',
  MODIFY COLUMN `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

ALTER TABLE `warehouse_transfer_line`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `transfer_order_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调拨单ID',
  MODIFY COLUMN `item_id` bigint NOT NULL COMMENT '商品ID',
  MODIFY COLUMN `requested_quantity` decimal(14,2) NOT NULL COMMENT '申请数量',
  MODIFY COLUMN `approved_quantity` decimal(14,2) NOT NULL COMMENT '审批通过数量',
  MODIFY COLUMN `reserved_quantity` decimal(14,2) NOT NULL COMMENT '预留数量',
  MODIFY COLUMN `shipped_quantity` decimal(14,2) NOT NULL COMMENT '发货数量',
  MODIFY COLUMN `received_quantity` decimal(14,2) NOT NULL COMMENT '实收数量',
  MODIFY COLUMN `in_transit_quantity` decimal(14,2) NOT NULL COMMENT '在途数量',
  MODIFY COLUMN `unit_cost` decimal(18,4) NOT NULL COMMENT '单位成本',
  MODIFY COLUMN `amount` decimal(14,2) NOT NULL COMMENT '金额',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `note` text COLLATE utf8mb4_unicode_ci COMMENT '备注',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `warehouse_transfer_order`
  MODIFY COLUMN `id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `transfer_no` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '调拨编号',
  MODIFY COLUMN `source_warehouse_id` bigint NOT NULL COMMENT '来源仓库ID',
  MODIFY COLUMN `target_warehouse_id` bigint NOT NULL COMMENT '目标仓库ID',
  MODIFY COLUMN `status` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  MODIFY COLUMN `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '幂等键',
  MODIFY COLUMN `total_amount` decimal(14,2) NOT NULL COMMENT '总金额',
  MODIFY COLUMN `version` bigint NOT NULL COMMENT '版本号',
  MODIFY COLUMN `note` text COLLATE utf8mb4_unicode_ci COMMENT '备注',
  MODIFY COLUMN `review_note` text COLLATE utf8mb4_unicode_ci COMMENT '审核备注',
  MODIFY COLUMN `requested_by` bigint DEFAULT NULL COMMENT '申请人用户ID',
  MODIFY COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT '审核人用户ID',
  MODIFY COLUMN `shipped_by` bigint DEFAULT NULL COMMENT '发货人用户ID',
  MODIFY COLUMN `received_by` bigint DEFAULT NULL COMMENT '收货人用户ID',
  MODIFY COLUMN `cancelled_by` bigint DEFAULT NULL COMMENT '取消人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `submitted_at` timestamp NULL DEFAULT NULL COMMENT '提交时间',
  MODIFY COLUMN `reviewed_at` timestamp NULL DEFAULT NULL COMMENT '审核时间',
  MODIFY COLUMN `shipped_at` timestamp NULL DEFAULT NULL COMMENT '发货时间',
  MODIFY COLUMN `received_at` timestamp NULL DEFAULT NULL COMMENT '收货时间',
  MODIFY COLUMN `cancelled_at` timestamp NULL DEFAULT NULL COMMENT '取消时间',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `warehouse_transfer_route`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `tenant_id` bigint NOT NULL COMMENT '租户ID',
  MODIFY COLUMN `source_warehouse_id` bigint NOT NULL COMMENT '来源仓库ID',
  MODIFY COLUMN `target_warehouse_id` bigint NOT NULL COMMENT '目标仓库ID',
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL COMMENT '是否启用',
  MODIFY COLUMN `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
  MODIFY COLUMN `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_by` bigint DEFAULT NULL COMMENT '更新人用户ID',
  MODIFY COLUMN `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
