<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ points: Array<{ label: string; value: number }> }>()
const canvas = ref<HTMLCanvasElement | null>(null)
let observer: ResizeObserver | null = null

function shortMoney(value: number) {
  return Math.abs(value) >= 10000 ? `${(value / 10000).toFixed(value >= 100000 ? 0 : 1)}万` : `${Math.round(value)}`
}

function draw() {
  const element = canvas.value
  if (!element) return
  const rect = element.getBoundingClientRect()
  const width = Math.max(320, Math.floor(rect.width))
  const height = Math.max(190, Math.floor(rect.height))
  const ratio = window.devicePixelRatio || 1
  element.width = width * ratio
  element.height = height * ratio
  const context = element.getContext('2d')
  if (!context) return
  context.scale(ratio, ratio)
  context.clearRect(0, 0, width, height)

  const padding = { top: 18, right: 20, bottom: 34, left: 54 }
  const chartWidth = width - padding.left - padding.right
  const chartHeight = height - padding.top - padding.bottom
  const values = props.points.map((item) => Number(item.value || 0))
  const max = Math.max(...values, 1)
  const min = Math.min(...values, 0)
  const range = Math.max(max - min, 1)

  context.font = '500 13px "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", sans-serif'
  context.textBaseline = 'middle'
  for (let index = 0; index <= 3; index += 1) {
    const y = padding.top + chartHeight * index / 3
    context.strokeStyle = '#dbe7e5'
    context.lineWidth = 1
    context.beginPath()
    context.moveTo(padding.left, y)
    context.lineTo(width - padding.right, y)
    context.stroke()
    context.fillStyle = '#6f817f'
    context.textAlign = 'right'
    context.fillText(shortMoney(max - range * index / 3), padding.left - 10, y)
  }

  if (!props.points.length) {
    context.fillStyle = '#6f817f'
    context.textAlign = 'center'
    context.fillText('暂无趋势数据', width / 2, height / 2)
    return
  }

  const step = props.points.length > 1 ? chartWidth / (props.points.length - 1) : 0
  const coordinates = props.points.map((item, index) => ({
    x: padding.left + (props.points.length === 1 ? chartWidth / 2 : step * index),
    y: padding.top + (max - Number(item.value || 0)) / range * chartHeight,
  }))

  context.beginPath()
  coordinates.forEach((point, index) => index ? context.lineTo(point.x, point.y) : context.moveTo(point.x, point.y))
  context.strokeStyle = '#76bdb8'
  context.lineWidth = 2.5
  context.lineJoin = 'round'
  context.lineCap = 'round'
  context.stroke()

  coordinates.forEach((point, index) => {
    context.beginPath()
    context.arc(point.x, point.y, 3.5, 0, Math.PI * 2)
    context.fillStyle = '#fff'
    context.fill()
    context.strokeStyle = '#76bdb8'
    context.lineWidth = 2
    context.stroke()
    context.fillStyle = '#526765'
    context.textAlign = 'center'
    context.fillText(props.points[index].label, point.x, height - 14)
  })
}

watch(() => props.points, () => void nextTick(draw), { deep: true })
onMounted(() => {
  observer = new ResizeObserver(draw)
  if (canvas.value) observer.observe(canvas.value)
  draw()
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <canvas ref="canvas" class="revenue-trend-chart" aria-label="营业额趋势图" />
</template>

<style scoped>
.revenue-trend-chart {
  display: block;
  width: 100%;
  height: 236px;
}
</style>
