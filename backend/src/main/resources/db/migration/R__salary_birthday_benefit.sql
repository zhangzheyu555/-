set @salary_birthday_benefit_sql := if(
  exists(
    select 1
    from information_schema.columns
    where table_schema = database()
      and table_name = 'salary_record'
      and column_name = 'birthday_benefit'
  ),
  'select 1',
  'alter table salary_record add column birthday_benefit decimal(14,2) not null default 0 comment ''员工福利（生日）金额'' after seniority'
);
prepare salary_birthday_benefit_stmt from @salary_birthday_benefit_sql;
execute salary_birthday_benefit_stmt;
deallocate prepare salary_birthday_benefit_stmt;
