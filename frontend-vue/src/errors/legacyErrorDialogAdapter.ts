import { inferAppErrorTitle, reportAppError } from './appErrorDialog'

const ERROR_SELECTOR = [
  '[role="alert"]',
  '.error-box',
  '.error-state',
  '.page-error',
  '.ea-error',
  '.notice-error',
  '.notice.warning',
  '.message--error',
  '.message.error',
  '.entry-notice--error',
  '.form-message.error',
  '.msg.error',
  '.msg.warn-text',
  '.submit-error',
  '.metric-error',
  '.trend-note',
  '.detail-operation-error',
  '.filter-options-error',
  '.option-error',
  '.attachment-error',
  '.dialog-error',
].join(',')

const FIELD_ERROR_SELECTOR = [
  '.field-error',
  '.field-error-text',
  '.field-target-error',
  '.attendance-error',
  '.scope-error',
  '.filter-error',
  '[data-field-error]',
].join(',')

const PERSISTENT_STATE_SELECTOR = [
  '.business-scope-error',
  '.store-management-notice',
  '.integrity-warning',
  '.no-permission',
  '[data-persistent-error-state]',
].join(',')

export function installLegacyErrorDialogAdapter() {
  let scanQueued = false
  const observer = new MutationObserver(() => scheduleScan())

  function scheduleScan() {
    if (scanQueued) return
    scanQueued = true
    // Store actions may briefly expose a low-level loading error before the
    // owning dialog converts it into an inline validation message or a more
    // precise final error. Wait until the current microtask/render cycle is
    // settled so transient states are not promoted into a blocking dialog.
    window.setTimeout(() => {
      scanQueued = false
      scanLegacyErrors()
    }, 0)
  }

  observer.observe(document.body, {
    attributes: true,
    attributeFilter: ['class', 'hidden', 'style', 'aria-hidden'],
    childList: true,
    characterData: true,
    subtree: true,
  })
  scheduleScan()
}

function scanLegacyErrors() {
  const candidates = Array.from(document.querySelectorAll<HTMLElement>(ERROR_SELECTOR))
    .filter((element) => !element.querySelector(ERROR_SELECTOR))

  for (const element of candidates) {
    if (element.closest('[role="dialog"], [role="alertdialog"]')) continue
    if (element.matches(PERSISTENT_STATE_SELECTOR) || element.closest(PERSISTENT_STATE_SELECTOR)) continue
    if (isFieldValidation(element)) continue

    const alreadyPromoted = element.dataset.errorDialogPromoted === 'true'
    if (!alreadyPromoted && !isVisible(element)) continue
    const message = extractMessage(element)
    if (!message || element.dataset.errorDialogMessage === message) continue

    const retryControl = findRetryControl(element)
    element.dataset.errorDialogMessage = message
    element.dataset.errorDialogPromoted = 'true'
    element.hidden = true
    suppressPromotedText(element)

    reportAppError(message, {
      title: element.dataset.errorTitle?.trim() || inferAppErrorTitle(message),
      actionLabel: retryControl?.label,
      action: retryControl
        ? () => {
            if (retryControl.element.isConnected) retryControl.element.click()
          }
        : undefined,
    })
  }
}

function suppressPromotedText(element: HTMLElement) {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
  const textNodes: Text[] = []
  while (walker.nextNode()) {
    const textNode = walker.currentNode as Text
    const parent = textNode.parentElement
    if (parent?.closest('button, a, [aria-hidden="true"]')) continue
    textNodes.push(textNode)
  }
  for (const textNode of textNodes) textNode.data = ''
}

function isFieldValidation(element: HTMLElement) {
  if (element.matches(FIELD_ERROR_SELECTOR) || element.closest(FIELD_ERROR_SELECTOR)) return true
  return (element.matches('small, span') && Boolean(element.closest('label')))
}

function isVisible(element: HTMLElement) {
  if (!element.isConnected || element.hidden || element.getAttribute('aria-hidden') === 'true') return false
  const style = window.getComputedStyle(element)
  return style.display !== 'none'
    && style.visibility !== 'hidden'
    && Number(style.opacity || '1') > 0
    && element.getClientRects().length > 0
}

function extractMessage(element: HTMLElement) {
  const clone = element.cloneNode(true) as HTMLElement
  clone.querySelectorAll('button, a, svg, [aria-hidden="true"]').forEach((node) => node.remove())
  return (clone.textContent || '').replace(/\s+/g, ' ').trim()
}

function findRetryControl(element: HTMLElement) {
  const controls = Array.from(element.querySelectorAll<HTMLElement>('button:not(:disabled), a[href]'))
  for (const control of controls) {
    const label = (control.getAttribute('aria-label') || control.textContent || control.title || '').replace(/\s+/g, ' ').trim()
    if (/重试|重新加载|重新读取|重新查询|刷新|返回重试/.test(label)) return { element: control, label }
  }
  return null
}
