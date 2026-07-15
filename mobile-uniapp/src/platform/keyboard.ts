import { computed, onMounted, onUnmounted, ref } from 'vue'

export function useKeyboardInset() {
  const height = ref(0)
  let h5Handler: (() => void) | undefined
  let uniHandler: ((event: { height: number }) => void) | undefined

  onMounted(() => {
    // #ifdef H5
    const viewport = window.visualViewport
    if (viewport) {
      h5Handler = () => {
        height.value = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)
      }
      viewport.addEventListener('resize', h5Handler)
      viewport.addEventListener('scroll', h5Handler)
      h5Handler()
    }
    // #endif

    // #ifdef MP-WEIXIN
    uniHandler = (event) => { height.value = Math.max(0, event.height || 0) }
    uni.onKeyboardHeightChange(uniHandler)
    // #endif

    // #ifdef APP-PLUS
    uniHandler = (event) => { height.value = Math.max(0, event.height || 0) }
    uni.onKeyboardHeightChange(uniHandler)
    // #endif
  })

  onUnmounted(() => {
    // #ifdef H5
    const viewport = window.visualViewport
    if (viewport && h5Handler) {
      viewport.removeEventListener('resize', h5Handler)
      viewport.removeEventListener('scroll', h5Handler)
    }
    // #endif

    // #ifdef MP-WEIXIN
    if (uniHandler) uni.offKeyboardHeightChange(uniHandler)
    // #endif

    // #ifdef APP-PLUS
    if (uniHandler) uni.offKeyboardHeightChange(uniHandler)
    // #endif
  })

  return {
    keyboardHeight: height,
    keyboardStyle: computed(() => ({ transform: `translateY(-${height.value}px)` })),
  }
}

