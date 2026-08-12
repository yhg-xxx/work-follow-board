<script setup lang="ts">
/**
 * 头部时钟：显示「8月7日 周四 14:30」
 * 每分钟刷新一次，对齐到分钟边界（整分钟时跳变，不带秒）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'

const now = ref(new Date())

const weekMap = ['日', '一', '二', '三', '四', '五', '六']
const formatted = computed(() => {
  const d = now.value
  const m = d.getMonth() + 1
  const day = d.getDate()
  const w = weekMap[d.getDay()]
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${m}月${day}日 周${w} ${hh}:${mm}`
})

let intervalId: number | undefined
let timeoutId: number | undefined
let active = false
onMounted(() => {
  active = true
  const tick = () => {
    now.value = new Date()
  }
  // 对齐到下一个分钟边界：整分钟时刷新，之后每 60s 一次
  const d = new Date()
  const msToNext = (60 - d.getSeconds()) * 1000 - d.getMilliseconds()
  timeoutId = window.setTimeout(() => {
    if (!active) return
    tick()
    intervalId = window.setInterval(tick, 60_000)
  }, msToNext)
})
onUnmounted(() => {
  active = false
  if (timeoutId !== undefined) window.clearTimeout(timeoutId)
  if (intervalId !== undefined) window.clearInterval(intervalId)
})
</script>

<template>
  <span class="clock-label" aria-live="off">{{ formatted }}</span>
</template>

<style scoped>
.clock-label {
  flex: 0 0 auto;
  font-size: 14px;
  line-height: 1;
  color: rgba(255, 255, 255, 0.85);
  /* 数字等宽：分钟跳变时宽度不变，不抖动；中文保持默认字体 */
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
  white-space: nowrap;
  user-select: none;
}
</style>
