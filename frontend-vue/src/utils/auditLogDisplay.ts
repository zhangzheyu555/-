import type { OperationLog } from '../api/operations'

const ACTION_LABELS: Record<string, string> = {
  logout: '退出登录',
  delete: '删除记录',
  permission_denied: '权限拒绝',
  legacy_storage_write: '旧系统数据写入',
  legacy_kv_structured_migration: '旧系统数据结构化迁移',
  attachment_upload: '上传附件',
  attachment_download: '下载附件',
  inspection_rectification_evidence_download: '下载巡店整改证据',

  'employee_assistant.chat': '使用员工服务助手',
  'employee_assistant.health': '检查员工服务助手状态',
  'employee_assistant.feedback': '提交员工服务助手反馈',
  'employee_assistant.handoff_create': '创建员工助手人工转接',
  'employee_assistant.handoff_claim': '领取员工助手人工事项',
  'employee_assistant.handoff_reply': '回复员工助手人工事项',
  'employee_assistant.handoff_close': '关闭员工助手人工事项',
  'employee_assistant.knowledge_create': '创建员工助手知识草稿',
  'employee_assistant.knowledge_update': '更新员工助手知识草稿',
  'employee_assistant.knowledge_publish': '发布员工助手知识',
  'employee_assistant.knowledge_rollback': '回滚员工助手知识',
  'knowledge_base.document_upload': '上传知识库资料',
  'knowledge_base.document_publish': '发布知识库资料',
  'knowledge_base.document_archive': '下架知识库资料',
  'knowledge_base.document_download': '下载知识库原始资料',

  daily_loss_submit: '提交每日报损单',
  daily_loss_approve: '审核通过每日报损单',
  daily_loss_attachment_upload: '上传每日报损附件',

  expense_save: '保存报销单',
  expense_submit: '提交报销单',
  expense_request_info: '要求补充报销资料',
  expense_approve: '审核通过报销单',
  expense_reject: '驳回报销单',
  expense_delete: '删除报销单',
  expense_supplement_submit: '提交报销补充资料',
  expense_supplement_attachment_download: '下载报销补充附件',

  salary_save: '保存工资记录',
  salary_generate: '生成工资记录',
  salary_history_import: '导入历史工资记录',
  salary_submit: '提交工资审核',
  salary_approve: '审核通过工资记录',
  salary_reject: '驳回工资记录',
  salary_mark_paid: '标记工资已发放',
  salary_lock: '锁定工资记录',
  salary_export: '导出工资表',
  salary_delete: '删除工资记录',

  inspection_save: '保存巡店记录',
  inspection_delete: '删除巡店记录',
  inspection_detection_confirm: '确认巡店图片识别结果',
  inspection_detection_bind: '绑定巡店图片识别结果',
  inspection_history_recalculated: '重新计算历史巡店评分',
  inspection_history_manual_review: '人工复核历史巡店评分',
  inspection_score_recalculated_for_export: '导出前重算巡店评分',
  inspection_rectification_legacy_completion_rejected: '拒绝旧版整改完成入口',

  todo_done: '完成待办',
  todo_resolve: '处理待办',
  todo_close: '关闭待办',
  todo_reject: '驳回待办',
  todo_escalate: '升级待办',
  todo_reopen: '重新打开待办',

  finance_todo_done: '完成财务待办',
  finance_todo_resolve: '处理财务待办',
  finance_todo_close: '关闭财务待办',
  finance_todo_reject: '驳回财务待办',
  finance_todo_escalate: '升级财务待办',

  'profit entry deleted': '利润记录已删除',
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  auth_session: '登录会话',
  auth_user: '账号',
  business_todo: '经营待办',
  business_todo_attachment: '待办附件',
  role_todo: '角色待办',
  finance_workbench: '财务工作台待办',
  daily_loss_record: '每日报损单',
  expense_claim: '报销单',
  employee_assistant: '员工服务助手请求',
  employee_assistant_handoff: '员工助手人工转接',
  employee_assistant_knowledge: '员工助手知识库',
  knowledge_base_document: '知识库资料',
  profit_entry: '利润记录',
  profit_import_preview: '利润导入预览',
  salary_record: '工资记录',
  inspection_record: '巡店记录',
  inspection_rectification: '巡店整改',
  training_exam_campaign: '培训考试',
  training_video: '培训视频',
  training_material: '培训资料',
  store_inventory_check: '门店盘存单',
  warehouse: '仓库单据',
  warehouse_item: '仓库商品',
  warehouse_purchase_order: '采购单',
  warehouse_delivery_order: '配送出库单',
  warehouse_return_order: '配送退货单',
  warehouse_transfer_order: '仓间调拨单',
  data_export: '数据导出',
  kv_storage: '旧系统键值数据',
  qmai_sync_batch: '企迈经营数据同步',
  qmai_platform_config: '企迈平台配置',
  qmai_shop_discovery: '企迈授权门店',
  qmai_data_export: '企迈数据导出',
  store_branch: '门店档案',
}

