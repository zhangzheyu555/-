import { onBeforeUnmount, onMounted } from 'vue'

export type ForegroundReloadReason = 'focus' | 'visibility'

interface ForegroundReloadOptions {
  staleAfterMs?: number
  canReload?: () => boolean
}

const DEFAULT_STALE_AFTER_MS = 60_000

export function useForegroundReload(
  reload: (reason: ForegroundReloadReason) => boolean | void | Promise<boolean | void>,
  options: ForegroundReloadOptions = {},
) {
  const staleAfterMs = options.staleAfterMs ?? DEFAULT_STALE_AFTER_MS
  let lastSuccessAt = 0
  let foregroundRequest: Promise<void> | null = null

  function markFresh(at = Date.now()) {
    lastSuccessAt = at
  }

  function isStale(now = Date.now()) {
    return !lastSuccessAt || now - lastSuccessAt >= staleAfterMs
  }

  function reloadWhenStale(reason: ForegroundReloadReason) {
    if (document.visibilityState !== 'visible' || options.canReload?.() === false || !isStale()) {
      return foregroundRequest
    }
    if (foregroundRequest) return foregroundRequest

    foregroundRequest = Promise.resolve()
      .then(() => reload(reason))
      .then((succeeded) => {
        if (succeeded !== false) markFresh()
      })
      .catch(() => {
        // 页面加载函数负责展示业务错误；前台事件不能产生未处理的 Promise rejection。
      })
      .finally(() => {
        foregroundRequest = null
      })
    return foregroundRequest
  }

  function handleFocus() {
    void reloadWhenStale('focus')
  }

  function handleVisibilityChange() {
    void reloadWhenStale('visibility')
  }

  onMounted(() => {
    window.addEventListener('focus', handleFocus)
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('focus', handleFocus)
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return {
    markFresh,
    isStale,
    reloadWhenStale,
  }
}
