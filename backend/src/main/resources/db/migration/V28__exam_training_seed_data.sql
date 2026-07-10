-- V28: Comprehensive training/exam seed data
-- Adds training materials, exam papers with questions, and ensures seed data for full exam workflow

-- ============================================================
-- TRAINING MATERIALS (培训资料)
-- ============================================================

-- Category: 食品安全
insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'FOOD_SAFETY_01', '食品储存温度标准', '食品安全',
  '冷藏食品必须在0-8℃环境中保存，冷冻食品必须在-18℃以下保存。每日上午和下午各记录一次温度，异常情况立即上报并启动应急预案。不同食材严格分区存放，生熟分开，避免交叉污染。',
  1, 40, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'FOOD_SAFETY_02', '食品保质期与标签管理', '食品安全',
  '所有食材入库时必须张贴标签，标注品名、生产日期、保质期和批次号。严格执行先进先出原则（FIFO）。开封后半成品必须在24小时内使用完毕并标注开封时间。过期食材立即废弃并记录。',
  1, 41, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'FOOD_SAFETY_03', '食品交叉污染预防', '食品安全',
  '使用不同颜色砧板和刀具区分生食和熟食加工。操作前后必须洗手消毒。即食食品必须使用食品级手套操作。清洁工具和食品加工工具严格分离存放。',
  1, 42, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

-- Category: 服务规范
insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'SERVICE_01', '门店服务标准流程', '服务规范',
  '顾客进店3秒内问候，点单时主动推荐新品和优惠活动。制作过程中向顾客说明等待时间。出餐时报出品名并双手递送。顾客离开时致谢并邀请再次光临。',
  1, 50, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'SERVICE_02', '客户投诉处理规范', '服务规范',
  '收到客户投诉时，首先道歉并表示理解。不推卸责任，不找借口。能够当场解决的立即处理；需要协调的上报店长并在30分钟内回复客户。所有投诉记录在案，每周回顾分析。',
  1, 51, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

-- Category: 品牌标准
insert into training_material(tenant_id, material_code, title, category, content, enabled, sort_order, created_at)
values (1, 'BRAND_01', '品牌形象与门店陈列', '品牌标准',
  '门头招牌保持清洁明亮，LED灯箱每日检查。菜单板内容准确无遮挡。产品陈列整齐美观，主推产品放在黄金视线位置。门店音乐音量适中，营造舒适氛围。',
  1, 60, current_timestamp)
on duplicate key update title=values(title), category=values(category), content=values(content), enabled=values(enabled), sort_order=values(sort_order), updated_at=current_timestamp;

-- ============================================================
-- EXAM PAPER 2: 食品安全考试 (10 questions)
-- ============================================================

insert into training_exam_paper(tenant_id, paper_code, paper_name, role_scope, pass_score, enabled, created_at)
values (1, 'FOOD_SAFETY_EXAM', '食品安全月度考试', 'EMPLOYEE,STORE_MANAGER', 70, 1, current_timestamp)
on duplicate key update paper_name=values(paper_name), role_scope=values(role_scope), pass_score=values(pass_score), enabled=values(enabled), updated_at=current_timestamp;

set @food_paper_id := (select id from training_exam_paper where tenant_id = 1 and paper_code = 'FOOD_SAFETY_EXAM' limit 1);

