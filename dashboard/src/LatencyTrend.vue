<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({ points: { type: Array, required: true } })
const canvas = ref()
const box = ref()
const hover = ref(null)
const plot = { left: 48, right: 14, top: 13, bottom: 27 }
const latest = computed(() => props.points.findLast(point => point.executions > 0))
const hovered = computed(() => hover.value == null ? null : props.points[hover.value.index])
const time = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })

let resize
let width = 0
let height = 0

function draw() {
  if (!canvas.value || !box.value) return
  width = box.value.clientWidth
  height = box.value.clientHeight
  if (width <= plot.left + plot.right || height <= plot.top + plot.bottom) return
  const dpr = window.devicePixelRatio || 1
  canvas.value.width = width * dpr
  canvas.value.height = height * dpr
  const ctx = canvas.value.getContext('2d')
  ctx.scale(dpr, dpr)

  const end = height - plot.bottom
  const chartWidth = width - plot.left - plot.right
  const chartHeight = end - plot.top
  const peak = Math.max(1, ...props.points.map(point => Math.max(point.p95Ms, point.averageMs)))
  const magnitude = 10 ** Math.floor(Math.log10(peak / 4))
  const step = Math.ceil(peak / 4 / magnitude) * magnitude
  const maximum = step * 4
  const x = index => plot.left + index * chartWidth / Math.max(1, props.points.length - 1)
  const y = value => end - value / maximum * chartHeight

  ctx.font = '10px "IBM Plex Mono", monospace'
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'right'
  for (let tick = 0; tick <= 4; tick++) {
    const value = step * tick
    const line = y(value)
    ctx.strokeStyle = '#e7edf5'
    ctx.setLineDash(tick ? [3, 4] : [])
    ctx.beginPath()
    ctx.moveTo(plot.left, line)
    ctx.lineTo(width - plot.right, line)
    ctx.stroke()
    ctx.fillStyle = '#607086'
    ctx.fillText(tick === 0 ? '0' : String(Number(value.toFixed(2))), plot.left - 9, line)
  }
  ctx.setLineDash([])
  const labels = width < 370 ? 3 : 5
  for (let tick = 0; tick < labels && props.points.length; tick++) {
    const index = Math.round(tick * (props.points.length - 1) / (labels - 1))
    ctx.textAlign = tick === 0 ? 'left' : tick === labels - 1 ? 'right' : 'center'
    ctx.fillStyle = '#607086'
    ctx.fillText(time.format(new Date(props.points[index].time)), x(index), end + 16)
  }

  for (const [key, color, fill] of [['p95Ms', '#356de3', true], ['averageMs', '#2a9c8e', false]]) {
    let segment = []
    function drawSegment() {
      if (!segment.length) return
      const first = segment[0]
      const last = segment.at(-1)
      if (fill && segment.length > 1) {
        const gradient = ctx.createLinearGradient(0, plot.top, 0, end)
        gradient.addColorStop(0, '#6b9bfc4a')
        gradient.addColorStop(1, '#d7e6ff1a')
        ctx.fillStyle = gradient
        ctx.beginPath()
        ctx.moveTo(first.x, end)
        for (const point of segment) ctx.lineTo(point.x, point.y)
        ctx.lineTo(last.x, end)
        ctx.closePath()
        ctx.fill()
      }
      ctx.strokeStyle = color
      ctx.lineWidth = 2.2
      ctx.lineJoin = 'round'
      ctx.beginPath()
      ctx.moveTo(first.x, first.y)
      for (const point of segment.slice(1)) ctx.lineTo(point.x, point.y)
      ctx.stroke()
      if (segment.length === 1) {
        ctx.beginPath()
        ctx.fillStyle = color
        ctx.arc(first.x, first.y, 3, 0, Math.PI * 2)
        ctx.fill()
      }
    }
    props.points.forEach((point, index) => {
      if (point.executions) {
        segment.push({ x: x(index), y: y(point[key]) })
      } else {
        drawSegment()
        segment = []
      }
    })
    drawSegment()
  }

  if (hovered.value) {
    const at = x(hover.value.index)
    ctx.strokeStyle = '#8da8d9'
    ctx.lineWidth = 1
    ctx.setLineDash([3, 4])
    ctx.beginPath()
    ctx.moveTo(at, plot.top)
    ctx.lineTo(at, end)
    ctx.stroke()
  }
}

function onMove(event) {
  if (props.points.length < 2) return
  const x = event.clientX - box.value.getBoundingClientRect().left
  const ratio = (x - plot.left) / (width - plot.left - plot.right)
  const index = Math.max(0, Math.min(props.points.length - 1, Math.round(ratio * (props.points.length - 1))))
  hover.value = { index, left: Math.max(4, Math.min(width - 150, x + 10)) }
  draw()
}

function clearHover() {
  hover.value = null
  draw()
}

watch(() => props.points, draw)
onMounted(() => {
  resize = new ResizeObserver(draw)
  resize.observe(box.value)
})
onUnmounted(() => resize?.disconnect())
</script>

<template>
  <figure class="trend-figure" :aria-label="latest
    ? `SQL 执行耗时趋势，最近区间 P95 ${latest.p95Ms.toFixed(2)} 毫秒`
    : 'SQL 执行耗时趋势，当前时间范围暂无采集数据'">
    <div ref="box" class="trend-canvas" @pointermove="onMove" @pointerleave="clearHover">
      <canvas ref="canvas" aria-hidden="true"></canvas>
      <div v-if="!latest" class="chart-empty">当前时间范围暂无采集数据</div>
      <div v-if="hovered" class="chart-tooltip" :style="{ left: `${hover.left}px` }" aria-hidden="true">
        <span>{{ time.format(new Date(hovered.time)) }}</span>
        <template v-if="hovered.executions">
          <div>P95 <b>{{ hovered.p95Ms.toFixed(2) }} ms</b></div>
          <div>平均 <b>{{ hovered.averageMs.toFixed(2) }} ms</b></div>
          <div>执行 <b>{{ hovered.executions }} 次</b></div>
        </template>
        <div v-else>暂无执行数据</div>
      </div>
    </div>
  </figure>
</template>
