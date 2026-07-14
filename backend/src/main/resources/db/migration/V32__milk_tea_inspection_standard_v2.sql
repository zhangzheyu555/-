-- V32: Replace restaurant-oriented inspection wording with milk-tea store standards.
-- Historical inspection_record_standard_snapshot rows are intentionally untouched.

insert into inspection_standard_version (
  tenant_id, version, title, full_score, effective_date, status, created_by
)
select distinct source.tenant_id, '2026-v2', '奶茶门店食品安全稽核标准 (V2)', 100.00, '2026-07-11', 'ACTIVE', null
from inspection_standard_version source
where source.status = 'ACTIVE'
  and not exists (
    select 1 from inspection_standard_version target
    where target.tenant_id = source.tenant_id and target.version = '2026-v2'
  );

update inspection_standard_version
set status = 'ARCHIVED', updated_at = current_timestamp
where status = 'ACTIVE' and version <> '2026-v2';

update inspection_standard_version
set status = 'ACTIVE', updated_at = current_timestamp
where version = '2026-v2';

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '人员卫生', 'P01', '员工洗手和个人卫生符合要求', '员工进入操作区、接触食品前及处理垃圾后必须规范洗手；工作服、口罩和发网保持清洁，指甲修剪整齐，不佩戴可能污染食品的饰物。', 10, 0, 1, 1
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'P01');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '人员卫生', 'P02', '健康证明有效且按要求公示', '所有直接接触食品的员工健康证明必须在有效期内并按要求公示；无证、过期或存在不适合接触食品的健康情况时不得上岗。', 10, 1, 1, 2
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'P02');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '环境卫生', 'E01', '操作区地面和台面清洁', '吧台、备料区和取餐区地面无积水、糖浆及奶渍；操作台随脏随清，每日闭店后完成清洁消毒。', 8, 0, 1, 3
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'E01');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '环境卫生', 'E02', '虫害防治和垃圾管理到位', '垃圾桶加盖并及时清运，无溢出、异味和渗漏；门店无明显虫害活动迹象，防虫设施有效，虫害记录和处置记录完整。', 8, 0, 1, 4
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'E02');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '环境卫生', 'E03', '杯具、吸管和包装材料防尘存放', '杯子、杯盖、吸管、封口膜和外卖包装必须离地、加盖或密闭存放，取用区域清洁干燥，不得与清洁工具或化学用品混放。', 6, 0, 1, 5
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'E03');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '原料管理', 'M01', '奶制品、水果及配料冷藏温度达标', '奶制品、鲜切水果、已开封果汁及需冷藏配料按标签要求冷藏，冷藏设备温度保持在规定范围并每日记录；温度异常时立即隔离评估。', 10, 1, 1, 6
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'M01');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '原料管理', 'M02', '原料保质期和开封标签完整', '茶叶、糖浆、奶制品、水果、珍珠及其他配料必须在保质期内；开封或制作后标注品名、开封或制作时间、使用期限，并按先进先出使用。', 12, 1, 1, 7
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'M02');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '原料管理', 'M03', '原料、成品与清洁用品分区存放', '茶叶、糖浆、奶制品、水果及已制作配料按储存要求分类、密封并标注开封日期；食品与清洁剂、消毒剂等非食品用品必须分区存放，避免污染。', 10, 1, 1, 8
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'M03');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '设备管理', 'D01', '冷藏设备运行和温度记录正常', '冷藏柜密封条、温度显示和排水保持正常，内部无积水、霉斑和过量结霜；每日按时记录温度并处理异常。', 8, 0, 1, 9
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'D01');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '设备管理', 'D02', '制冰机、封口机和操作台清洁', '制冰机内外部、冰铲及储冰区域按计划清洁消毒；封口机、摇杯机和操作台无糖渍、奶渍及积垢，清洁记录完整。', 6, 0, 1, 10
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'D02');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '操作规范', 'O01', '饮品制作过程符合卫生规范', '取冰、取料和制作饮品时使用清洁专用工具，手部不得直接接触即食原料；不同配料工具定点存放，污染后立即更换并清洁消毒。', 8, 0, 1, 11
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'O01');

insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order)
select tenant_id, id, '操作规范', 'O02', '器具清洗消毒和存放规范', '量杯、雪克杯、搅拌勺、冰铲及配料容器按规定频次清洗消毒，清洁后沥干并存放在防尘区域，不得使用有异味或破损器具。', 4, 0, 1, 12
from inspection_standard_version v where v.version = '2026-v2' and not exists (select 1 from inspection_standard_item i where i.standard_version_id = v.id and i.code = 'O02');
