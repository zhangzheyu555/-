-- V79: server-owned recipe definitions. Product sales can only be converted through these tenant/brand
-- scoped rows; the browser is never an authority for grams, yield, or conversion factors.
create table qmai_recipe_definition (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  brand_code varchar(40) not null,
  product_name varchar(300) not null,
  active tinyint(1) not null default 1,
  version_no int not null default 1,
  updated_by_user_id bigint null,
  updated_by_name varchar(120) null,
  updated_at timestamp not null default current_timestamp on update current_timestamp,
  created_at timestamp not null default current_timestamp,
  unique key uk_qmai_recipe_definition (tenant_id, brand_code, product_name),
  constraint fk_qmai_recipe_definition_tenant foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table qmai_recipe_ingredient (
  id bigint not null auto_increment primary key,
  recipe_id bigint not null,
  material_name varchar(300) not null,
  fruit_name varchar(160) not null,
  grams_per_cup decimal(18,3) not null,
  conversion_kind varchar(16) not null,
  conversion_factor decimal(18,6) null,
  sort_order int not null default 0,
  constraint chk_qmai_recipe_kind check (conversion_kind in ('FLESH', 'JUICE', 'ONE')),
  constraint fk_qmai_recipe_ingredient_recipe foreign key (recipe_id) references qmai_recipe_definition(id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
