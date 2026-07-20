alter table store_requisition alter column status set default 'SUBMITTED';
alter table store_requisition alter column total_amount set default 0;
alter table store_requisition_line alter column requested_quantity set default 0;
alter table store_requisition_line alter column shipped_quantity set default 0;
alter table store_requisition_line alter column unit_price set default 0;
alter table store_requisition_line alter column amount set default 0;
