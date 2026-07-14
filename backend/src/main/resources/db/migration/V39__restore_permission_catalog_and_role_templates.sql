-- Repair databases where legacy data was imported after V37 and emptied the permission data.
-- This migration is additive: existing catalog entries, role grants and user scopes are preserved.

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values
  ('system.dashboard.read', 'SYSTEM', '查看老板工作台', '进入老板工作台并查看全公司概览。', 'LOW', 1, 10),
  ('system.user.manage', 'SYSTEM', '管理账号权限', '创建账号、修改角色、配置权限和数据范围。', 'HIGH', 1, 20),
  ('system.audit.read', 'SYSTEM', '查看操作审计', '查看操作日志、权限拒绝和敏感操作记录。', 'HIGH', 1, 30),
  ('system.audit.write', 'SYSTEM', '补写操作审计', '补写经批准的系统操作审计记录。', 'HIGH', 1, 40),
  ('system.migration.manage', 'SYSTEM', '执行系统迁移', '执行受控的数据迁移和兼容处理。', 'HIGH', 1, 50),
  ('operations.dashboard.read', 'OPERATIONS', '查看运营工作台', '查看巡店、考试、平台和运营待办概览。', 'LOW', 1, 60),
  ('assistant.use', 'ASSISTANT', '使用经营助手', '在当前账号权限和数据范围内使用经营助手。', 'MEDIUM', 1, 70),
  ('store.read', 'STORE', '查看门店', '查看权限范围内的门店基础信息。', 'LOW', 1, 100),
  ('store.manage', 'STORE', '维护门店', '维护权限范围内的门店资料和运营配置。', 'MEDIUM', 1, 110),
  ('employee.read', 'EMPLOYEE', '查看员工', '查看权限范围内的员工基础资料。', 'MEDIUM', 1, 120),
  ('employee.manage', 'EMPLOYEE', '维护员工', '新增或维护权限范围内的员工资料。', 'HIGH', 1, 130),
  ('finance.profit.read', 'FINANCE', '查看利润', '查看权限范围内的经营利润和成本。', 'MEDIUM', 1, 200),
  ('finance.profit.write', 'FINANCE', '维护利润', '录入或修改权限范围内的经营数据。', 'HIGH', 1, 210),
  ('finance.profit.delete', 'FINANCE', '删除利润记录', '删除经确认可移除的经营利润记录。', 'HIGH', 1, 220),
  ('finance.export', 'FINANCE', '导出财务数据', '导出利润、工资或其他敏感经营数据。', 'HIGH', 1, 230),
  ('expense.create', 'EXPENSE', '提交报销', '创建报销单并补充报销资料。', 'MEDIUM', 1, 300),
  ('expense.read', 'EXPENSE', '查看报销', '查看权限范围内的报销单。', 'MEDIUM', 1, 310),
  ('expense.review', 'EXPENSE', '审核报销', '审核、退回或确认报销单。', 'HIGH', 1, 320),
  ('salary.read', 'SALARY', '查看工资', '查看权限范围内的工资记录。', 'HIGH', 1, 400),
  ('salary.edit', 'SALARY', '编辑工资', '生成或修改工资记录。', 'HIGH', 1, 410),
  ('salary.review', 'SALARY', '审核工资', '审核或退回工资记录。', 'HIGH', 1, 420),
  ('salary.pay', 'SALARY', '确认工资发放', '将审核通过的工资标记为已发放。', 'HIGH', 1, 430),
  ('warehouse.central.read', 'WAREHOUSE', '查看总仓库存', '查看总仓物料、批次、采购和出入库信息。', 'MEDIUM', 1, 500),
  ('warehouse.central.manage', 'WAREHOUSE', '管理总仓库存', '维护总仓物料、采购、入库、调拨、退货和库存。', 'HIGH', 1, 510),
  ('warehouse.store.read', 'WAREHOUSE', '查看门店库存', '查看权限范围内的门店库存和叫货信息。', 'LOW', 1, 520),
  ('warehouse.requisition.create', 'WAREHOUSE', '创建叫货申请', '为绑定门店创建叫货或配送退货申请。', 'MEDIUM', 1, 530),
  ('warehouse.requisition.review', 'WAREHOUSE', '审核叫货申请', '审核门店叫货申请并安排配送。', 'HIGH', 1, 540),
  ('warehouse.requisition.receive', 'WAREHOUSE', '确认门店收货', '确认绑定门店的配送收货结果。', 'MEDIUM', 1, 550),
  ('inventory.read', 'INVENTORY', '查看盘存', '查看权限范围内的盘存单。', 'LOW', 1, 560),
  ('inventory.manage', 'INVENTORY', '维护盘存', '创建或修改权限范围内的盘存单。', 'MEDIUM', 1, 570),
  ('inventory.review', 'INVENTORY', '复核盘存', '复核权限范围内的盘存单。', 'HIGH', 1, 580),
  ('inspection.read', 'INSPECTION', '查看巡店', '查看权限范围内的巡店记录和整改结果。', 'LOW', 1, 600),
  ('inspection.manage', 'INSPECTION', '管理巡店', '发起巡店、评分、整改复核和红线处理。', 'HIGH', 1, 610),
  ('exam.learn', 'EXAM', '参加培训考试', '查看本人课程、考试任务和学习记录。', 'LOW', 1, 700),
  ('exam.manage', 'EXAM', '管理培训考试', '维护课程、题库、考试发布和阅卷。', 'HIGH', 1, 710),
  ('exam.report', 'EXAM', '查看培训报表', '查看权限范围内的培训进度和考试报表。', 'MEDIUM', 1, 720),
  ('platform.read', 'PLATFORM', '查看平台配置', '查看权限范围内的平台配置和同步状态。', 'MEDIUM', 1, 800),
  ('platform.manage', 'PLATFORM', '管理平台授权', '维护第三方平台授权、同步配置和回调设置。', 'HIGH', 1, 810),
  ('attachment.read', 'ATTACHMENT', '查看附件', '查看权限范围内的业务附件。', 'MEDIUM', 1, 900),
  ('attachment.write', 'ATTACHMENT', '维护附件', '上传或补充权限范围内的业务附件。', 'HIGH', 1, 910),
  ('todo.read', 'TODO', '查看待办', '查看当前角色和数据范围内的业务待办。', 'LOW', 1, 1000),
  ('todo.transition', 'TODO', '处理待办', '领取、提交、复核、驳回或完成业务待办。', 'MEDIUM', 1, 1010)
