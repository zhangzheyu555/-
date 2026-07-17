-- 员工档案扩展：健康证/生日/身份证/合同/晋升节点/登录账号关联（设计见 docs/员工信息管理设计文档.md）。
-- 生日只有「月.日」（源 Excel 无年份），存字符串不造假数据；合同签署时间源数据含区间写法，保真存原文。
alter table employee
  add column birthday varchar(20) null comment '生日(月.日，如 4.14，无年份)' after hire_date,
  add column id_card_no varchar(30) null comment '身份证号码(可能不完整，按源数据保真)' after birthday,
  add column health_cert_issue_date date null comment '健康证办理日期' after id_card_no,
  add column health_cert_expire_date date null comment '健康证到期日期(空=无健康证，页面标红)' after health_cert_issue_date,
  add column contract_sign_text varchar(60) null comment '合同签署时间(源表原文)' after health_cert_expire_date,
  add column regular_date date null comment '转正时间' after contract_sign_text,
  add column trainer_date date null comment '训练员转正时间' after regular_date,
  add column shift_leader_date date null comment '领班时间' after trainer_date,
  add column manager_date date null comment '店长转正时间' after shift_leader_date,
  add column auth_user_id bigint null comment '关联登录账号(兼职员工不开号，此列为空)' after manager_date;

alter table employee
  add unique key uk_employee_auth_user (auth_user_id),
  add constraint fk_employee_auth_user foreign key (auth_user_id) references auth_user(id);

create index idx_employee_health_expire on employee (tenant_id, health_cert_expire_date);