insert into training_exam_question(tenant_id, paper_id, question_type, question_text, options_json, standard_answer, accept_keywords, score, sort_order, enabled, created_at)
values
  (1, @food_paper_id, 'SINGLE_CHOICE', '冷藏食品的储存温度范围是多少？',
   '["0-4℃","0-8℃","2-10℃","-5-0℃"]', '0-8℃', null, 10, 1, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '开封后的半成品最晚多久内必须使用完毕？',
   '["12小时","24小时","48小时","72小时"]', '24小时', null, 10, 2, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '以下哪项是处理过期食材的正确做法？',
   '["降价促销","混入新鲜食材使用","立即废弃并记录","继续观察一天"]', '立即废弃并记录', null, 10, 3, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '生食和熟食加工时最重要的隔离措施是什么？',
   '["使用同一套工具","使用不同颜色标识的砧板和刀具","放在同一工作台上","不需要隔离"]', '使用不同颜色标识的砧板和刀具', null, 10, 4, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', 'FIFO原则在食品管理中代表什么？',
   '["先采购先用","先进先出","先入库先检查","先到先服务"]', '先进先出', null, 10, 5, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '每日温度记录应该至少进行几次？',
   '["1次","2次","3次","不需要记录"]', '2次', null, 10, 6, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '操作食品前最重要的个人卫生步骤是什么？',
   '["戴手套","洗手消毒","穿围裙","戴口罩"]', '洗手消毒', null, 10, 7, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '发现食材出现异味时应该怎么处理？',
   '["继续使用","与其他食材混合","立即停用并上报","只用在外卖订单"]', '立即停用并上报', '立即停用', 10, 8, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '即食食品操作时对员工的要求是什么？',
   '["不需要特殊防护","佩戴食品级手套","佩戴棉布手套","徒手操作"]', '佩戴食品级手套', null, 10, 9, 1, current_timestamp),
  (1, @food_paper_id, 'SINGLE_CHOICE', '冷冻食品的储存温度应低于多少度？',
   '["0℃","-5℃","-10℃","-18℃"]', '-18℃', null, 10, 10, 1, current_timestamp)
on duplicate key update
  question_text=values(question_text), options_json=values(options_json),
  standard_answer=values(standard_answer), score=values(score),
  enabled=values(enabled), updated_at=current_timestamp;

-- ============================================================
-- EXAM PAPER 3: 新人入职综合考试 (mix of existing questions + service)
-- ============================================================

insert into training_exam_paper(tenant_id, paper_code, paper_name, role_scope, pass_score, enabled, created_at)
values (1, 'ONBOARD_EXAM', '新人入职综合考试', 'EMPLOYEE,STORE_MANAGER', 60, 1, current_timestamp)
on duplicate key update paper_name=values(paper_name), role_scope=values(role_scope), pass_score=values(pass_score), enabled=values(enabled), updated_at=current_timestamp;

set @onboard_paper_id := (select id from training_exam_paper where tenant_id = 1 and paper_code = 'ONBOARD_EXAM' limit 1);

insert into training_exam_question(tenant_id, paper_id, question_type, question_text, options_json, standard_answer, accept_keywords, score, sort_order, enabled, created_at)
values
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '顾客进店后应在几秒内问候？',
   '["1秒","3秒","5秒","10秒"]', '3秒', null, 10, 1, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '收到客户投诉时第一步应该做什么？',
   '["解释原因","道歉并表示理解","找店长","忽略"]', '道歉并表示理解', null, 10, 2, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '出餐时的正确做法是什么？',
   '["放在柜台上让顾客自取","报出品名并双手递送","直接递给顾客不说话","扔在取餐区"]', '报出品名并双手递送', null, 10, 3, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '水果出现明显异味时应该怎么处理？',
   '["继续使用但减少用量","立即停用并记录","混入新鲜水果中使用","只用于外卖订单"]', '立即停用并记录', null, 10, 4, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '半成品开封后最重要的管理动作是什么？',
   '["贴标签记录时间","放在吧台方便取用","混合不同批次","只看外观不需要记录"]', '贴标签记录时间', null, 10, 5, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '门店LED灯箱应多久检查一次？',
   '["每周","每日","每月","不需要检查"]', '每日', null, 10, 6, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '开封后半成品标签上必须标注什么信息？',
   '["品名和开封时间","只标注品名","不需要标注","标注价格"]', '品名和开封时间', null, 10, 7, 1, current_timestamp),
  (1, @onboard_paper_id, 'SINGLE_CHOICE', '店铺盘存金额的基础计算方式是什么？',
   '["数量×单价","销量×折扣","收入-房租","库存÷杯量"]', '数量×单价', null, 10, 8, 1, current_timestamp)
on duplicate key update
  question_text=values(question_text), options_json=values(options_json),
  standard_answer=values(standard_answer), score=values(score),
  enabled=values(enabled), updated_at=current_timestamp;
