export function operationErrorText(cause: unknown, fallback = '操作失败，请稍后重试。') {
  return cause instanceof Error && cause.message.trim() ? cause.message : fallback
}

/**
 * Shows failures for a user-initiated write operation. Do not use this for
 * background loading or pull-to-refresh errors, which stay as inline notices.
 */
export function showOperationFailure(title: string, cause: unknown, fallback?: string) {
  const content = operationErrorText(cause, fallback)
  uni.showModal({ title, content, showCancel: false })
  return content
}

/** Shows a clear acknowledgement after a user-initiated write succeeds. */
export function showOperationSuccess(title: string, content = '操作已完成。') {
  return new Promise<void>((resolve) => {
    uni.showModal({
      title,
      content,
      showCancel: false,
      success: () => resolve(),
      fail: () => resolve(),
    })
  })
}
