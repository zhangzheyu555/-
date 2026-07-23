-- 本机 QA 测试数据：仅可用于 ai_profit_dev_qa，禁止用于生产或服务器数据库。
-- 所有新增记录使用 LOCAL-QA-20260722 前缀，可按该前缀清理。
USE ai_profit_dev_qa;
START TRANSACTION;

SET @tenant_id := 1;
SET @warehouse_id := (SELECT id FROM warehouse_facility WHERE tenant_id = @tenant_id AND code = 'JZ-CENTRAL' LIMIT 1);
SET @supplier_id := (SELECT id FROM warehouse_supplier WHERE tenant_id = @tenant_id AND active = 1 ORDER BY id LIMIT 1);
SET @warehouse_user_id := (SELECT id FROM auth_user WHERE tenant_id = @tenant_id AND username = 'warehouse' LIMIT 1);
SET @boss_user_id := (SELECT id FROM auth_user WHERE tenant_id = @tenant_id AND username = 'boss' LIMIT 1);
SET @item_a := (SELECT id FROM warehouse_item WHERE tenant_id = @tenant_id AND active = 1 ORDER BY id LIMIT 1);
SET @item_b := (SELECT id FROM warehouse_item WHERE tenant_id = @tenant_id AND active = 1 ORDER BY id LIMIT 1 OFFSET 1);

-- 采购单：草稿、待入库、已入库三种状态，供仓库工作台和待办筛选测试。
DELETE FROM warehouse_purchase_order_line WHERE tenant_id = @tenant_id AND purchase_order_id LIKE 'LOCAL-QA-20260722-PO-%';
DELETE FROM warehouse_purchase_order WHERE tenant_id = @tenant_id AND id LIKE 'LOCAL-QA-20260722-PO-%';
INSERT INTO warehouse_purchase_order(
  id, tenant_id, warehouse_id, idempotency_key, version, supplier_id, status,
  total_amount, note, created_by, received_by, created_at, received_at, updated_at
) VALUES
  ('LOCAL-QA-20260722-PO-DRAFT', @tenant_id, @warehouse_id, 'LOCAL-QA-20260722-PO-DRAFT', 0, @supplier_id, 'DRAFT', 238.00, '本机虚拟采购单：草稿状态', @warehouse_user_id, NULL, current_timestamp, NULL, current_timestamp),
  ('LOCAL-QA-20260722-PO-ORDERED', @tenant_id, @warehouse_id, 'LOCAL-QA-20260722-PO-ORDERED', 0, @supplier_id, 'ORDERED', 476.00, '本机虚拟采购单：待入库状态', @warehouse_user_id, NULL, current_timestamp, NULL, current_timestamp),
  ('LOCAL-QA-20260722-PO-RECEIVED', @tenant_id, @warehouse_id, 'LOCAL-QA-20260722-PO-RECEIVED', 1, @supplier_id, 'RECEIVED', 714.00, '本机虚拟采购单：已入库状态', @warehouse_user_id, @boss_user_id, current_timestamp, current_timestamp, current_timestamp);
INSERT INTO warehouse_purchase_order_line(tenant_id, purchase_order_id, item_id, ordered_quantity, received_quantity, unit_cost, amount, note)
VALUES
  (@tenant_id, 'LOCAL-QA-20260722-PO-DRAFT', @item_a, 10, 0, 11.90, 119.00, '虚拟采购明细 A'),
  (@tenant_id, 'LOCAL-QA-20260722-PO-DRAFT', @item_b, 10, 0, 11.90, 119.00, '虚拟采购明细 B'),
  (@tenant_id, 'LOCAL-QA-20260722-PO-ORDERED', @item_a, 20, 0, 11.90, 238.00, '待到货明细 A'),
  (@tenant_id, 'LOCAL-QA-20260722-PO-ORDERED', @item_b, 20, 0, 11.90, 238.00, '待到货明细 B'),
  (@tenant_id, 'LOCAL-QA-20260722-PO-RECEIVED', @item_a, 30, 30, 11.90, 357.00, '已收货明细 A'),
  (@tenant_id, 'LOCAL-QA-20260722-PO-RECEIVED', @item_b, 30, 30, 11.90, 357.00, '已收货明细 B');

