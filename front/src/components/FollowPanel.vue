<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TaskDetail, TaskLogItem } from '../api/task'
import { addLog, deleteLog, getTask } from '../api/task'
import { errMsg, localISODate } from '../utils/taskShared'
import { logOp } from '../composables/useOpLog'

const props = defineProps<{ taskId: number }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'sync-count', payload: { id: number; logCount: number }): void
}>()

// ---------- 卡片跟进面板（悬浮于卡片右侧，放不下时弹左侧；一次只开一张） ----------
const PANEL_WIDTH = 360
const detail = ref<TaskDetail | null>(null)
const panelStyle = ref<Record<string, string>>({})
const showLogForm = ref(false)
const logForm = reactive({ logDate: '', person: '', summary: '', nextStep: '' })
const logSaving = ref(false)

let scrollEl: HTMLElement | null = null
let resizeObserver: ResizeObserver | null = null

// 面板相对 .cards-scroll 内容坐标定位（随卡片一起滚动，无需监听滚动事件）
function positionPanel(cardId: number) {
  scrollEl = document.querySelector<HTMLElement>('.cards-scroll')
  const cardEl = document.querySelector<HTMLElement>(`.task-card[data-id="${cardId}"]`)
  if (!scrollEl || !cardEl) return
  const gap = 10
  const MIN_W = 280 // 面板最小可用宽度，低于此则整卡覆盖式弹出（仅窄屏）
  const viewW = scrollEl.clientWidth
  const maxH = Math.max(240, scrollEl.clientHeight - 24)
  const cardL = cardEl.offsetLeft
  const cardR = cardL + cardEl.offsetWidth

  // 优先贴卡片右侧；右侧放不下则贴左侧；两者都放不下时收窄宽度适配，
  // 保证面板不遮住被点卡片本体
  const rightFit = viewW - 8 - (cardR + gap)
  const leftFit = cardL - gap - 8
  let left: number
  let width: number
  if (rightFit >= MIN_W) {
    width = Math.min(PANEL_WIDTH, rightFit)
    left = cardR + gap
  } else if (leftFit >= MIN_W) {
    width = Math.min(PANEL_WIDTH, leftFit)
    left = cardL - gap - width
  } else {
    width = Math.min(PANEL_WIDTH, viewW - 16)
    left = 8
  }
  left = Math.max(8, left)

  let top = cardEl.offsetTop
  const maxTop = scrollEl.scrollHeight - maxH - 12
  top = Math.min(Math.max(top, 8), maxTop)
  panelStyle.value = { left: `${left}px`, top: `${top}px`, width: `${width}px`, maxHeight: `${maxH}px` }
}

function onResize() {
  if (detail.value) nextTick(() => positionPanel(props.taskId))
}

async function submitLog() {
  if (!detail.value) return
  if (!logForm.summary?.trim()) {
    ElMessage.warning('请填写跟进摘要')
    return
  }
  logSaving.value = true
  try {
    await addLog(detail.value.id, {
      logDate: logForm.logDate || null,
      person: logForm.person,
      summary: logForm.summary.trim(),
      nextStep: logForm.nextStep,
    })
    ElMessage.success('跟进记录已添加')
    const pd = detail.value
    logOp({
      action: 'LOG_ADD',
      targetType: 'task',
      targetId: pd.id,
      targetCode: pd.taskCode ?? undefined,
      detail: `为事项 ${pd.taskCode ?? pd.title} 添加跟进：${logForm.summary.trim()}`,
    })
    detail.value = (await getTask(detail.value.id)).data
    emit('sync-count', { id: detail.value.id, logCount: detail.value.logs.length })
    logForm.summary = ''
    logForm.nextStep = ''
    showLogForm.value = false
  } catch (err: any) {
    ElMessage.error('添加跟进失败：' + errMsg(err))
  } finally {
    logSaving.value = false
  }
}

// 删除单条跟进记录：确认后调后端删除，本地移除记录并同步卡片跟进条数
async function removeLog(log: TaskLogItem) {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm('确认删除这条跟进记录？删除后不可恢复。', '删除跟进记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户取消
  }
  try {
    await deleteLog(detail.value.id, log.id)
    ElMessage.success('跟进记录已删除')
    const pd = detail.value
    logOp({
      action: 'LOG_DELETE',
      targetType: 'task',
      targetId: pd.id,
      targetCode: pd.taskCode ?? undefined,
      detail: `为事项 ${pd.taskCode ?? pd.title} 删除跟进记录：${log.summary ?? ''}`,
    })
    detail.value.logs = detail.value.logs.filter((x) => x.id !== log.id)
    emit('sync-count', { id: pd.id, logCount: detail.value.logs.length })
  } catch (err: any) {
    ElMessage.error('删除跟进失败：' + errMsg(err))
  }
}

