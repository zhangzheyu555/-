-- Group existing and future knowledge documents into auditable topics and versions.
-- Original source files remain immutable; integration happens through metadata and retrieval.

create table knowledge_base_topic (
  id bigint not null auto_increment,
  tenant_id bigint not null,
  name varchar(200) not null,
  normalized_name varchar(200) not null,
  created_by bigint not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  primary key (id),
  unique key uk_knowledge_base_topic_name (tenant_id, normalized_name),
  key idx_knowledge_base_topic_list (tenant_id, updated_at, id),
  constraint fk_knowledge_base_topic_tenant
    foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4;

insert into knowledge_base_topic(
  tenant_id, name, normalized_name, created_by, created_at, updated_at
)
select
  tenant_id,
  min(title),
  lower(trim(title)),
  min(created_by),
  min(created_at),
  max(updated_at)
from knowledge_base_document
group by tenant_id, lower(trim(title));

alter table knowledge_base_document
  add column topic_id bigint null after tenant_id,
  add column version_no int null after topic_id,
  add column relation_type varchar(16) null after version_no,
  add column predecessor_document_id bigint null after relation_type;

update knowledge_base_document document
join knowledge_base_topic topic
  on topic.tenant_id = document.tenant_id
 and topic.normalized_name = lower(trim(document.title))
set document.topic_id = topic.id;

create temporary table knowledge_base_document_version_backfill (
  document_id bigint not null,
  version_no int not null,
  predecessor_document_id bigint null,
  primary key (document_id)
);

insert into knowledge_base_document_version_backfill(document_id, version_no, predecessor_document_id)
select
  id,
  row_number() over (partition by topic_id order by created_at, id),
  lag(id) over (partition by topic_id order by created_at, id)
from knowledge_base_document;

update knowledge_base_document document
join knowledge_base_document_version_backfill backfill on backfill.document_id = document.id
set document.version_no = backfill.version_no,
    document.relation_type = if(backfill.version_no = 1, 'ORIGINAL', 'SUPPLEMENTS'),
    document.predecessor_document_id = backfill.predecessor_document_id;

drop temporary table knowledge_base_document_version_backfill;

alter table knowledge_base_document
  modify column topic_id bigint not null,
  modify column version_no int not null,
  modify column relation_type varchar(16) not null,
  add unique key uk_knowledge_base_document_topic_version (topic_id, version_no),
  add key idx_knowledge_base_document_topic_status (tenant_id, topic_id, status, version_no),
  add key idx_knowledge_base_document_predecessor (predecessor_document_id),
  add constraint fk_knowledge_base_document_topic
    foreign key (topic_id) references knowledge_base_topic(id),
  add constraint fk_knowledge_base_document_predecessor
    foreign key (predecessor_document_id) references knowledge_base_document(id),
  add constraint chk_knowledge_base_document_version
    check (version_no > 0),
  add constraint chk_knowledge_base_document_relation
    check (relation_type in ('ORIGINAL', 'REPLACES', 'SUPPLEMENTS'));
