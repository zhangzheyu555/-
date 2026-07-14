create table if not exists training_course (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  course_code varchar(80) not null,
  title varchar(160) not null,
  category varchar(80) null,
  description text null,
  cover_url varchar(500) null,
  duration_minutes int not null default 0,
  required_role_scope varchar(255) null,
  enabled tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_training_course_code (tenant_id, course_code),
  index idx_training_course_enabled (tenant_id, enabled, sort_order),
  constraint fk_training_course_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_training_course_creator foreign key (created_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_course_material (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  course_id bigint not null,
  material_id bigint not null,
  required tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  unique key uk_training_course_material (tenant_id, course_id, material_id),
  index idx_training_course_material_order (tenant_id, course_id, sort_order),
  constraint fk_training_course_material_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_training_course_material_course foreign key (course_id) references training_course(id),
  constraint fk_training_course_material_material foreign key (material_id) references training_material(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_question_category (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  category_code varchar(80) not null,
  category_name varchar(120) not null,
  description varchar(500) null,
  enabled tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_question_category_code (tenant_id, category_code),
  index idx_exam_question_category_order (tenant_id, enabled, sort_order),
  constraint fk_exam_question_category_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_question_bank (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  question_code varchar(100) not null,
  category_id bigint null,
  question_type varchar(40) not null,
  question_text text not null,
  options_json longtext null,
  standard_answer text null,
  answer_analysis text null,
  accept_keywords text null,
  difficulty varchar(20) not null default 'MEDIUM',
  default_score decimal(8,2) not null default 10,
  enabled tinyint(1) not null default 1,
  created_by bigint null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_question_bank_code (tenant_id, question_code),
  index idx_exam_question_bank_category (tenant_id, category_id, enabled),
  constraint fk_exam_question_bank_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_question_bank_category foreign key (category_id) references training_exam_question_category(id),
  constraint fk_exam_question_bank_creator foreign key (created_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_paper_question_link (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  paper_question_id bigint not null,
  bank_question_id bigint not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_exam_paper_question_link (tenant_id, paper_question_id),
  index idx_exam_paper_question_bank (tenant_id, bank_question_id),
  constraint fk_exam_paper_question_link_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_paper_question_link_question foreign key (paper_question_id) references training_exam_question(id),
  constraint fk_exam_paper_question_link_bank foreign key (bank_question_id) references training_exam_question_bank(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_attempt_review (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  attempt_id bigint not null,
  review_status varchar(40) not null default 'AUTO_GRADED',
  review_note varchar(500) null,
  reviewed_by bigint null,
  reviewed_at datetime null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_attempt_review (tenant_id, attempt_id),
  index idx_exam_attempt_review_status (tenant_id, review_status, created_at),
  constraint fk_exam_attempt_review_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_attempt_review_attempt foreign key (attempt_id) references training_exam_attempt(id),
  constraint fk_exam_attempt_review_user foreign key (reviewed_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_answer_review (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  answer_id bigint not null,
  awarded_score decimal(8,2) not null default 0,
  review_comment varchar(500) null,
  reviewed_by bigint not null,
  reviewed_at datetime not null,
  created_at timestamp not null default current_timestamp,
  unique key uk_exam_answer_review (tenant_id, answer_id),
  constraint fk_exam_answer_review_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_answer_review_answer foreign key (answer_id) references training_exam_answer(id),
  constraint fk_exam_answer_review_user foreign key (reviewed_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table if not exists training_exam_wrong_question (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  user_id bigint not null,
  attempt_id bigint not null,
  question_id bigint not null,
  mastered tinyint(1) not null default 0,
  mastered_at datetime null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_exam_wrong_question (tenant_id, user_id, attempt_id, question_id),
  index idx_exam_wrong_question_user (tenant_id, user_id, mastered, created_at),
  constraint fk_exam_wrong_question_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_exam_wrong_question_user foreign key (user_id) references auth_user(id),
  constraint fk_exam_wrong_question_attempt foreign key (attempt_id) references training_exam_attempt(id),
  constraint fk_exam_wrong_question_question foreign key (question_id) references training_exam_question(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table training_course convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_material convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_learning_record convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_paper convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_question convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_campaign convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_assignment convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_attempt convert to character set utf8mb4 collate utf8mb4_unicode_ci;
alter table training_exam_answer convert to character set utf8mb4 collate utf8mb4_unicode_ci;

-- Restore known Git-history source labels when an earlier non-UTF8 import stored literal 0x3F bytes.
update training_exam_paper
set paper_name = case paper_code
  when 'NEW_STAFF_BASIC' then '新人基础考试'
  when 'FOOD_SAFETY_EXAM' then '食品安全月度考试'
  when 'ONBOARD_EXAM' then '新人入职综合考试'
  else paper_name end,
  updated_at = current_timestamp
where paper_code in ('NEW_STAFF_BASIC', 'FOOD_SAFETY_EXAM', 'ONBOARD_EXAM')
  and locate('?', paper_name) > 0;

update training_material
set title = case material_code
  when 'FRUIT_STANDARD' then '水果切配标准'
  when 'PREP_STANDARD' then '半成品保存标准'
  when 'COOK_STANDARD' then '蒸煮和茶底标准'
  when 'SERVICE_01' then '顾客服务基础规范'
  when 'HYGIENE_01' then '门店卫生检查标准'
  when 'SAFETY_01' then '食品安全操作规范'
  when 'BRAND_01' then '品牌形象与门店陈列'
  else title end,
  category = case material_code
  when 'FRUIT_STANDARD' then '水果标准'
  when 'PREP_STANDARD' then '半成品'
  when 'COOK_STANDARD' then '蒸煮类'
  when 'SERVICE_01' then '服务规范'
  when 'HYGIENE_01' then '门店卫生'
  when 'SAFETY_01' then '食品安全'
  when 'BRAND_01' then '品牌标准'
  else category end,
  updated_at = current_timestamp
where material_code in ('FRUIT_STANDARD', 'PREP_STANDARD', 'COOK_STANDARD', 'SERVICE_01', 'HYGIENE_01', 'SAFETY_01', 'BRAND_01')
  and (locate('?', title) > 0 or locate('?', category) > 0);

-- A font or connection fix cannot recover text that was already stored as literal 0x3F.
-- Reapply the exact Git-history source rows only when any user-facing field contains '?'.
create temporary table exam_utf8_repair_source (
  paper_code varchar(80) not null,
  sort_order int not null,
  question_text text not null,
  options_json longtext null,
  standard_answer text not null,
  accept_keywords text null,
  primary key (paper_code, sort_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into exam_utf8_repair_source(paper_code, sort_order, question_text, options_json, standard_answer, accept_keywords) values
('NEW_STAFF_BASIC', 1, '水果出现明显异味时应该怎么处理？', '["继续使用但减少用量","立即停用并记录","混入新鲜水果中使用","只用于外卖订单"]', '立即停用并记录', null),
('NEW_STAFF_BASIC', 2, '半成品开封后最重要的管理动作是什么？', '["贴标签记录时间","放在吧台方便取用","混合不同批次","只看外观不需要记录"]', '贴标签记录时间', null),
('NEW_STAFF_BASIC', 3, '店铺盘存金额的基础计算方式是什么？', '["数量 × 单价","销量 × 折扣","收入 - 房租","库存 ÷ 杯量"]', '数量 × 单价', null),
('NEW_STAFF_BASIC', 4, '饿了么订单金额如果标记为示例数据，运营应该如何理解？', '["可以作为真实财务入账","只做页面演示，不能当真实经营数据","自动写入利润表","代表所有门店已接入"]', '只做页面演示，不能当真实经营数据', null),
('FOOD_SAFETY_EXAM', 1, '冷藏食品的储存温度范围是多少？', '["0-4℃","0-8℃","2-10℃","-5-0℃"]', '0-8℃', null),
('FOOD_SAFETY_EXAM', 2, '开封后的半成品最晚多久内必须使用完毕？', '["12小时","24小时","48小时","72小时"]', '24小时', null),
('FOOD_SAFETY_EXAM', 3, '以下哪项是处理过期食材的正确做法？', '["降价促销","混入新鲜食材使用","立即废弃并记录","继续观察一天"]', '立即废弃并记录', null),
('FOOD_SAFETY_EXAM', 4, '生食和熟食加工时最重要的隔离措施是什么？', '["使用同一套工具","使用不同颜色标识的砧板和刀具","放在同一工作台上","不需要隔离"]', '使用不同颜色标识的砧板和刀具', null),
('FOOD_SAFETY_EXAM', 5, 'FIFO原则在食品管理中代表什么？', '["先采购先用","先进先出","先入库先检查","先到先服务"]', '先进先出', null),
('FOOD_SAFETY_EXAM', 6, '每日温度记录应该至少进行几次？', '["1次","2次","3次","不需要记录"]', '2次', null),
('FOOD_SAFETY_EXAM', 7, '操作食品前最重要的个人卫生步骤是什么？', '["戴手套","洗手消毒","穿围裙","戴口罩"]', '洗手消毒', null),
('FOOD_SAFETY_EXAM', 8, '发现食材出现异味时应该怎么处理？', '["继续使用","与其他食材混合","立即停用并上报","只用在外卖订单"]', '立即停用并上报', '立即停用'),
('FOOD_SAFETY_EXAM', 9, '即食食品操作时对员工的要求是什么？', '["不需要特殊防护","佩戴食品级手套","佩戴棉布手套","徒手操作"]', '佩戴食品级手套', null),
('FOOD_SAFETY_EXAM', 10, '冷冻食品的储存温度应低于多少度？', '["0℃","-5℃","-10℃","-18℃"]', '-18℃', null),
('ONBOARD_EXAM', 1, '顾客进店后应在几秒内问候？', '["1秒","3秒","5秒","10秒"]', '3秒', null),
('ONBOARD_EXAM', 2, '收到客户投诉时第一步应该做什么？', '["解释原因","道歉并表示理解","找店长","忽略"]', '道歉并表示理解', null),
('ONBOARD_EXAM', 3, '出餐时的正确做法是什么？', '["放在柜台上让顾客自取","报出品名并双手递送","直接递给顾客不说话","扔在取餐区"]', '报出品名并双手递送', null),
('ONBOARD_EXAM', 4, '水果出现明显异味时应该怎么处理？', '["继续使用但减少用量","立即停用并记录","混入新鲜水果中使用","只用于外卖订单"]', '立即停用并记录', null),
('ONBOARD_EXAM', 5, '半成品开封后最重要的管理动作是什么？', '["贴标签记录时间","放在吧台方便取用","混合不同批次","只看外观不需要记录"]', '贴标签记录时间', null),
('ONBOARD_EXAM', 6, '门店LED灯箱应多久检查一次？', '["每周","每日","每月","不需要检查"]', '每日', null),
('ONBOARD_EXAM', 7, '开封后半成品标签上必须标注什么信息？', '["品名和开封时间","只标注品名","不需要标注","标注价格"]', '品名和开封时间', null),
('ONBOARD_EXAM', 8, '店铺盘存金额的基础计算方式是什么？', '["数量×单价","销量×折扣","收入-房租","库存÷杯量"]', '数量×单价', null);

update training_exam_question q
join training_exam_paper p on p.tenant_id = q.tenant_id and p.id = q.paper_id
join exam_utf8_repair_source s on s.paper_code = p.paper_code and s.sort_order = q.sort_order
set q.question_text = s.question_text,
    q.options_json = s.options_json,
    q.standard_answer = s.standard_answer,
    q.accept_keywords = s.accept_keywords,
    q.updated_at = current_timestamp
where locate('?', q.question_text) > 0
   or locate('?', q.options_json) > 0
   or locate('?', q.standard_answer) > 0;

drop temporary table exam_utf8_repair_source;

insert into training_exam_question_category(
  tenant_id, category_code, category_name, description, enabled, sort_order, created_at, updated_at
)
select t.id, 'GENERAL', '基础知识', '通用制度、品牌与岗位基础知识', 1, 10, current_timestamp, current_timestamp
from tenant t
where not exists (
  select 1 from training_exam_question_category c where c.tenant_id = t.id and c.category_code = 'GENERAL'
);

insert into training_exam_question_category(
  tenant_id, category_code, category_name, description, enabled, sort_order, created_at, updated_at
)
select t.id, 'FOOD_SAFETY', '食品安全', '食品储存、加工、效期和卫生规范', 1, 20, current_timestamp, current_timestamp
from tenant t
where not exists (
  select 1 from training_exam_question_category c where c.tenant_id = t.id and c.category_code = 'FOOD_SAFETY'
);

insert into training_exam_question_category(
  tenant_id, category_code, category_name, description, enabled, sort_order, created_at, updated_at
)
select t.id, 'SERVICE', '服务规范', '顾客接待、点单、出餐和投诉处理', 1, 30, current_timestamp, current_timestamp
from tenant t
where not exists (
  select 1 from training_exam_question_category c where c.tenant_id = t.id and c.category_code = 'SERVICE'
);

insert into training_exam_question_category(
  tenant_id, category_code, category_name, description, enabled, sort_order, created_at, updated_at
)
select t.id, 'STORE_OPERATION', '门店运营', '盘存、陈列和门店日常运营标准', 1, 40, current_timestamp, current_timestamp
from tenant t
where not exists (
  select 1 from training_exam_question_category c where c.tenant_id = t.id and c.category_code = 'STORE_OPERATION'
);

-- Import the real paper questions already stored in MySQL into the independent question bank.
insert into training_exam_question_bank(
  tenant_id, question_code, category_id, question_type, question_text, options_json,
  standard_answer, accept_keywords, difficulty, default_score, enabled, created_at, updated_at
)
select q.tenant_id,
       concat('LEGACY_', q.paper_id, '_', q.sort_order),
       c.id,
       q.question_type,
       q.question_text,
       q.options_json,
       q.standard_answer,
       q.accept_keywords,
       'MEDIUM',
       q.score,
       q.enabled,
       current_timestamp,
       current_timestamp
from training_exam_question q
join training_exam_paper p on p.tenant_id = q.tenant_id and p.id = q.paper_id
join training_exam_question_category c
  on c.tenant_id = q.tenant_id
 and c.category_code = case
   when p.paper_code = 'FOOD_SAFETY_EXAM' then 'FOOD_SAFETY'
   when p.paper_code = 'ONBOARD_EXAM' then 'SERVICE'
   else 'GENERAL' end
where not exists (
  select 1
  from training_exam_question_bank b
  where b.tenant_id = q.tenant_id
    and b.question_code = concat('LEGACY_', q.paper_id, '_', q.sort_order)
);

insert into training_exam_paper_question_link(tenant_id, paper_question_id, bank_question_id, created_at)
select q.tenant_id, q.id, b.id, current_timestamp
from training_exam_question q
join training_exam_question_bank b
  on b.tenant_id = q.tenant_id
 and b.question_code = concat('LEGACY_', q.paper_id, '_', q.sort_order)
where not exists (
  select 1
  from training_exam_paper_question_link l
  where l.tenant_id = q.tenant_id and l.paper_question_id = q.id
);
