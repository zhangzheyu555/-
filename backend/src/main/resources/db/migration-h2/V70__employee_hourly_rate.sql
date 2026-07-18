-- V68: 员工时薪选择（兼职/实习/长期阿姨适用）。空=按默认口径（实习兼职15、长期阿姨正式18，工资模板新版）。
alter table employee
  add column hourly_rate decimal(8,2) null comment '时薪(元/小时)，兼职/实习/阿姨适用；空=默认' after base_salary;