const REASON_LABELS: Record<string, string> = {
  'state=READY': '服务状态：可用',
  'state=UNCONFIGURED': '服务状态：未配置',
  'state=UNAVAILABLE': '服务状态：暂不可用',
  'state=AUTH_FAILED': '服务状态：认证失败',
  'result=READY': '处理结果：可用',
  'expense claim saved': '报销单已保存',
  'expense claim submitted': '报销单已提交',
  'expense claim approved': '报销单已审核通过',
  'expense claim rejected': '报销单已驳回',
  'expense claim deleted': '报销单已删除',
  'profit entry deleted': '利润记录已删除',
  'legacy KV write via /api/storage': '旧系统键值数据写入',
  'Vue3 运营中心': '运营中心保存',
}

const STATUS_LABELS: Record<string, string> = {
  READY: '可用',
  UNCONFIGURED: '未配置',
  UNAVAILABLE: '暂不可用',
  AUTH_FAILED: '认证失败',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  CANCELLED: '已取消',
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  APPROVED: '已审核',
  REJECTED: '已驳回',
  PAID: '已发放',
  LOCKED: '已锁定',
  PENDING: '待处理',
  DONE: '已完成',
  TODO_DONE: '待办已完成',
  RESOLVE: '处理',
  CLOSE: '关闭',
  RISK: '风险',
  LOSS_OUT: '报损出库',
}

const RESULT_LABELS: Record<string, string> = {
  answer: '已回答',
  handoff: '转人工',
  blocked: '已拦截',
  unavailable: '暂不可用',
  ready: '可用',
  success: '成功',
  failed: '失败',
}

const TECHNICAL_TOKEN_LABELS: Record<string, string> = {
  auth: '登录',
  session: '会话',
  user: '用户',
  permission: '权限',
  denied: '拒绝',
  employee: '员工',
  assistant: '服务助手',
  health: '状态检查',
  chat: '对话',
  feedback: '反馈',
  handoff: '人工转接',
  knowledge: '知识库',
  create: '创建',
  update: '更新',
  publish: '发布',
  rollback: '回滚',
  claim: '领取',
  reply: '回复',
  close: '关闭',
  daily: '每日',
  loss: '报损',
  submit: '提交',
  approve: '审核通过',
  reject: '驳回',
  attachment: '附件',
  upload: '上传',
  download: '下载',
  expense: '报销',
  supplement: '补充资料',
  request: '要求',
  info: '信息',
  salary: '工资',
  generate: '生成',
  history: '历史',
  import: '导入',
  mark: '标记',
  paid: '已发放',
  lock: '锁定',
  export: '导出',
  inspection: '巡店',
  rectification: '整改',
  evidence: '证据',
  detection: '图片识别',
  bind: '绑定',
  confirm: '确认',
  score: '评分',
  recalculated: '重新计算',
  todo: '待办',
  business: '经营',
  role: '角色',
  finance: '财务',
  workbench: '工作台',
  profit: '利润',
  entry: '记录',
  preview: '预览',
  warehouse: '仓库',
  storage: '存储',
  item: '商品',
  purchase: '采购',
  delivery: '配送',
  return: '退货',
  transfer: '调拨',
  order: '单据',
  training: '培训',
  exam: '考试',
  campaign: '场次',
  video: '视频',
  material: '资料',
  qmai: '企迈',
  sync: '同步',
  batch: '批次',
  platform: '平台',
  config: '配置',
  shop: '门店',
  discovery: '授权门店',
  data: '数据',
  legacy: '旧系统',
  kv: '键值',
  structured: '结构化',
  migration: '迁移',
}

export function formatAuditAction(action: string | undefined) {
  const value = clean(action)
  if (!value) return '-'
  if (ACTION_LABELS[value]) return ACTION_LABELS[value]
  if (hasChinese(value)) return value
  if (value.startsWith('finance_todo_')) {
    return `财务待办：${formatActionSuffix(value.slice('finance_todo_'.length))}`
  }
  if (value.startsWith('todo_')) {
    return `待办处理：${formatActionSuffix(value.slice('todo_'.length))}`
  }
  return labelFromTechnicalCode(value, '系统操作')
}

export function formatAuditTarget(log: OperationLog) {
  const targetType = clean(log.targetType)
  const targetId = clean(log.targetId)
  const label = targetType
    ? TARGET_TYPE_LABELS[targetType] || (hasChinese(targetType) ? targetType : labelFromTechnicalCode(targetType, '业务对象'))
    : '业务对象'

  if (!targetId) return label
  return `${label}（${formatTargetId(targetType, targetId)}）`
}

export function formatAuditReason(reason: string | undefined) {
  const value = clean(reason)
  if (!value) return '-'
  if (REASON_LABELS[value]) return REASON_LABELS[value]
  if (hasChinese(value)) return translateEmbeddedTechnicalText(value)
  return translateTechnicalReason(value)
}