on duplicate key update permission_code = values(permission_code);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, template.role_code, template.permission_code, current_timestamp
from tenant
join (
  select 'FINANCE' role_code, 'assistant.use' permission_code
  union all select 'FINANCE', 'store.read'
  union all select 'FINANCE', 'employee.read'
  union all select 'FINANCE', 'finance.profit.read'
  union all select 'FINANCE', 'finance.profit.write'
  union all select 'FINANCE', 'finance.export'
  union all select 'FINANCE', 'expense.create'
  union all select 'FINANCE', 'expense.read'
  union all select 'FINANCE', 'expense.review'
  union all select 'FINANCE', 'salary.read'
  union all select 'FINANCE', 'salary.edit'
  union all select 'FINANCE', 'salary.review'
  union all select 'FINANCE', 'salary.pay'
  union all select 'FINANCE', 'inventory.read'
  union all select 'FINANCE', 'attachment.read'
  union all select 'FINANCE', 'attachment.write'
  union all select 'FINANCE', 'todo.read'
  union all select 'FINANCE', 'todo.transition'
  union all select 'WAREHOUSE', 'assistant.use'
  union all select 'WAREHOUSE', 'store.read'
  union all select 'WAREHOUSE', 'warehouse.central.read'
  union all select 'WAREHOUSE', 'warehouse.central.manage'
  union all select 'WAREHOUSE', 'warehouse.store.read'
  union all select 'WAREHOUSE', 'warehouse.requisition.review'
  union all select 'WAREHOUSE', 'attachment.read'
  union all select 'WAREHOUSE', 'attachment.write'
  union all select 'WAREHOUSE', 'todo.read'
  union all select 'WAREHOUSE', 'todo.transition'
  union all select 'WAREHOUSE', 'exam.learn'
  union all select 'STORE_MANAGER', 'assistant.use'
  union all select 'STORE_MANAGER', 'store.read'
  union all select 'STORE_MANAGER', 'store.manage'
  union all select 'STORE_MANAGER', 'employee.read'
  union all select 'STORE_MANAGER', 'employee.manage'
  union all select 'STORE_MANAGER', 'finance.profit.read'
  union all select 'STORE_MANAGER', 'finance.profit.write'
  union all select 'STORE_MANAGER', 'expense.create'
  union all select 'STORE_MANAGER', 'expense.read'
  union all select 'STORE_MANAGER', 'salary.read'
  union all select 'STORE_MANAGER', 'warehouse.store.read'
  union all select 'STORE_MANAGER', 'warehouse.requisition.create'
  union all select 'STORE_MANAGER', 'warehouse.requisition.receive'
  union all select 'STORE_MANAGER', 'inventory.read'
  union all select 'STORE_MANAGER', 'inventory.manage'
  union all select 'STORE_MANAGER', 'inspection.read'
  union all select 'STORE_MANAGER', 'exam.learn'
  union all select 'STORE_MANAGER', 'exam.report'
  union all select 'STORE_MANAGER', 'attachment.read'
  union all select 'STORE_MANAGER', 'attachment.write'
  union all select 'STORE_MANAGER', 'todo.read'
  union all select 'STORE_MANAGER', 'todo.transition'
  union all select 'OPERATIONS', 'operations.dashboard.read'
  union all select 'OPERATIONS', 'assistant.use'
  union all select 'OPERATIONS', 'store.read'
  union all select 'OPERATIONS', 'store.manage'
  union all select 'OPERATIONS', 'employee.read'
  union all select 'OPERATIONS', 'warehouse.store.read'
  union all select 'OPERATIONS', 'inventory.read'
  union all select 'OPERATIONS', 'inventory.manage'
  union all select 'OPERATIONS', 'inventory.review'
  union all select 'OPERATIONS', 'inspection.read'
  union all select 'OPERATIONS', 'inspection.manage'
  union all select 'OPERATIONS', 'exam.learn'
  union all select 'OPERATIONS', 'exam.manage'
  union all select 'OPERATIONS', 'exam.report'
  union all select 'OPERATIONS', 'platform.read'
  union all select 'OPERATIONS', 'platform.manage'
  union all select 'OPERATIONS', 'attachment.read'
  union all select 'OPERATIONS', 'attachment.write'
  union all select 'OPERATIONS', 'todo.read'
  union all select 'OPERATIONS', 'todo.transition'
  union all select 'EMPLOYEE', 'exam.learn'
) template on 1 = 1
where not exists (
  select 1
  from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = template.role_code
    and existing.permission_code = template.permission_code
);

