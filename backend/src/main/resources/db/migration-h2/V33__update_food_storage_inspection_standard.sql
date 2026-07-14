-- H2 test equivalent of the MySQL V33 data migration.
update inspection_standard_item
set title = '食品原料与非食品用品分区存放',
    description = '茶叶、糖浆、奶制品、水果、珍珠等食品原料及成品配料应分类密封存放；清洁剂、消毒剂和私人物品必须设置独立区域，不得与食品同柜混放。',
    updated_at = current_timestamp
where enabled = 1
  and standard_version_id in (
    select id from inspection_standard_version where status = 'ACTIVE'
  )
  and (
    code = 'M03'
    or regexp_like(concat(coalesce(title, ''), coalesce(description, '')), '生熟|生肉|熟肉')
  );
