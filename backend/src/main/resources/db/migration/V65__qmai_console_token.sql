-- 企迈商户后台登录令牌（qm_seller_token cookie 值）。
-- 用户浏览器登录后复制粘贴，后端带此 cookie 调后台网关拉数据；仅后端存，绝不下发前端。
-- 令牌有时效，过期后需重新粘贴。
alter table qmai_platform_config
  add column console_token text null;