onMounted(async () => {
  // 本地时区今天：toISOString() 按 UTC 截取会在凌晨 0-8 点差一天
  logForm.logDate = localISODate()
  try {
    detail.value = (await getTask(props.taskId)).data
    await nextTick()
    positionPanel(props.taskId)
    // 窗口尺寸变化 / 侧栏收起等宽度变化 → 重新定位（ResizeObserver 覆盖侧栏折叠场景）
    window.addEventListener('resize', onResize)
    scrollEl = document.querySelector<HTMLElement>('.cards-scroll')
    if (scrollEl) {
      resizeObserver = new ResizeObserver(() => onResize())
      resizeObserver.observe(scrollEl)
    }
  } catch (err: any) {
    ElMessage.error('加载跟进记录失败：' + errMsg(err))
    emit('close')
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <div v-if="detail" class="log-panel" :style="panelStyle">
    <header class="lp-head">
      <span class="lp-title">跟进记录 · <span class="num">{{ detail.logs.length }} 条</span></span>
      <button type="button" class="lp-close" title="收起" aria-label="收起" @click="emit('close')">✕</button>
    </header>
    <div class="lp-body">
      <el-timeline v-if="detail.logs.length" class="log-timeline">
        <el-timeline-item v-for="l in detail.logs" :key="l.id" :timestamp="l.logDate || ''" placement="top">
          <div class="log-item">
            <button
              type="button"
              class="log-del"
              title="删除该条跟进记录"
              aria-label="删除该条跟进记录"
              @click.stop="removeLog(l)"
            >
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /></svg>
            </button>
            <div><b class="log-person">{{ l.person || '—' }}</b>&nbsp;{{ l.summary || '' }}</div>
            <div v-if="l.nextStep" class="log-next">下一步：{{ l.nextStep }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无跟进记录" :image-size="48" />

      <button v-if="!showLogForm" type="button" class="lp-add" @click="showLogForm = true">＋ 添加跟进</button>
      <div v-else class="log-form">
        <div class="log-form-row">
          <el-date-picker v-model="logForm.logDate" type="date" value-format="YYYY-MM-DD" placeholder="日期" style="width: 100%" />
          <el-input v-model="logForm.person" placeholder="跟进人" />
        </div>
        <el-input v-model="logForm.summary" type="textarea" :rows="2" placeholder="跟进摘要（必填）" />
        <el-input v-model="logForm.nextStep" type="textarea" :rows="1" placeholder="下一步行动（可选）" />
        <div class="log-form-actions">
          <el-button size="small" @click="showLogForm = false">取消</el-button>
          <el-button size="small" type="primary" :loading="logSaving" @click="submitLog">提交</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.log-item {
  position: relative;
  font-size: 13px;
  padding-right: 24px;
}
/* 删除按钮：划过该条记录时浮现右上角 */
.log-del {
  position: absolute;
  top: 0;
  right: 0;
  display: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--c-faint);
  cursor: pointer;
}
.log-item:hover .log-del,
.log-del:focus-visible {
  display: inline-flex;
}
.log-del:hover {
  color: var(--c-st-urgent);
  background: var(--c-st-urgent-soft);
}
.log-del:focus-visible {
  outline: 2px solid var(--c-blue);
  outline-offset: 1px;
}
.log-next {
  color: var(--c-muted);
}

/* ---------- 卡片跟进面板（悬浮于卡片右侧/左侧） ---------- */
.log-panel {
  position: absolute;
  z-index: 6;
  background: var(--c-card);
  border: 1px solid var(--c-line-strong);
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(15, 27, 61, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: lp-in 0.16s ease;
}
@keyframes lp-in {
  from { opacity: 0; transform: translateX(-6px); }
  to { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .log-panel { animation: none; }
}
.lp-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--c-line);
  background: var(--c-bg);
}
.lp-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink);
}
.lp-close {
  flex: 0 0 auto;
  border: none;
  background: transparent;
  color: var(--c-faint);
  font-size: 14px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 5px;
  cursor: pointer;
}
.lp-close:hover {
  color: var(--c-ink);
  background: var(--c-row-hover);
}
.lp-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px 14px;
}
.lp-add {
  width: 100%;
  margin-top: 10px;
  border: 1px dashed var(--c-line-strong);
  background: transparent;
  color: var(--c-blue);
  border-radius: 7px;
  padding: 7px 0;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.lp-add:hover {
  background: var(--c-blue-soft);
  border-color: var(--c-blue);
}

.log-timeline {
  padding-left: 2px;
}
.log-person {
  color: var(--c-blue);
  font-weight: 600;
}
.log-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.log-form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.log-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
