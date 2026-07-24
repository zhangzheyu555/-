-- H2 test equivalent of the production MySQL V102 migration.
update training_video
set category = '设备培训'
where category = 'è®¾å¤‡åŸ¹è®­';
