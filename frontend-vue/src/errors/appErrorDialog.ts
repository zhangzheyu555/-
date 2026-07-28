import { readonly, shallowRef, type App } from 'vue'

export interface AppErrorDialogOptions {
  title?: string
  actionLabel?: string
  action?: () => unknown | Promise<unknown>
  sourceKey?: string
}

interface AppErrorDialogEntry {
  id: number
  title: string
  message: string
  actionLabel: string
  action?: () => unknown | Promise<unknown>
  dedupeKey: string
}

const activeEntry = shallowRef<AppErrorDialogEntry | null>(null)
const pendingEntries: AppErrorDialogEntry[] = []
let nextEntryId = 1

export const currentAppErrorDialog = readonly(activeEntry)

export function reportAppError(reason: unknown, options: AppErrorDialogOptions = {}) {
  const message = safeErrorMessage(reason)
  if (!message || isCancelledError(reason)) return null

  const title = options.title?.trim() || inferAppErrorTitle(message)
  const dedupeKey = options.sourceKey?.trim() || `${title}:${message}`
  const duplicate = findDuplicate(dedupeKey)
  if (duplicate) {
    if (!duplicate.action && options.action) {
      duplicate.action = options.action
      duplicate.actionLabel = options.actionLabel?.trim() || '重试'
      if (activeEntry.value?.id === duplicate.id) activeEntry.value = { ...duplicate }
    }
    return duplicate.id
  }

  const entry: AppErrorDialogEntry = {
    id: nextEntryId++,
    title,
    message,
    actionLabel: options.actionLabel?.trim() || (options.action ? '重试' : '我知道了'),
    action: options.action,
    dedupeKey,
  }
  if (activeEntry.value) {
    if (pendingEntries.length < 8) pendingEntries.push(entry)
  } else {
    activeEntry.value = entry
  }
  return entry.id
}

export function dismissAppErrorDialog() {
  activeEntry.value = pendingEntries.shift() || null
}

export async function confirmAppErrorDialog() {
  const entry = activeEntry.value
  if (!entry) return
  dismissAppErrorDialog()
  if (!entry.action) return
  try {
    await entry.action()
  } catch (error) {
    reportAppError(error, { title: '重试未完成' })
  }
}

export function installRuntimeErrorDialogs(app: App) {
  const previousHandler = app.config.errorHandler
  app.config.errorHandler = (error, instance, info) => {
    console.error('[Vue Error]', info, error)
    reportAppError(error, { title: '页面运行出现问题', sourceKey: `vue:${info}:${safeErrorMessage(error)}` })
    previousHandler?.(error, instance, info)
  }

  const handleWindowError = (event: ErrorEvent) => {
    reportAppError(event.error || event.message, {
      title: '页面运行出现问题',
      sourceKey: `window:${event.filename}:${event.lineno}:${event.message}`,
    })
  }
  const handleUnhandledRejection = (event: PromiseRejectionEvent) => {
    if (isCancelledError(event.reason)) return
    console.error('[Unhandled Promise Rejection]', event.reason)
    reportAppError(event.reason, { title: '操作未完成' })
  }

  window.addEventListener('error', handleWindowError)
  window.addEventListener('unhandledrejection', handleUnhandledRejection)
}

export function inferAppErrorTitle(message: string) {
  if (/登录|账号或密码|密码修改/.test(message)) return '登录未完成'
  if (/导出|下载/.test(message)) return '导出未完成'
  if (/上传|附件|照片/.test(message)) return '上传未完成'
  if (/保存|写入|录入/.test(message)) return '保存未完成'
  if (/删除|移出/.test(message)) return '删除未完成'
  if (/加载|读取|获取|查询/.test(message)) return '数据加载失败'
  if (/提交|审核|确认|发放|收货|发货/.test(message)) return '操作未完成'
  return '操作未完成'
}

function safeErrorMessage(reason: unknown) {
  const raw = reason instanceof Error
    ? reason.message
    : typeof reason === 'string'
      ? reason
      : reason && typeof reason === 'object' && 'message' in reason
        ? String((reason as { message?: unknown }).message || '')
        : ''
  const message = raw.replace(/\s+/g, ' ').trim()
  if (!message) return '系统暂时无法完成该操作，请稍后重试。'
  if (looksTechnical(message)) return '系统暂时无法完成该操作，请稍后重试。'
  return message.length > 800 ? `${message.slice(0, 797)}...` : message
}

function looksTechnical(message: string) {
  return [
    /java\./i,
    /org\.springframework/i,
    /stack\s*trace/i,
    /sql(state)?/i,
    /noclassdeffounderror/i,
    /typeerror:/i,
    /referenceerror:/i,
  ].some((pattern) => pattern.test(message))
}

function isCancelledError(reason: unknown) {
  if (!reason || typeof reason !== 'object') return false
  return 'code' in reason && (reason as { code?: unknown }).code === 'REQUEST_CANCELLED'
}

function findDuplicate(dedupeKey: string) {
  if (activeEntry.value?.dedupeKey === dedupeKey) return activeEntry.value
  return pendingEntries.find((entry) => entry.dedupeKey === dedupeKey) || null
}
