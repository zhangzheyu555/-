create table if not exists store_inventory_check (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  check_no varchar(80) not null,
  store_id varchar(64) not null,
  store_name varchar(160) not null,
  check_date date not null,
  status varchar(40) not null default 'DRAFT',
  total_amount decimal(14,2) not null default 0,
  submitted_by bigint null,
  reviewed_by bigint null,
  reviewed_at datetime null,
  note text null,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_inventory_check_no (tenant_id, check_no),
  index idx_inventory_check_store_date (tenant_id, store_id, check_date),
  index idx_inventory_check_status (tenant_id, status),
  constraint fk_inventory_check_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_inventory_check_store foreign key (store_id) references store_branch(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists store_inventory_check_line (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  check_id bigint not null,
  item_name varchar(160) not null,
  item_code varchar(80) null,
  category varchar(80) null,
  spec varchar(120) null,
  unit varchar(40) null,
  package_quantity decimal(14,2) null,
  unit_price decimal(14,2) not null default 0,
  unit_price_each decimal(14,4) null,
  counted_quantity decimal(14,2) not null default 0,
  amount decimal(14,2) not null default 0,
  note text null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_inventory_check_line_check (tenant_id, check_id),
  constraint fk_inventory_check_line_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_inventory_check_line_check foreign key (check_id) references store_inventory_check(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_paper (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  paper_code varchar(80) not null,
  paper_name varchar(160) not null,
  role_scope varchar(160) null,
  pass_score decimal(8,2) not null default 80,
  enabled tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_paper_code (tenant_id, paper_code),
  index idx_exam_paper_enabled (tenant_id, enabled),
  constraint fk_exam_paper_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_question (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  paper_id bigint not null,
  question_type varchar(40) not null,
  question_text text not null,
  options_json longtext null,
  standard_answer text not null,
  accept_keywords text null,
  score decimal(8,2) not null default 10,
  sort_order int not null default 0,
  enabled tinyint(1) not null default 1,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  index idx_exam_question_paper (tenant_id, paper_id, sort_order),
  constraint fk_exam_question_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_question_paper foreign key (paper_id) references training_exam_paper(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

set @sql := if(exists(select 1 from information_schema.statistics where table_schema = database() and table_name = 'training_exam_question' and index_name = 'uk_exam_question_order'), 'select 1', 'alter table training_exam_question add unique key uk_exam_question_order (tenant_id, paper_id, sort_order)');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

create table if not exists training_exam_attempt (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  paper_id bigint not null,
  paper_name varchar(160) not null,
  examinee_name varchar(120) not null,
  examinee_role varchar(40) not null,
  store_id varchar(64) null,
  store_name varchar(160) null,
  score decimal(8,2) not null default 0,
  passed tinyint(1) not null default 0,
  violated tinyint(1) not null default 0,
  submitted_by bigint null,
  submitted_at datetime not null,
  created_at timestamp not null default current_timestamp,
  index idx_exam_attempt_paper (tenant_id, paper_id, submitted_at),
  index idx_exam_attempt_store (tenant_id, store_id, submitted_at),
  index idx_exam_attempt_user (tenant_id, submitted_by, submitted_at),
  constraint fk_exam_attempt_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_attempt_paper foreign key (paper_id) references training_exam_paper(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_answer (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  attempt_id bigint not null,
  question_id bigint not null,
  user_answer text null,
  correct tinyint(1) not null default 0,
  score decimal(8,2) not null default 0,
  created_at timestamp not null default current_timestamp,
  index idx_exam_answer_attempt (tenant_id, attempt_id),
  constraint fk_exam_answer_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_answer_attempt foreign key (attempt_id) references training_exam_attempt(id),
  constraint fk_exam_answer_question foreign key (question_id) references training_exam_question(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_material (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  material_code varchar(80) not null,
  title varchar(160) not null,
  category varchar(80) not null,
  image_urls longtext null,
  content text null,
  enabled tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_training_material_code (tenant_id, material_code),
  index idx_training_material_category (tenant_id, category, enabled),
  constraint fk_training_material_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_learning_record (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  material_id bigint not null,
  user_id bigint not null,
  user_name varchar(120) not null,
  store_id varchar(64) null,
  learned tinyint(1) not null default 1,
  learned_at datetime not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_learning_record_user_material (tenant_id, material_id, user_id),
  index idx_learning_record_store (tenant_id, store_id, learned_at),
  constraint fk_learning_record_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_learning_record_material foreign key (material_id) references training_material(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into training_exam_paper(tenant_id, paper_code, paper_name, role_scope, pass_score, enabled, created_at)
values (1, 'NEW_STAFF_BASIC', '新人基础考试', 'STORE_MANAGER,OPERATIONS', 80, 1, current_timestamp)
on duplicate key update
  paper_name = values(paper_name),
  role_scope = values(role_scope),
  pass_score = values(pass_score),
  enabled = values(enabled),
  updated_at = current_timestamp;

set @new_staff_paper_id := (select id from training_exam_paper where tenant_id = 1 and paper_code = 'NEW_STAFF_BASIC' limit 1);

insert into training_exam_question(tenant_id, paper_id, question_type, question_text, options_json, standard_answer, accept_keywords, score, sort_order, enabled, created_at)
values
  (1, @new_staff_paper_id, 'SINGLE_CHOICE', '水果出现明显异味时应该怎么处理？', '["继续使用但减少用量","立即停用并记录","混入新鲜水果中使用","只用于外卖订单"]', '立即停用并记录', null, 25, 1, 1, current_timestamp),
  (1, @new_staff_paper_id, 'SINGLE_CHOICE', '半成品开封后最重要的管理动作是什么？', '["贴标签记录时间","放在吧台方便取用","混合不同批次","只看外观不需要记录"]', '贴标签记录时间', null, 25, 2, 1, current_timestamp),
  (1, @new_staff_paper_id, 'SINGLE_CHOICE', '店铺盘存金额的基础计算方式是什么？', '["数量 × 单价","销量 × 折扣","收入 - 房租","库存 ÷ 杯量"]', '数量 × 单价', null, 25, 3, 1, current_timestamp),
  (1, @new_staff_paper_id, 'SINGLE_CHOICE', '饿了么订单金额如果标记为示例数据，运营应该如何理解？', '["可以作为真实财务入账","只做页面演示，不能当真实经营数据","自动写入利润表","代表所有门店已接入"]', '只做页面演示，不能当真实经营数据', null, 25, 4, 1, current_timestamp)
on duplicate key update
  question_text = values(question_text),
  options_json = values(options_json),
  standard_answer = values(standard_answer),
  score = values(score),
  enabled = values(enabled),
  updated_at = current_timestamp;

insert into training_material(tenant_id, material_code, title, category, image_urls, content, enabled, sort_order, created_at)
values
  (1, 'FRUIT_STANDARD', '水果切配标准', '水果标准', '["/train-img/fruit1_1.jpg","/train-img/fruit1_2.jpg"]', '检查水果外观、成熟度和切配大小。发黑、出水、异味、明显压伤的水果禁止使用。', 1, 10, current_timestamp),
  (1, 'PREP_STANDARD', '半成品保存标准', '半成品', '["/train-img/prep1_1.jpg","/train-img/prep1_2.jpg","/train-img/prep1_3.jpg"]', '半成品必须按批次标识，使用前确认气味、颜色和保存时间。标签缺失、超过效期、容器不洁时禁止进入出品。', 1, 20, current_timestamp),
  (1, 'COOK_STANDARD', '蒸煮和茶底标准', '蒸煮类', '["/train-img/cook1_1.jpg","/train-img/cook1_2.jpg","/train-img/cook1_3.jpg"]', '严格按时间、比例和温度执行，出品前记录批次时间。超过有效时间必须废弃。', 1, 30, current_timestamp)
on duplicate key update
  title = values(title),
  category = values(category),
  image_urls = values(image_urls),
  content = values(content),
  enabled = values(enabled),
  sort_order = values(sort_order),
  updated_at = current_timestamp;
