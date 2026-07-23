-- One-time initialization of the isolated real-data database.
-- Source: ai_profit_os_qa_38stores (audited staging database)
-- Target: ai_profit_os_real_qa (new empty database with Flyway V1-V91 applied)
--
-- Never run this against an active database. The guard below rejects a target
-- that already contains accounts or verified business master data.

delimiter //
create procedure ai_profit_os_real_qa.assert_empty_real_target()
begin
  if (select count(*) from ai_profit_os_real_qa.auth_user) > 0
     or (select count(*) from ai_profit_os_real_qa.warehouse_item) > 0
     or (select count(*) from ai_profit_os_real_qa.daily_loss_monthly_archive) > 0
     or (select count(*) from ai_profit_os_real_qa.knowledge_base_document) > 0 then
    signal sqlstate '45000'
      set message_text = 'Target real database is not empty; initialization refused';
  end if;
end//
delimiter ;

call ai_profit_os_real_qa.assert_empty_real_target();
drop procedure ai_profit_os_real_qa.assert_empty_real_target;

set foreign_key_checks = 0;
start transaction;

-- Remove migration fixtures that are not approved real business data.
delete from ai_profit_os_real_qa.training_video_progress;
delete from ai_profit_os_real_qa.training_video;
delete from ai_profit_os_real_qa.training_learning_record;
delete from ai_profit_os_real_qa.training_course_material;
delete from ai_profit_os_real_qa.training_course;
delete from ai_profit_os_real_qa.training_exam_wrong_question;
delete from ai_profit_os_real_qa.training_exam_answer_review;
delete from ai_profit_os_real_qa.training_exam_attempt_review;
delete from ai_profit_os_real_qa.training_exam_answer;
delete from ai_profit_os_real_qa.training_exam_attempt;
delete from ai_profit_os_real_qa.training_exam_assignment;
delete from ai_profit_os_real_qa.training_exam_campaign;
delete from ai_profit_os_real_qa.training_exam_paper_question_link;
delete from ai_profit_os_real_qa.training_exam_question_bank;
delete from ai_profit_os_real_qa.training_exam_question_category;
delete from ai_profit_os_real_qa.training_exam_question;
delete from ai_profit_os_real_qa.training_exam_paper;
delete from ai_profit_os_real_qa.training_material;
delete from ai_profit_os_real_qa.employee_salary_profile;
delete from ai_profit_os_real_qa.salary_policy;
delete from ai_profit_os_real_qa.warehouse_stock_batch;
delete from ai_profit_os_real_qa.warehouse_item_category;

-- The tenant row is infrastructure, not a demo company identity.
update ai_profit_os_real_qa.tenant
set name = '门店经营真实库',
    industry = 'chain_store',
    scale = 'real',
    status = 'ACTIVE',
    updated_at = current_timestamp
where id = 1;

-- Approved organization and access configuration. Existing passwords are
-- retained only for the first sign-in; every account must choose a new one.
insert into ai_profit_os_real_qa.brand
select * from ai_profit_os_qa_38stores.brand;

insert into ai_profit_os_real_qa.store_branch
select * from ai_profit_os_qa_38stores.store_branch;

insert into ai_profit_os_real_qa.auth_user(
  id, tenant_id, username, password_hash, display_name, role, store_id,
  enabled, permission_version, created_at, updated_at, password_change_required
)
select id, tenant_id, username, password_hash, display_name, role, store_id,
       enabled, permission_version + 1, created_at, current_timestamp, 1
from ai_profit_os_qa_38stores.auth_user;

insert into ai_profit_os_real_qa.user_store_scope
select * from ai_profit_os_qa_38stores.user_store_scope;

insert into ai_profit_os_real_qa.user_data_scope
select * from ai_profit_os_qa_38stores.user_data_scope;

insert into ai_profit_os_real_qa.user_permission_override
select * from ai_profit_os_qa_38stores.user_permission_override;

-- Verified warehouse material workbook: 411 unique material codes.
insert into ai_profit_os_real_qa.warehouse_item_category
select * from ai_profit_os_qa_38stores.warehouse_item_category;

