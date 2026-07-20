-- V56 used MODIFY COLUMN for Chinese comments and removed defaults originally
-- declared by V4. Restore the complete requisition insert contract.
alter table store_requisition
  modify column status varchar(40) not null default 'SUBMITTED' comment '状态',
  modify column total_amount decimal(14,2) not null default 0 comment '总金额';

alter table store_requisition_line
  modify column requested_quantity decimal(14,2) not null default 0 comment '申请数量',
  modify column shipped_quantity decimal(14,2) not null default 0 comment '发货数量',
  modify column unit_price decimal(14,2) not null default 0 comment '单位价格',
  modify column amount decimal(14,2) not null default 0 comment '金额';
