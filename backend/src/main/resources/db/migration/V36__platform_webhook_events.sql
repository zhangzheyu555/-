create table if not exists platform_webhook_event (
  id bigint not null auto_increment primary key,
  provider varchar(32) not null,
  event_id varchar(160) not null,
  event_type varchar(80) null,
  payload_sha256 char(64) not null,
  processing_status varchar(32) not null,
  duplicate_count int not null default 0,
  received_at timestamp not null default current_timestamp,
  last_received_at timestamp not null,
  unique key uk_platform_webhook_event (provider, event_id),
  index idx_platform_webhook_status (provider, processing_status, received_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
