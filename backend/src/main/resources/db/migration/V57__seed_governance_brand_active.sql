-- V57: Seed governance infrastructure & brand safe-disable support.
-- R1-03: Append-only migration. Does NOT delete, merge or rewrite any existing data.
-- V1-V56 are unchanged; brand defaults to active (1) so existing queries are unaffected.

ALTER TABLE brand
  ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用'
  AFTER sort_order;

-- Seed governance audit table: records every governance action with full traceability.
-- Regular operation_log cleanup must exclude rows where action starts with 'seed_governance_'.
CREATE TABLE seed_governance_audit (
  id              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  tenant_id       BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
  entity_type     VARCHAR(80) NOT NULL COMMENT '实体类型：BRAND/STORE/BATCH/MATERIAL/SUPPLIER/PROFIT/EMPLOYEE',
  entity_key      VARCHAR(255) NOT NULL COMMENT '稳定业务识别键',
  action          VARCHAR(80) NOT NULL COMMENT '动作：SCAN_CANDIDATE/DISABLE/PRESERVE/MANUAL_REVIEW',
  status          VARCHAR(40) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/APPROVED/EXECUTED/FAILED/ROLLED_BACK',
  original_fingerprint TEXT COMMENT '原始Seed指纹（JSON）',
  current_fingerprint  TEXT COMMENT '当前完整字段快照（JSON）',
  lineage         VARCHAR(255) COMMENT '来源迁移与谱系：V4/V7/V43等',
  reference_count INT NOT NULL DEFAULT 0 COMMENT '直接引用计数',
  flow_count      INT NOT NULL DEFAULT 0 COMMENT '业务流水计数（入库/出库/采购/配送等）',
  attachment_count INT NOT NULL DEFAULT 0 COMMENT '附件计数',
  operation_log_count INT NOT NULL DEFAULT 0 COMMENT '操作日志引用计数',
  has_manual_edit TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否检测到人工修改信号',
  decision        VARCHAR(80) COMMENT '决策：DISABLE/PRESERVE/CANDIDATE_DELETE/CANDIDATE_DISPOSE',
  decision_by     VARCHAR(120) COMMENT '决策人',
  change_order    VARCHAR(160) COMMENT '工单/变更号',
  pre_image       MEDIUMTEXT COMMENT '执行前完整快照（JSON）',
  executed_by     VARCHAR(120) COMMENT '执行人',
  executed_at     TIMESTAMP NULL COMMENT '执行时间',
  error_summary   VARCHAR(500) COMMENT '失败原因摘要',
  rollback_plan   VARCHAR(500) COMMENT '回滚或前向修复方案',
  idempotency_key VARCHAR(160) COMMENT '幂等键',
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_tenant_entity (tenant_id, entity_type, entity_key),
  KEY idx_status (status),
  KEY idx_idempotency (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
