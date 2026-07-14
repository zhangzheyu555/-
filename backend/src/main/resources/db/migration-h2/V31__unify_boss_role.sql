update auth_user
set role = 'BOSS',
    store_id = null
where upper(role) in ('ADMIN', 'OWNER');

delete from user_store_scope
where user_id in (
  select id
  from auth_user
  where role = 'BOSS'
);

delete from role_permission
where upper(role_code) in ('ADMIN', 'OWNER');
