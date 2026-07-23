alter table profit_entry add column if not exists meituan decimal(14,2) not null default 0;
alter table profit_entry add column if not exists eleme decimal(14,2) not null default 0;
alter table profit_entry add column if not exists douyin decimal(14,2) not null default 0;
alter table profit_entry add column if not exists amap decimal(14,2) not null default 0;
