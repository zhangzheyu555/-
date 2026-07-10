-- V27: Fix corrupted Chinese characters in inspection standards
-- The original data was inserted via PowerShell pipe which mangled UTF-8 encoding.
-- This migration deletes the corrupted data and re-inserts correct Chinese text.

-- Delete corrupted data
delete from inspection_standard_item where tenant_id = 1;
delete from inspection_standard_version where tenant_id = 1;

-- Insert standard version
insert into inspection_standard_version (tenant_id, version, title, full_score, effective_date, status, created_by)
values (1, '2026-v1', '门店食品安全稽核标准 (V1)', 100.00, '2026-01-01', 'ACTIVE', null);

set @std_id = (select id from inspection_standard_version where tenant_id = 1 and version = '2026-v1');

-- 维度: 人员卫生 (2 items: 1 scoring + 1 red-line)
insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order) values
(1, @std_id, '人员卫生', 'P01', '员工正确穿戴工作服和口罩', '所有接触食品的员工必须穿戴清洁工作服、口罩和发网，指甲修剪整齐，不佩戴首饰', 10, 0, 1, 1),
(1, @std_id, '人员卫生', 'P02', '健康证有效且公示', '所有从业人员健康证必须在有效期内且在店内公示，无证或过期视为红线', 10, 1, 1, 2);

-- 维度: 环境卫生 (3 items: all scoring)
insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order) values
(1, @std_id, '环境卫生', 'E01', '地面清洁无杂物', '操作间和用餐区地面保持清洁，无食物残渣、油渍和水渍，每日至少清洁两次', 8, 0, 1, 3),
(1, @std_id, '环境卫生', 'E02', '垃圾及时清理', '垃圾桶必须加盖，每日清理不少于两次，无溢出和异味', 8, 0, 1, 4),
(1, @std_id, '环境卫生', 'E03', '墙面和天花板清洁', '墙面、天花板无霉斑、无蛛网、无漆面脱落，排风口定期清洁', 6, 0, 1, 5);

-- 维度: 食材管理 (3 items: all red-line)
insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order) values
(1, @std_id, '食材管理', 'M01', '食材储存温度达标', '冷藏温度0-8℃，冷冻温度-18℃以下，每日两次温度记录完整，异常温度需立即处理', 10, 1, 1, 6),
(1, @std_id, '食材管理', 'M02', '食材标签和保质期管理', '所有食材有明确标签（品名、生产日期、保质期），无过期食材，严格先进先出', 12, 1, 1, 7),
(1, @std_id, '食材管理', 'M03', '生熟分开存放', '生食和熟食严格分区存放，使用不同颜色标识的容器和工具，避免交叉污染', 10, 1, 1, 8);

-- 维度: 设备管理 (2 items: both scoring)
insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order) values
(1, @std_id, '设备管理', 'D01', '冷藏冷冻设备运行正常', '冷藏柜和冷冻柜温度显示正常，无异常噪音，定期除霜，密封条完好', 8, 0, 1, 9),
(1, @std_id, '设备管理', 'D02', '消毒设备正常运行', '消毒柜、紫外线灯等消毒设备运行正常，有每日使用记录', 6, 0, 1, 10);

-- 维度: 操作规范 (2 items: both scoring)
insert into inspection_standard_item (tenant_id, standard_version_id, dimension, code, title, description, suggested_score, red_line, enabled, sort_order) values
(1, @std_id, '操作规范', 'O01', '食品加工操作规范', '加工过程符合食品安全操作规范，生熟工具不交叉使用，操作台面及时清洁消毒', 8, 0, 1, 11),
(1, @std_id, '操作规范', 'O02', '餐具清洗消毒到位', '餐具清洗消毒流程规范（一刮二洗三冲四消毒五保洁），已消毒餐具存放于密闭保洁柜', 4, 0, 1, 12);
