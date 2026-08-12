<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { listOpLogs, type OpLogAction, type OpLogItem } from '../api/task'
import { actionLabel, OP_LOGGED_EVENT } from '../composables/useOpLog'

// 受控组件：父组件（App.vue）控制显隐与定位样式，本组件自持筛选、列表、加载与自动刷新
const props = defineProps<{ panelStyle: Record<string, string> }>()
const emit = defineEmits<{ (e: 'close'): void }>()

// 供父组件做「点击面板外部关闭」判定
const rootEl = ref<HTMLElement | null>(null)
defineExpose({ el: rootEl })

const PAGE_SIZE = 300
const MAX_LIMIT = 1000

// 操作类型选项（顺序即展示顺序）
const ACTION_OPTIONS: { value: OpLogAction; label: string }[] = [
  { value: 'CREATE', label: '新建事项' },
  { value: 'UPDATE', label: '编辑事项' },
  { value: 'DELETE', label: '删除事项' },
  { value: 'STATUS', label: '状态流转' },
  { value: 'IMPORT', label: '数据导入' },
  { value: 'EXPORT', label: '数据导出' },
  { value: 'LOG_ADD', label: '添加跟进' },
  { value: 'LOG_DELETE', label: '删除跟进' },
]

// 操作类型 → 徽标配色（soft 底 + 主色字，深浅色通用）
const ACTION_COLORS: Record<string, { bg: string; fg: string }> = {
  CREATE: { bg: 'rgba(46, 158, 107, 0.14)', fg: '#2E9E6B' },
  UPDATE: { bg: 'rgba(36, 86, 201, 0.12)', fg: '#2456C9' },
  DELETE: { bg: 'rgba(214, 69, 80, 0.12)', fg: '#D64550' },
  STATUS: { bg: 'rgba(232, 163, 61, 0.16)', fg: '#C07F1D' },
  IMPORT: { bg: 'rgba(139, 92, 246, 0.14)', fg: '#8B5CF6' },
  EXPORT: { bg: 'rgba(43, 89, 195, 0.12)', fg: '#2B59C3' },
  LOG_ADD: { bg: 'rgba(46, 158, 107, 0.14)', fg: '#2E9E6B' },
  LOG_DELETE: { bg: 'rgba(214, 69, 80, 0.12)', fg: '#D64550' },
}

const filters = reactive<{ actions: OpLogAction[]; dateRange: [string, string] | null }>({
  actions: [],
  dateRange: null,
})
const logs = ref<OpLogItem[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(false)

const bodyRef = ref<HTMLElement | null>(null)
const sentinelRef = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

function fmtTime(s: string | null): string {
  if (!s) return '—'
  return s.replace('T', ' ').slice(0, 19)
}

function buildQuery(limit: number): Record<string, unknown> {
  const params: Record<string, unknown> = { limit }
  if (filters.actions.length) params.action = filters.actions
  if (filters.dateRange) {
    params.dateFrom = filters.dateRange[0]
    params.dateTo = filters.dateRange[1]
  }
  return params
}

async function fetchFirst() {
  loading.value = true
  try {
    const { data } = await listOpLogs(buildQuery(PAGE_SIZE))
    logs.value = data
    hasMore.value = data.length >= PAGE_SIZE
    // 换筛选/刷新后回到列表顶部
    if (bodyRef.value) bodyRef.value.scrollTop = 0
  } catch {
    logs.value = []
    hasMore.value = false
  } finally {
    loading.value = false
    maybeFill()
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  const nextLimit = Math.min(logs.value.length + PAGE_SIZE, MAX_LIMIT)
  if (nextLimit <= logs.value.length) return
  loadingMore.value = true
  try {
    const { data } = await listOpLogs(buildQuery(nextLimit))
    logs.value = data
    hasMore.value = data.length >= nextLimit && nextLimit < MAX_LIMIT
  } finally {
    loadingMore.value = false
    maybeFill()
  }
}

/** 列表未撑满滚动区时自动继续加载，直到可滚动或加载完 */
function maybeFill() {
  requestAnimationFrame(() => {
    const body = bodyRef.value
    if (body && hasMore.value && !loading.value && !loadingMore.value && body.scrollHeight <= body.clientHeight + 4) {
      loadMore()
    }
  })
}

// 滚动到底自动加载下一页
function setupObserver() {
  teardownObserver()
  const body = bodyRef.value
  const sentinel = sentinelRef.value
  if (!body || !sentinel) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((en) => en.isIntersecting)) loadMore()
    },
    { root: body, rootMargin: '120px 0px' },
  )
  observer.observe(sentinel)
}
function teardownObserver() {
  observer?.disconnect()
  observer = null
}

// 筛选变化 → 重新拉首页
watch(() => [filters.actions, filters.dateRange] as const, fetchFirst)

