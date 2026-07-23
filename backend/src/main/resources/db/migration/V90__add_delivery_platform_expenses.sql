alter table profit_entry add column meituan decimal(14,2) not null default 0 after commission;
alter table profit_entry add column eleme decimal(14,2) not null default 0 after meituan;
alter table profit_entry add column douyin decimal(14,2) not null default 0 after eleme;
alter table profit_entry add column amap decimal(14,2) not null default 0 after douyin;