-- 巡检整改：以已有巡检记录构造“待复核”样本。
INSERT INTO inspection_rectification(
  id, tenant_id, inspection_record_id, store_id, status, manager_note,
  submitted_by, submitted_by_name, submitted_at, review_note,
  reviewed_by, reviewed_by_name, reviewed_at, version, created_at, updated_at
) VALUES (
  'LOCAL-QA-20260722-RECT-REVIEW', @tenant_id, 'e2e-seed-redline-inspection', 'rg1', 'SUBMITTED',
  '本机虚拟整改：已完成清洁、补齐留证，等待督导复核。', @warehouse_user_id, '本机测试用户', current_timestamp,
  NULL, NULL, NULL, NULL, 0, current_timestamp, current_timestamp
)
ON DUPLICATE KEY UPDATE status=VALUES(status), manager_note=VALUES(manager_note), submitted_at=VALUES(submitted_at), updated_at=current_timestamp;

-- 培训课程及资料：供培训中心、员工学习列表和角色筛选测试。
INSERT INTO training_material(tenant_id, material_code, title, category, image_urls, content, enabled, sort_order, created_at, updated_at)
VALUES
  (@tenant_id, 'LOCAL-QA-20260722-MAT-SERVICE', '本机测试：高峰服务规范', '门店服务', '[]', '用于本地测试的虚拟培训资料，不代表正式制度。', 1, 9001, current_timestamp, current_timestamp),
  (@tenant_id, 'LOCAL-QA-20260722-MAT-SAFETY', '本机测试：食品安全巡检', '食品安全', '[]', '用于本地测试的虚拟培训资料，不代表正式制度。', 1, 9002, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), enabled=1, updated_at=current_timestamp;
INSERT INTO training_course(tenant_id, course_code, title, category, description, cover_url, duration_minutes, required_role_scope, enabled, sort_order, created_by, created_at, updated_at)
VALUES
  (@tenant_id, 'LOCAL-QA-20260722-COURSE-SERVICE', '本机测试：门店高峰服务', '门店服务', '用于验证课程列表、详情与学习进度的虚拟课程。', NULL, 25, 'STORE_MANAGER,EMPLOYEE', 1, 9001, @boss_user_id, current_timestamp, current_timestamp),
  (@tenant_id, 'LOCAL-QA-20260722-COURSE-WAREHOUSE', '本机测试：仓库收货规范', '仓库管理', '用于验证仓库角色培训可见性的虚拟课程。', NULL, 35, 'WAREHOUSE', 1, 9002, @boss_user_id, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE title=VALUES(title), description=VALUES(description), enabled=1, updated_at=current_timestamp;

INSERT INTO operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, reason, created_at)
VALUES (@tenant_id, @boss_user_id, '本机测试数据', 'local_qa_fixture_seed', 'local_qa_fixture', 'LOCAL-QA-20260722', '补充采购、整改与培训模块虚拟测试数据', current_timestamp);

COMMIT;

SELECT 'purchase_orders' AS dataset, COUNT(*) AS total FROM warehouse_purchase_order WHERE tenant_id=@tenant_id AND id LIKE 'LOCAL-QA-20260722-%'
UNION ALL SELECT 'rectifications', COUNT(*) FROM inspection_rectification WHERE tenant_id=@tenant_id AND id LIKE 'LOCAL-QA-20260722-%'
UNION ALL SELECT 'courses', COUNT(*) FROM training_course WHERE tenant_id=@tenant_id AND course_code LIKE 'LOCAL-QA-20260722-%';
