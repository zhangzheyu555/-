-- 企迈凭证支持多品牌：同一租户可分别配置「茹菓」「霸王茶姬」等多套凭证。
-- 既有单条配置归入默认品牌 ruguo；先建复合唯一键（tenant_id 前缀可继续支撑外键）再删旧唯一键。
alter table qmai_platform_config
  add column brand varchar(40) not null default 'ruguo' after tenant_id;
alter table qmai_platform_config
  add constraint uk_qmai_config_tenant_brand unique (tenant_id, brand);
alter table qmai_platform_config
  drop index uk_qmai_config_tenant;
-- 企迈商户后台登录凭证（console.qmai.cn），仅后端登录抓取数据用，绝不下发前端。
alter table qmai_platform_config
  add column console_account varchar(200) not null default '',
  add column console_password varchar(200) not null default '';