update auth_user
set store_id = (
  select min(scope.store_id)
  from user_store_scope scope
  where scope.tenant_id = auth_user.tenant_id
    and scope.user_id = auth_user.id
)
where upper(role) = 'STORE_MANAGER'
  and (store_id is null or trim(store_id) = '')
  and exists (
    select 1
    from user_store_scope scope
    where scope.tenant_id = auth_user.tenant_id
      and scope.user_id = auth_user.id
  );

update auth_user
set role = case
      when upper(role) in ('ADMIN', 'OWNER') then 'BOSS'
      when upper(role) in ('SUPERVISOR', 'OPS') then 'OPERATIONS'
      else upper(role)
    end,
    store_id = case
      when upper(role) in ('ADMIN', 'OWNER') then null
      else store_id
    end
where upper(role) in ('ADMIN', 'OWNER', 'SUPERVISOR', 'OPS');

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select auth_user.tenant_id,
       auth_user.id,
       domain.domain_code,
       case when effective_scope.scope_value_json is null then 'ALL' else 'STORE_LIST' end,
       effective_scope.scope_value_json,
       null,
       current_timestamp
from auth_user
join (
  select 'STORE' domain_code
  union all select 'FINANCE'
  union all select 'SALARY'
  union all select 'WAREHOUSE'
) domain on 1 = 1
left join (
  select scope_entry.tenant_id,
         scope_entry.user_id,
         json_arrayagg(scope_entry.store_id) as scope_value_json
  from (
    select tenant_id, user_id, store_id
    from user_store_scope
    where store_id is not null and trim(store_id) <> ''
    union
    select tenant_id, id as user_id, store_id
    from auth_user
    where store_id is not null and trim(store_id) <> ''
  ) scope_entry
  group by scope_entry.tenant_id, scope_entry.user_id
) effective_scope
  on effective_scope.tenant_id = auth_user.tenant_id
 and effective_scope.user_id = auth_user.id
