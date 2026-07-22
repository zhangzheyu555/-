-- 文档型向量知识库。MySQL 是原始资料、权限元数据和派生向量索引的唯一持久化来源；
-- 不调用外部模型，也不将 Word/Excel 正文发送给第三方服务。

-- V76 adds the scoped internal knowledge-base schema after the released V75 WeChat binding.
create table knowledge_base_document (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  title varchar(200) not null,
  category varchar(64) not null,
  original_file_name varchar(255) not null,
  content_type varchar(160) not null,
  file_size bigint not null,
  file_sha256 char(64) not null,
  source_content longblob not null,
  visibility varchar(16) not null,
  status varchar(16) not null,
  parsed_char_count int not null default 0,
  chunk_count int not null default 0,
  created_by bigint not null,
  published_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  published_at timestamp null,
  primary key (id),
  unique key uk_knowledge_base_document_file (tenant_id, file_sha256),
  key idx_knowledge_base_document_list (tenant_id, status, updated_at),
  key idx_knowledge_base_document_creator (tenant_id, created_by, created_at)
) engine=InnoDB default charset=utf8mb4;

create table knowledge_base_document_role_scope (
  document_id bigint not null,
  role_code varchar(32) not null,
  primary key (document_id, role_code),
  constraint fk_knowledge_base_document_role_scope_document
    foreign key (document_id) references knowledge_base_document(id) on delete cascade
) engine=InnoDB default charset=utf8mb4;

create table knowledge_base_document_store_scope (
  document_id bigint not null,
  store_id varchar(64) not null,
  primary key (document_id, store_id),
  key idx_knowledge_base_document_store_scope_store (store_id, document_id),
  constraint fk_knowledge_base_document_store_scope_document
    foreign key (document_id) references knowledge_base_document(id) on delete cascade
) engine=InnoDB default charset=utf8mb4;

create table knowledge_base_chunk (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  document_id bigint not null,
  chunk_no int not null,
  source_locator varchar(255) not null,
  content mediumtext not null,
  content_hash char(64) not null,
  embedding_model varchar(64) not null,
  embedding_dimensions smallint unsigned not null,
  embedding blob not null,
  created_at timestamp not null default current_timestamp,
  primary key (id),
  unique key uk_knowledge_base_chunk_position (document_id, chunk_no),
  key idx_knowledge_base_chunk_document (tenant_id, document_id),
  constraint fk_knowledge_base_chunk_document
    foreign key (document_id) references knowledge_base_document(id) on delete cascade
) engine=InnoDB default charset=utf8mb4;

insert into permission_catalog(
  permission_code, module_code, permission_name, description, risk_level, enabled, sort_order
)
values
  ('knowledge_base.search', 'KNOWLEDGE_BASE', '检索知识库', '检索已发布且在本人角色、门店范围内的内部资料。', 'MEDIUM', 1, 79),
  ('knowledge_base.manage', 'KNOWLEDGE_BASE', '管理知识库', '上传、发布和下架受控的 Word、Excel、CSV 与文本知识资料，仅限老板和督导。', 'HIGH', 1, 80)
on duplicate key update
  module_code = values(module_code),
  permission_name = values(permission_name),
  description = values(description),
  risk_level = values(risk_level),
  enabled = values(enabled),
  sort_order = values(sort_order);

-- 所有正式内部角色可检索自己范围内的已发布资料；管理只给老板和督导。
insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, roles.role_code, 'knowledge_base.search', current_timestamp
from tenant
cross join (
  select 'BOSS' as role_code union all select 'FINANCE' union all select 'SUPERVISOR'
  union all select 'WAREHOUSE' union all select 'STORE_MANAGER' union all select 'EMPLOYEE'
) roles
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = roles.role_code
    and existing.permission_code = 'knowledge_base.search'
);

insert into role_permission(tenant_id, role_code, permission_code, created_at)
select tenant.id, roles.role_code, 'knowledge_base.manage', current_timestamp
from tenant
cross join (select 'BOSS' as role_code union all select 'SUPERVISOR') roles
where not exists (
  select 1 from role_permission existing
  where existing.tenant_id = tenant.id
    and existing.role_code = roles.role_code
    and existing.permission_code = 'knowledge_base.manage'
);

-- 角色模板变化后强制重新签发会话，避免旧会话在菜单与接口之间出现权限不一致。
update auth_user
set permission_version = permission_version + 1,
    updated_at = current_timestamp
where upper(role) in ('BOSS', 'FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'STORE_MANAGER', 'EMPLOYEE');

delete token
from auth_token token
join auth_user account
  on account.tenant_id = token.tenant_id
 and account.id = token.user_id
where upper(account.role) in ('BOSS', 'FINANCE', 'SUPERVISOR', 'WAREHOUSE', 'STORE_MANAGER', 'EMPLOYEE');
