alter table auth_user
  add column password_change_required tinyint(1) not null default 0;