where upper(auth_user.role) = 'FINANCE'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = auth_user.tenant_id
      and existing.user_id = auth_user.id
      and existing.domain_code = domain.domain_code
  );

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select auth_user.tenant_id, auth_user.id, 'WAREHOUSE', 'CENTRAL_WAREHOUSE', null, null, current_timestamp
from auth_user
where upper(auth_user.role) = 'WAREHOUSE'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = auth_user.tenant_id
      and existing.user_id = auth_user.id
      and existing.domain_code = 'WAREHOUSE'
  );

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select auth_user.tenant_id,
       auth_user.id,
       domain.domain_code,
       case
         when auth_user.store_id is not null and trim(auth_user.store_id) <> '' then 'OWN_STORE'
         else 'NONE'
       end,
       null,
       null,
       current_timestamp
from auth_user
join (
  select 'STORE' domain_code
  union all select 'FINANCE'
  union all select 'SALARY'
  union all select 'WAREHOUSE'
  union all select 'INSPECTION'
  union all select 'EXAM'
) domain on 1 = 1
where upper(auth_user.role) = 'STORE_MANAGER'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = auth_user.tenant_id
      and existing.user_id = auth_user.id
      and existing.domain_code = domain.domain_code
  );

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select auth_user.tenant_id,
       auth_user.id,
       domain.domain_code,
       case when effective_scope.scope_value_json is null then 'NONE' else 'STORE_LIST' end,
       effective_scope.scope_value_json,
       null,
       current_timestamp
from auth_user
join (
  select 'STORE' domain_code
  union all select 'WAREHOUSE'
  union all select 'INSPECTION'
  union all select 'EXAM'
  union all select 'PLATFORM'
) domain on 1 = 1
left join (
  select scope_entry.tenant_id,
         scope_entry.user_id,
         json_arrayagg(scope_entry.store_id) as scope_value_json
  from (
    select tenant_id, user_id, store_id
    from user_store_scope
    where store_id is not null and trim(store_id) <> ''
    union
    select tenant_id, id as user_id, store_id
    from auth_user
    where store_id is not null and trim(store_id) <> ''
  ) scope_entry
  group by scope_entry.tenant_id, scope_entry.user_id
) effective_scope
  on effective_scope.tenant_id = auth_user.tenant_id
 and effective_scope.user_id = auth_user.id
where upper(auth_user.role) = 'OPERATIONS'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = auth_user.tenant_id
      and existing.user_id = auth_user.id
      and existing.domain_code = domain.domain_code
  );

insert into user_data_scope(
  tenant_id, user_id, domain_code, scope_type, scope_value_json, created_by, created_at
)
select auth_user.tenant_id, auth_user.id, 'EXAM', 'SELF', null, null, current_timestamp
from auth_user
where upper(auth_user.role) = 'EMPLOYEE'
  and not exists (
    select 1 from user_data_scope existing
    where existing.tenant_id = auth_user.tenant_id
      and existing.user_id = auth_user.id
      and existing.domain_code = 'EXAM'
  );

-- Role-template recovery changes effective authorization, so every prior token must expire.
update auth_user set permission_version = permission_version + 1;
delete from auth_token;
