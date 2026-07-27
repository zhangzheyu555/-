create table if not exists store_inventory_item (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  item_code varchar(80) not null,
  category varchar(40) not null,
  item_name varchar(160) not null,
  spec varchar(160) null,
  unit varchar(40) not null,
  package_quantity decimal(18,4) not null default 1,
  package_price decimal(18,6) null,
  unit_price decimal(18,6) null,
  sort_order int not null default 0,
  enabled tinyint(1) not null default 1,
  source_file varchar(255) null,
  source_sha256 varchar(64) null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null,
  unique key uk_store_inventory_item_code (tenant_id, item_code),
  index idx_store_inventory_item_order (tenant_id, enabled, sort_order),
  constraint fk_store_inventory_item_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

alter table store_inventory_check
  modify column total_amount decimal(18,2) not null default 0;

alter table store_inventory_check_line
  modify column package_quantity decimal(18,4) null,
  modify column unit_price decimal(18,6) not null default 0,
  modify column unit_price_each decimal(18,6) null,
  modify column counted_quantity decimal(18,4) not null default 0,
  modify column amount decimal(18,2) not null default 0;

insert into store_inventory_item(
  tenant_id, item_code, category, item_name, spec, unit,
  package_quantity, package_price, unit_price, sort_order, enabled,
  source_file, source_sha256, created_at
)
select tenant.id, template.item_code, template.category, template.item_name, template.spec, template.unit,
       template.package_quantity, template.package_price, template.unit_price, template.sort_order, 1,
       '2026.6月盘存表(1)的副本.xlsx',
       'a6eb9ae9185e03af3801fea0848831b7d7c703b4bca4563a4952b5d9de5d99b1',
       current_timestamp
from tenant
cross join (
  select 'PD-HC-001' item_code, '耗材' category, '700注塑杯' item_name, '1件20条/1条25个' spec, '个' unit, 500 package_quantity, 210 package_price, 0.420000 unit_price, 1 sort_order
  union all select 'PD-HC-002', '耗材', '500注塑杯', '1件20条/1条25个', '个', 500, 190, 0.380000, 2
  union all select 'PD-HC-003', '耗材', '新500纸杯', '1件10条/1条20个', '个', 200, 105, 0.525000, 3
  union all select 'PD-HC-004', '耗材', '奶油分装盒', '按条/1条25个', '个', 25, 8.5, 0.340000, 4
  union all select 'PD-HC-005', '耗材', '试饮杯', '1条100个', '个', 100, 4, 0.040000, 5
  union all select 'PD-HC-006', '耗材', '分体热饮盖', '1件4条/1条50个', '个', 200, 225, 1.125000, 6
  union all select 'PD-HC-007', '耗材', '小孔芒果杯盖', '1条/50个', '个', 50, 8, 0.160000, 7
  union all select 'PD-HC-008', '耗材', '新款98口径杯盖', '1件20条/1条50个', '个', 100, 13, 0.130000, 8
  union all select 'PD-HC-009', '耗材', '热敏收银纸', '1件25条/1条4个', '卷', 100, 165, 1.650000, 9
  union all select 'PD-HC-010', '耗材', '杯贴标签纸', '1条5卷', '卷', 1, 6.4, 6.400000, 10
  union all select 'PD-HC-011', '耗材', '时效贴纸', '1条6卷', '卷', 1, 7, 7.000000, 11
  union all select 'PD-HC-012', '耗材', '注塑杯盖', '一件20条/一条50个', '个', 1000, 100, 0.100000, 12
  union all select 'PD-HC-013', '耗材', '封口膜', '卷', '卷', 1, 75, 75.000000, 13
  union all select 'PD-HC-014', '耗材', '热饮白色封条', '卷', '条', 60, 3, 0.050000, 14
  union all select 'PD-HC-015', '耗材', '细吸管（按包叫货）', '按包统计', '包', 1, 5.5, 5.500000, 15
  union all select 'PD-HC-016', '耗材', '粗吸管', '1件20包/1包100支', '包', 20, 105, 5.250000, 16
  union all select 'PD-HC-017', '耗材', '水果叉子', '按包统计', '包', 1, 10, 10.000000, 17
  union all select 'PD-HC-018', '耗材', '单杯保温袋', '1件10扎/1扎50个', '个', 500, 305, 0.610000, 18
  union all select 'PD-HC-019', '耗材', '双杯保温袋', '1件10扎/1扎50个', '个', 500, 390, 0.780000, 19
  union all select 'PD-HC-020', '耗材', '四杯保温袋', '1件6扎/1扎50个', '个', 300, 310, 1.033333, 20
  union all select 'PD-HC-021', '耗材', '单杯袋', '一扎100个', '个', 100, 11, 0.110000, 21
  union all select 'PD-HC-022', '耗材', '双杯纸袋', '1件20扎/一扎25个', '个', 500, 425, 0.850000, 22
  union all select 'PD-HC-023', '耗材', '四杯纸袋', '1件8扎/一扎25个', '个', 200, 210, 1.050000, 23
  union all select 'PD-HC-024', '耗材', '四杯托', '1件20扎/一扎25个', '个', 500, 200, 0.400000, 24
  union all select 'PD-HC-025', '耗材', '驼色抹布', '按条统计', '条', 1, 4, 4.000000, 25
  union all select 'PD-HC-026', '耗材', '蓝色抹布', '按条统计', '条', 1, 4, 4.000000, 26
  union all select 'PD-HC-027', '耗材', '咖色抹布', '按条统计', '条', 1, 4.5, 4.500000, 27
  union all select 'PD-HC-028', '耗材', '灰色抹布', '按条统计', '条', 1, 4, 4.000000, 28
  union all select 'PD-HC-029', '耗材', '纸巾', '1件50包', '包', 50, 140, 2.800000, 29
  union all select 'PD-HC-030', '耗材', '大垃圾袋', '1扎30个', '个', 30, 18, 0.600000, 30
  union all select 'PD-HC-031', '耗材', '玻璃瓶', '60个/件', '个', 60, 95, 1.583333, 31
  union all select 'PD-HC-032', '耗材', '安心贴', '卷', '卷', 1, null, null, 32
  union all select 'PD-HC-033', '耗材', '500牛油果杯', '1件20条/1条15个', '个', 300, 145, 0.483333, 33
  union all select 'PD-HC-034', '耗材', '550注塑杯', '500个/件', '个', 500, 382, 0.764000, 34
  union all select 'PD-HC-035', '耗材', '550注塑杯盖子', '500个/件', '个', 500, 108, 0.216000, 35
  union all select 'PD-HC-036', '耗材', '1L瓶子', '1件100个', '个', 100, 135, 1.350000, 36
  union all select 'PD-HC-037', '耗材', '500瓶子', '1件100个', '个', 100, 100, 1.000000, 37
  union all select 'PD-HC-038', '耗材', '棉棒', '个', '个', 1, 20, 20.000000, 38
  union all select 'PD-HC-039', '耗材', '雪克杯', '个', '个', 1, 20, 20.000000, 39
  union all select 'PD-HC-040', '耗材', '一次性头套', '1包100个', '个', 100, 20, 0.200000, 40
  union all select 'PD-HC-041', '耗材', '一次性手套M', '1包100个', '个', 100, 35, 0.350000, 41
  union all select 'PD-HC-042', '耗材', '一次性手套L', '1包100个', '个', 100, 18, 0.180000, 42
  union all select 'PD-HC-043', '耗材', '一次性口罩', '1包50个', '个', 50, 30, 0.600000, 43
  union all select 'PD-HC-044', '耗材', '拱形杯盖', '一件20条/一条50个', '个', 1000, 125, 0.125000, 44
  union all select 'PD-HC-045', '耗材', '小透明胶', '卷', '卷', 1, 4.5, 4.500000, 45
  union all select 'PD-HC-046', '耗材', '卡士杯盖防漏贴', '卷', '卷', 1, 12, 12.000000, 46
  union all select 'PD-HC-047', '耗材', '装饰袋', '个', '个', 1, 3, 3.000000, 47
  union all select 'PD-HC-048', '耗材', '防漏纸', '500张/包', '张', 500, 16, 0.032000, 48
  union all select 'PD-YL-001', '原料', '原味晶球', '1件12包', '包', 12, 100, 8.333333, 49
  union all select 'PD-YL-002', '原料', '西柚粒', '1件12瓶', '瓶', 12, 350, 29.166667, 50
  union all select 'PD-YL-003', '原料', '椰子粉', '1件12包', '包', 12, 400, 33.333333, 51
  union all select 'PD-YL-004', '原料', '熊猫炼奶（瓶）', '按瓶统计', '瓶', 1, 9.5, 9.500000, 52
  union all select 'PD-YL-005', '原料', '咖啡奶', '1件12瓶', '瓶', 12, 27.5, 2.291667, 53
  union all select 'PD-YL-006', '原料', '优益C', '1瓶950ML', '瓶', 1, 11, 11.000000, 54
  union all select 'PD-YL-007', '原料', '淡奶油', '1件12瓶', '瓶', 12, 485, 40.416667, 55
  union all select 'PD-YL-008', '原料', '白糖', '1袋25kg', '斤', 50, 235, 4.700000, 56
  union all select 'PD-YL-009', '原料', '定制糖浆（新款糖）', '1件4桶', '件', 1, 210, 210.000000, 57
  union all select 'PD-YL-010', '原料', '海盐', '袋', '袋', 1, 4.5, 4.500000, 58
  union all select 'PD-YL-011', '原料', 'VC', '1袋500克', '袋', 1, 27, 27.000000, 59
  union all select 'PD-YL-012', '原料', '水晶冻粉', '按包统计', '包', 1, 20, 20.000000, 60
  union all select 'PD-YL-013', '原料', '茉莉绿茶', '1件50包', '包', 50, 2100, 42.000000, 61
  union all select 'PD-YL-014', '原料', '悦鲜活', '1件12瓶', '瓶', 12, 156, 13.000000, 62
  union all select 'PD-YL-015', '原料', '氧泡泡', '盒', '盒', 1, 148, 148.000000, 63
  union all select 'PD-YL-016', '原料', '西米', '20包/件', '包', 20, 140, 7.000000, 64
  union all select 'PD-YL-017', '原料', '珍珠', '1斤/包', '包', 1, 10, 10.000000, 65
  union all select 'PD-YL-018', '原料', '黑糖', '5斤/包', '斤', 5, 65, 13.000000, 66
  union all select 'PD-YL-019', '原料', '糯米', '5斤/包', '斤', 5, 20, 4.000000, 67
  union all select 'PD-YL-020', '原料', '糯米粉', '1斤/包', '斤', 1, 8, 8.000000, 68
  union all select 'PD-YL-021', '原料', '木薯粉', '1斤/包', '斤', 1, 8, 8.000000, 69
  union all select 'PD-YL-022', '原料', '红茶', '1袋500克', '克', 500, 30, 0.060000, 70
  union all select 'PD-YL-023', '原料', '爆爆珠', '一件12瓶', '瓶', 12, 225, 18.750000, 71
  union all select 'PD-YL-024', '原料', '卡士酸奶', '24盒/件', '盒', 24, 72, 3.000000, 72
  union all select 'PD-YL-025', '原料', '奇亚籽', '500克/盒', '克', 500, 50, 0.100000, 73
  union all select 'PD-YL-026', '原料', '小胡鸭藕', '袋', '袋', 1, 1, 1.000000, 74
  union all select 'PD-YL-027', '原料', '小胡鸭海带', '袋', '袋', 1, 1, 1.000000, 75
  union all select 'PD-YL-028', '原料', '小胡鸭鸡爪', '袋', '袋', 1, 1.5, 1.500000, 76
  union all select 'PD-YL-029', '原料', '红枣', '一包2.5KG', '斤', 5, 30, 6.000000, 77
  union all select 'PD-YL-030', '原料', '小黄姜', '一瓶500ML', '毫升', 500, 12, 0.024000, 78
  union all select 'PD-YL-031', '原料', '食用盐', '1袋500克', '克', 500, 2, 0.004000, 79
  union all select 'PD-YL-032', '原料', '抹茶', '50克一袋', '克', 50, 40, 0.800000, 80
  union all select 'PD-YL-033', '原料', '生耶乳', '一件12瓶', '瓶', 12, 204, 17.000000, 81
  union all select 'PD-YL-034', '原料', '味全乳酸菌', '1瓶950ML', '瓶', 1, 14, 14.000000, 82
  union all select 'PD-SG-001', '水果', '香水柠檬', '斤', '斤', 1, 4, 4.000000, 83
  union all select 'PD-SG-002', '水果', '牛油果', '按个算', '个', 1, 5, 5.000000, 84
  union all select 'PD-SG-003', '水果', '芒果', '1件20斤', '斤', 1, 5, 5.000000, 85
  union all select 'PD-SG-004', '水果', '火龙果', '1件28斤', '斤', 1, 4, 4.000000, 86
  union all select 'PD-SG-005', '水果', '凤梨', '1件25斤', '斤', 1, 6.5, 6.500000, 87
  union all select 'PD-SG-006', '水果', '橙子', '1件38斤', '斤', 1, 3, 3.000000, 88
  union all select 'PD-SG-007', '水果', '羽衣甘蓝', '斤', '斤', 1, 6.5, 6.500000, 89
  union all select 'PD-SG-008', '水果', '榴莲', '1件6斤', '斤', 1, 56.66, 56.660000, 90
  union all select 'PD-SG-009', '水果', '梨子', '25斤/件', '斤', 1, 4.5, 4.500000, 91
  union all select 'PD-SG-010', '水果', '苹果', '斤', '斤', 1, 5, 5.000000, 92
  union all select 'PD-SG-011', '水果', '百香果', '斤', '斤', 1, 9, 9.000000, 93
  union all select 'PD-SG-012', '水果', '金桔', '斤', '斤', 1, 7, 7.000000, 94
  union all select 'PD-SG-013', '水果', '荔枝', '斤', '斤', 1, 8, 8.000000, 95
  union all select 'PD-SG-014', '水果', '西瓜', '45斤/件', '斤', 1, 3.8, 3.800000, 96
  union all select 'PD-SG-015', '水果', '杨梅', '5斤/件', '斤', 1, 18, 18.000000, 97
  union all select 'PD-SG-016', '水果', '莲雾', '1斤', '斤', 1, 25, 25.000000, 98
  union all select 'PD-SG-017', '水果', '水蜜桃', null, '斤', 1, null, null, 99
  union all select 'PD-SG-018', '水果', '青芒', null, '斤', 1, null, null, 100
  union all select 'PD-SG-019', '水果', '葡萄', null, '斤', 1, null, null, 101
  union all select 'PD-SG-020', '水果', '黄皮', null, '斤', 1, null, null, 102
  union all select 'PD-SG-021', '水果', '鲜奶', null, '瓶', 1, null, null, 103
) template
where not exists (
  select 1
  from store_inventory_item existing
  where existing.tenant_id = tenant.id
    and existing.item_code = template.item_code
);