// 打开期间自动刷新：主页面产生新操作日志时触发
function onOpLogged() {
  fetchFirst()
}

onMounted(() => {
  fetchFirst()
  setupObserver()
  window.addEventListener(OP_LOGGED_EVENT, onOpLogged)
})
onBeforeUnmount(() => {
  teardownObserver()
  window.removeEventListener(OP_LOGGED_EVENT, onOpLogged)
})
</script>

<template>
  <!-- 操作日志悬浮面板（fixed 定位：贴 header 入口按钮下方，父组件注入 panelStyle） -->
  <div ref="rootEl" class="op-panel" :style="panelStyle">
    <header class="op-head">
      <span class="op-title">操作日志</span>
      <span v-if="logs.length" class="op-count">{{ logs.length }} 条</span>
      <div class="head-right">
        <button type="button" class="icon-btn" title="刷新" aria-label="刷新" @click="fetchFirst">⟳</button>
        <button type="button" class="icon-btn" title="收起" aria-label="收起" @click="emit('close')">✕</button>
      </div>
    </header>

    <!-- 顶部筛选：日期区间 + 操作类型 -->
    <div class="op-filter">
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        unlink-panels
        clearable
        style="width: 100%"
      />
      <el-select
        v-model="filters.actions"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="操作类型"
        clearable
        style="width: 100%"
      >
        <el-option v-for="a in ACTION_OPTIONS" :key="a.value" :label="a.label" :value="a.value" />
      </el-select>
    </div>

    <!-- 日志列表（紧凑两行） -->
    <div ref="bodyRef" class="op-body" v-loading="loading">
      <div v-if="!logs.length && !loading" class="op-empty">暂无操作记录</div>
      <div v-for="row in logs" :key="row.id" class="op-item">
        <div class="op-row1">
          <span class="op-time">{{ fmtTime(row.createdAt) }}</span>
          <span
            class="action-tag"
            :style="{ background: ACTION_COLORS[row.action]?.bg, color: ACTION_COLORS[row.action]?.fg }"
          >{{ actionLabel(row.action) }}</span>
          <span v-if="row.targetCode" class="op-code">{{ row.targetCode }}</span>
        </div>
        <div class="op-row2">{{ row.detail || '—' }}</div>
      </div>
      <div ref="sentinelRef" class="op-sentinel">
        {{ loadingMore ? '加载中…' : hasMore ? '上拉加载更多' : logs.length ? '已加载全部' : '' }}
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 操作日志悬浮面板（高级筛选 / 跟进面板同视觉风格） ---------- */
.op-panel {
  position: fixed;
  z-index: 30;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(15, 27, 61, 0.22); /* 浅色：蓝雾台账风投影 */
  animation: op-in 0.16s ease;
}
/* 深色：加重投影 + 外层细蓝描边，提升「浮起感」与对比度 */
[data-theme='dark'] .op-panel {
  box-shadow:
    0 12px 36px rgba(0, 0, 0, 0.65),
    0 2px 6px rgba(0, 0, 0, 0.4),
    0 0 0 1px rgba(91, 138, 230, 0.22) inset;
  border-color: #31426A;
}
@keyframes op-in {
  from { opacity: 0; transform: translateY(-6px); }
  to   { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .op-panel { animation: none; }
}

/* ---------- 头部 ---------- */
.op-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--c-line);
  background: var(--c-bg);
}
.op-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--c-ink);
}
.op-count {
  font-size: 12px;
  color: var(--c-muted);
  background: var(--c-blue-soft);
  border-radius: 999px;
  padding: 1px 8px;
}
.head-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}
.icon-btn {
  border: none;
  background: transparent;
  color: var(--c-faint);
  font-size: 14px;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.icon-btn:hover {
  color: var(--c-ink);
  background: var(--c-row-hover);
}

/* ---------- 顶部筛选（一行：日期占多数宽度、操作类型自适应） ---------- */
.op-filter {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--c-line);
  background: var(--c-card);
}

/* ---------- 日志列表 ---------- */
.op-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 14px 2px;
}
.op-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--c-faint);
  font-size: 13px;
}
.op-item {
  padding: 9px 8px;
  border-radius: 8px;
  transition: background 0.15s ease;
}
.op-item:hover {
  background: var(--c-row-hover);
}
.op-item + .op-item {
  border-top: 1px solid var(--c-line);
}
.op-row1 {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.op-time {
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-soft);
  white-space: nowrap;
}
.action-tag {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
}
.op-code {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-blue);
  white-space: nowrap;
}
.op-row2 {
  margin-top: 5px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-ink);
  word-break: break-all;
}
.op-sentinel {
  padding: 8px 0 10px;
  text-align: center;
  font-size: 12px;
  color: var(--c-faint);
}
</style>