export function rawAuditTarget(log: OperationLog) {
  const targetType = clean(log.targetType)
  const targetId = clean(log.targetId)
  return [targetType, targetId].filter(Boolean).join(' ') || '-'
}

function formatActionSuffix(value: string) {
  const label = ACTION_LABELS[value]
  if (label) return label
  return labelFromTechnicalCode(value, '处理')
}

function formatTargetId(targetType: string, targetId: string) {
  if (targetType === 'auth_session') return `用户：${targetId}`
  if (targetType === 'data_export' || targetType === 'qmai_data_export') return `范围：${formatExportScope(targetId)}`

  const normalized = targetId
    .replace(/^biz-todo-/i, '')
    .replace(/^todo-act-/i, '')
    .replace(/^todo-/i, '')
    .replace(/^expense-/i, '')
    .replace(/^csv-/i, '')

  return `编号：${shortId(normalized)}`
}

function formatExportScope(value: string) {
  const scopeLabels: Record<string, string> = {
    all: '全部门店',
    summary: '汇总',
    products: '商品销售',
    stores: '门店营业额',
  }
  return scopeLabels[value] || shortId(value)
}

function translateTechnicalReason(value: string) {
  const parts = value.split(';').map((part) => part.trim()).filter(Boolean)
  if (parts.length > 1) {
    const translated = parts.map(translateReasonPart).filter(Boolean)
    return translated.length ? translated.join('；') : '系统记录'
  }
  return translateReasonPart(value) || labelFromTechnicalCode(value, '系统记录')
}

function translateReasonPart(part: string) {
  const [rawKey, rawValue] = part.split('=').map((item) => item.trim())
  if (!rawKey || rawValue === undefined) {
    return hasChinese(part) ? translateEmbeddedTechnicalText(part) : labelFromTechnicalCode(part, '系统记录')
  }
  const key = labelFromReasonKey(rawKey)
  const value = labelFromReasonValue(rawValue)
  return `${key}：${value}`
}

function labelFromReasonKey(key: string) {
  const labels: Record<string, string> = {
    state: '服务状态',
    result: '处理结果',
    outcome: '处理结果',
    input_redacted: '输入脱敏',
    knowledge_ms: '知识库耗时',
    model_ms: '模型耗时',
    total_ms: '总耗时',
    recordId: '巡店记录',
    snapshotId: '评分快照',
    sourceModule: '来源模块',
    todo_id: '待办编号',
    batch_id: '批次编号',
    movement_type: '出入库类型',
  }
  return labels[key] || labelFromTechnicalCode(key, '字段')
}

function labelFromReasonValue(value: string) {
  const normalized = value.trim()
  const upper = normalized.toUpperCase()
  if (STATUS_LABELS[upper]) return STATUS_LABELS[upper]
  if (RESULT_LABELS[normalized.toLowerCase()]) return RESULT_LABELS[normalized.toLowerCase()]
  if (normalized === 'true') return '是'
  if (normalized === 'false') return '否'
  if (/^\d+(\.\d+)?$/.test(normalized)) return normalized
  if (hasChinese(normalized)) return translateEmbeddedTechnicalText(normalized)
  return shortId(stripKnownPrefix(normalized))
}

function translateEmbeddedTechnicalText(value: string) {
  let result = value
  for (const [status, label] of Object.entries(STATUS_LABELS)) {
    result = result.replace(new RegExp(`\\b${escapeRegExp(status)}\\b`, 'g'), label)
  }
  return result
    .replace(/\brecordId=/g, '巡店记录：')
    .replace(/\bsnapshotId=/g, '评分快照：')
    .replace(/\bsourceModule=/g, '来源模块：')
    .replace(/\btodo_id=/g, '待办编号：')
    .replace(/\bbatch_id=/g, '批次编号：')
    .replace(/\bmovement_type=/g, '出入库类型：')
}

function labelFromTechnicalCode(value: string, fallback: string) {
  const tokens = value
    .split(/[._\-\s/]+/)
    .map((token) => token.trim().toLowerCase())
    .filter(Boolean)

  const labels = tokens
    .map((token) => TECHNICAL_TOKEN_LABELS[token])
    .filter(Boolean)

  if (!labels.length) return fallback
  return Array.from(new Set(labels)).join('')
}

function stripKnownPrefix(value: string) {
  return value
    .replace(/^biz-todo-/i, '')
    .replace(/^todo-act-/i, '')
    .replace(/^todo-/i, '')
    .replace(/^expense-/i, '')
    .replace(/^batch-/i, '')
}

function shortId(value: string) {
  if (value.length <= 24) return value
  return `${value.slice(0, 12)}...${value.slice(-6)}`
}

function clean(value: string | undefined | null) {
  return value == null ? '' : String(value).trim()
}

function hasChinese(value: string) {
  return /[\u4e00-\u9fff]/.test(value)
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
