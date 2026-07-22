const EDGE_WIDTH_PX = 40
const MIN_HORIZONTAL_DISTANCE_PX = 72

/**
 * Keeps Android/WeChat edge-back gestures on top-level tabs inside the app.
 * The home tab deliberately does not use these handlers, so its normal exit
 * behavior remains unchanged.
 */
export function createEdgeSwipeToHomeHandlers() {
  let start: { x: number; y: number; fromEdge: boolean } | null = null
  let navigating = false

  function onTouchStart(event: TouchEvent): void {
    const touch = event.touches[0]
    if (!touch) return
    const width = Number(uni.getSystemInfoSync().windowWidth || 0)
    start = {
      x: touch.clientX,
      y: touch.clientY,
      fromEdge: width > 0 && (touch.clientX <= EDGE_WIDTH_PX || touch.clientX >= width - EDGE_WIDTH_PX),
    }
  }

  function onTouchEnd(event: TouchEvent): void {
    const touch = event.changedTouches[0]
    const started = start
    start = null
    if (!touch || !started || !started.fromEdge || navigating) return

    const horizontalDistance = Math.abs(touch.clientX - started.x)
    const verticalDistance = Math.abs(touch.clientY - started.y)
    if (horizontalDistance < MIN_HORIZONTAL_DISTANCE_PX || horizontalDistance <= verticalDistance) return

    navigating = true
    uni.switchTab({ url: '/pages/home/index' })
  }

  return { onTouchStart, onTouchEnd }
}
