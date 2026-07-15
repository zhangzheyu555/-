-- 员工助手知识库与人工转接闭环。仅保存审批后的知识、脱敏问题和处理结论，绝不保存完整原始会话。

create table employee_assistant_knowledge (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  category varchar(64) not null,
  title varchar(160) not null,
  keywords varchar(1000) not null,
  standard_answer text not null,
  status varchar(16) not null,
  current_version int not null default 0,
  created_by bigint not null,
  updated_by bigint not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  primary key (id),
  unique key uk_employee_assistant_knowledge_title (tenant_id, title),
  key idx_employee_assistant_knowledge_lookup (tenant_id, status, category)
) engine=InnoDB default charset=utf8mb4;

-- 发布时插入不可变快照；应用层不提供更新或删除版本的操作。
create table employee_assistant_knowledge_version (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  knowledge_id bigint not null,
  version_no int not null,
  category varchar(64) not null,
  title varchar(160) not null,
  keywords varchar(1000) not null,
  standard_answer text not null,
  publish_action varchar(16) not null,
  published_by bigint not null,
  published_at timestamp not null default current_timestamp,
  primary key (id),
  unique key uk_employee_assistant_knowledge_version (tenant_id, knowledge_id, version_no),
  key idx_employee_assistant_knowledge_version_lookup (tenant_id, knowledge_id, published_at)
) engine=InnoDB default charset=utf8mb4;

create table employee_assistant_handoff (
  id varchar(64) not null,
  tenant_id bigint not null,
  store_id varchar(64) null,
  question_redacted varchar(1200) not null,
  category varchar(64) not null,
  status varchar(16) not null,
  requested_by bigint not null,
  handled_by bigint null,
  resolution varchar(2000) null,
  created_at timestamp not null default current_timestamp,
  claimed_at timestamp null,
  responded_at timestamp null,
  closed_at timestamp null,
  expires_at timestamp not null,
  updated_at timestamp not null default current_timestamp,
  primary key (id),
  key idx_employee_assistant_handoff_queue (tenant_id, status, expires_at, created_at),
  key idx_employee_assistant_handoff_store (tenant_id, store_id, status),
  key idx_employee_assistant_handoff_requester (tenant_id, requested_by, created_at)
) engine=InnoDB default charset=utf8mb4;

create table employee_assistant_feedback (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  answer_source varchar(32) not null,
  knowledge_id bigint null,
  knowledge_version int null,
  helpful tinyint(1) not null,
  reason_code varchar(64) null,
  created_by bigint not null,
  created_at timestamp not null default current_timestamp,
  primary key (id),
  key idx_employee_assistant_feedback_knowledge (tenant_id, knowledge_id, knowledge_version),
  key idx_employee_assistant_feedback_creator (tenant_id, created_by, created_at)
) engine=InnoDB default charset=utf8mb4;

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values
  ('employee_assistant.knowledge_manage', 'EMPLOYEE_ASSISTANT', '管理员工助手知识库', '维护、发布和回滚已审批的员工服务标准话术，仅限老板。', 'HIGH', 1, 77),
  ('employee_assistant.handoff_manage', 'EMPLOYEE_ASSISTANT', '处理员工助手人工事项', '领取、回复和关闭权限范围内的员工助手人工转接事项。', 'HIGH', 1, 78)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

-- BOSS 通过最高角色权限集获得知识库权限；仅运营/督导（规范角色 OPERATIONS）默认获得人工事项处理权限。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, 'OPERATIONS', 'employee_assistant.handoff_manage', current_timestamp
from tenant
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = 'OPERATIONS'
    and existing.permission_code = 'employee_assistant.handoff_manage'
);

update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'OPERATIONS', 'OPS', 'SUPERVISOR');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('BOSS', 'OPERATIONS', 'OPS', 'SUPERVISOR');