insert into ai_profit_os_real_qa.warehouse_item
select * from ai_profit_os_qa_38stores.warehouse_item;

insert into ai_profit_os_real_qa.warehouse_item_department
select * from ai_profit_os_qa_38stores.warehouse_item_department;

-- Verified daily-loss definitions and the ten monthly source archives.
insert into ai_profit_os_real_qa.loss_item_config
select * from ai_profit_os_qa_38stores.loss_item_config;

insert into ai_profit_os_real_qa.daily_loss_monthly_archive
select * from ai_profit_os_qa_38stores.daily_loss_monthly_archive;

insert into ai_profit_os_real_qa.daily_loss_monthly_archive_store
select * from ai_profit_os_qa_38stores.daily_loss_monthly_archive_store;

insert into ai_profit_os_real_qa.daily_loss_monthly_archive_item
select * from ai_profit_os_qa_38stores.daily_loss_monthly_archive_item;

insert into ai_profit_os_real_qa.daily_loss_monthly_archive_store_item
select * from ai_profit_os_qa_38stores.daily_loss_monthly_archive_store_item;

-- Published real workbook and its local 384-dimensional vector index.
insert into ai_profit_os_real_qa.knowledge_base_document
select * from ai_profit_os_qa_38stores.knowledge_base_document;

insert into ai_profit_os_real_qa.knowledge_base_document_role_scope
select * from ai_profit_os_qa_38stores.knowledge_base_document_role_scope;

insert into ai_profit_os_real_qa.knowledge_base_document_store_scope
select * from ai_profit_os_qa_38stores.knowledge_base_document_store_scope;

insert into ai_profit_os_real_qa.knowledge_base_chunk
select * from ai_profit_os_qa_38stores.knowledge_base_chunk;

-- Fresh provenance audit; QA/test operation logs are intentionally excluded.
insert into ai_profit_os_real_qa.operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id,
  after_json, reason, created_at
) values
  (1, 47, '系统上线', 'real_data.material_import', 'warehouse_item', '物品信息.xlsx',
   '{"rows":411,"sha256":"935c76bc3aae987f51e9bae792910818441a41233d00d713a3e57272151c9cc2","zeroPriceMeaning":"门店可向仓库免费叫货"}',
   '从用户确认的物品信息工作簿迁入真实库', current_timestamp),
  (1, 47, '系统上线', 'real_data.vector_import', 'knowledge_base_document', '1',
   '{"documents":1,"chunks":80,"dimensions":384,"sha256":"935c76bc3aae987f51e9bae792910818441a41233d00d713a3e57272151c9cc2"}',
   '迁入已发布物料知识库及本地向量索引', current_timestamp),
  (1, 47, '系统上线', 'real_data.loss_archive_import', 'daily_loss_monthly_archive', '2025-08..2026-06',
   '{"months":10,"storeRows":157,"itemRows":163,"quantityCells":1761,"sha256":"d5dd1714216f71480f028389c7fbe1de7f431f5e8a8512454f1585fd99959f7e"}',
   '按用户确认年份迁入月度报损原始档案', current_timestamp),
  (1, 47, '系统上线', 'real_database.initialized', 'database', 'ai_profit_os_real_qa',
   '{"source":"verified-only","excluded":"profit,salary,expense,inspection,todo,warehouse-flow-test-data"}',
   '真实库初始化完成，未迁入演示交易', current_timestamp);

commit;
set foreign_key_checks = 1;

-- Compact reconciliation output used by the rollout script.
select
  (select count(*) from ai_profit_os_real_qa.store_branch) as stores,
  (select count(*) from ai_profit_os_real_qa.auth_user) as users,
  (select count(*) from ai_profit_os_real_qa.warehouse_item) as materials,
  (select count(*) from ai_profit_os_real_qa.loss_item_config) as loss_items,
  (select count(*) from ai_profit_os_real_qa.daily_loss_monthly_archive) as loss_months,
  (select count(*) from ai_profit_os_real_qa.knowledge_base_document) as vector_documents,
  (select count(*) from ai_profit_os_real_qa.knowledge_base_chunk) as vector_chunks;
