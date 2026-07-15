-- V52: tenant-scoped training videos backed by the existing protected attachment storage.
-- Video bytes stay in warehouse_attachment; this table stores only business metadata.

create table training_video (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  video_code varchar(120) not null,
  attachment_id bigint not null,
  course_id bigint null,
  title varchar(160) not null,
  category varchar(120) null,
  description varchar(500) null,
  duration_seconds decimal(10,2) null,
  enabled tinyint(1) not null default 1,
  sort_order int not null default 0,
  created_by bigint not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_training_video_code (tenant_id, video_code),
  unique key uk_training_video_attachment (tenant_id, attachment_id),
  index idx_training_video_course (tenant_id, course_id, sort_order),
  index idx_training_video_enabled (tenant_id, enabled, sort_order),
  constraint fk_training_video_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_training_video_attachment foreign key (attachment_id) references warehouse_attachment(id),
  constraint fk_training_video_course foreign key (course_id) references training_course(id),
  constraint fk_training_video_creator foreign key (created_by) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

-- watched_seconds is server-approved continuous watch time. last_position is resume-only.
create table training_video_progress (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  video_id bigint not null,
  user_id bigint not null,
  user_name varchar(120) not null,
  store_id varchar(64) null,
  watched_seconds decimal(10,2) not null default 0,
  duration_seconds decimal(10,2) null,
  progress_percent decimal(5,2) not null default 0,
  last_position decimal(10,2) not null default 0,
  completed tinyint(1) not null default 0,
  completed_at timestamp null,
  last_reported_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null on update current_timestamp,
  unique key uk_training_video_progress (tenant_id, video_id, user_id),
  index idx_training_video_progress_user (tenant_id, user_id, last_reported_at),
  constraint chk_training_video_progress_watched check (watched_seconds >= 0),
  constraint chk_training_video_progress_percent check (progress_percent >= 0 and progress_percent <= 100),
  constraint fk_training_video_progress_tenant foreign key (tenant_id) references tenant(id),
  constraint fk_training_video_progress_video foreign key (video_id) references training_video(id),
  constraint fk_training_video_progress_user foreign key (user_id) references auth_user(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
